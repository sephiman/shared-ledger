package com.sephilabs.sharedledger.networth.cash

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.snapshot.AssetValueInput
import com.sephilabs.sharedledger.networth.snapshot.AutoSnapshotService
import com.sephilabs.sharedledger.networth.snapshot.SnapshotFrequency
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRequest
import com.sephilabs.sharedledger.networth.snapshot.SnapshotService
import com.sephilabs.sharedledger.networth.snapshot.VALUE_SOURCE_CARRIED_OVER
import com.sephilabs.sharedledger.networth.snapshot.VALUE_SOURCE_COMPUTED
import com.sephilabs.sharedledger.networth.snapshot.VALUE_SOURCE_OVERRIDDEN
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.Transaction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Cash flows into a snapshot through its adjustment series + estimate: the form prefills the
 * estimate (marked computed), correcting it re-anchors (writes a new adjustment), accepting it
 * writes nothing, and with no series cash still just carries over.
 */
class CashSnapshotIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val service: SnapshotService,
    private val autoSnapshots: AutoSnapshotService,
    private val snapshots: SnapshotRepository,
    private val adjustments: CashAdjustmentRepository,
    private val transactions: TransactionRepository,
) : IntegrationTestBase() {

    private val allCodes = listOf("cash", "fund", "etfs", "stocks", "crypto", "pension")
    private val anchorDate = LocalDate.of(2026, 6, 1)
    private val snapDate = LocalDate.of(2026, 6, 30)

    @Test
    fun `named-values prefills cash with the estimate`() {
        val (user, household) = seed()
        adjustment(household.id, user.id, anchorDate, "1000.00")
        income(household.id, user.id, LocalDate.of(2026, 6, 5), "200.00")

        val cash = service.namedValuesAt(household.id, snapDate).cash

        assertThat(cash).isNotNull()
        assertThat(BigDecimal(cash)).isEqualByComparingTo("1200.00")
    }

    @Test
    fun `accepting the estimate marks cash computed and writes no new adjustment`() {
        val (user, household) = seed()
        adjustment(household.id, user.id, anchorDate, "1000.00")
        income(household.id, user.id, LocalDate.of(2026, 6, 5), "200.00")

        // The form submits the estimate value with no explicit source -> inferred computed.
        val dto = service.create(household.id, request(snapDate, cash = "1200.00"), user)

        val cashRow = dto.assets.first { it.assetClassCode == "cash" }
        assertThat(cashRow.value).isEqualByComparingTo("1200.00")
        assertThat(cashRow.valueSource).isEqualTo(VALUE_SOURCE_COMPUTED)
        assertThat(adjustments.findAllByHouseholdIdOrderByAdjustmentDateDescCreatedAtDesc(household.id)).hasSize(1)
    }

    @Test
    fun `editing a snapshot whose cash is computed re-validates and writes no adjustment`() {
        val (user, household) = seed()
        adjustment(household.id, user.id, anchorDate, "1000.00")
        income(household.id, user.id, LocalDate.of(2026, 6, 5), "200.00")
        val created = service.create(household.id, request(snapDate, cash = "1200.00"), user)

        // Re-save with cash explicitly 'computed' (the form re-sends the frozen source on edit).
        val cashInput = AssetValueInput("cash", BigDecimal("1200.00"), VALUE_SOURCE_COMPUTED)
        val others = allCodes.filter { it != "cash" }.map { AssetValueInput(it, BigDecimal("0.00")) }
        val dto = service.update(
            household.id,
            created.id,
            SnapshotRequest(snapDate, null, listOf(cashInput) + others, emptyList(), emptyList(), true),
            user,
        )

        assertThat(dto.assets.first { it.assetClassCode == "cash" }.valueSource).isEqualTo(VALUE_SOURCE_COMPUTED)
        // Editing never re-anchors: the series is unchanged.
        assertThat(adjustments.findAllByHouseholdIdOrderByAdjustmentDateDescCreatedAtDesc(household.id)).hasSize(1)
    }

    @Test
    fun `correcting cash in a snapshot re-anchors with a new adjustment`() {
        val (user, household) = seed()
        adjustment(household.id, user.id, anchorDate, "1000.00")
        income(household.id, user.id, LocalDate.of(2026, 6, 5), "200.00")

        // Estimate is 1200; the user corrects to 1500.
        val dto = service.create(household.id, request(snapDate, cash = "1500.00"), user)

        val cashRow = dto.assets.first { it.assetClassCode == "cash" }
        assertThat(cashRow.value).isEqualByComparingTo("1500.00")
        assertThat(cashRow.valueSource).isEqualTo(VALUE_SOURCE_OVERRIDDEN)

        val series = adjustments.findAllByHouseholdIdOrderByAdjustmentDateDescCreatedAtDesc(household.id)
        assertThat(series).hasSize(2)
        val reAnchor = series.first { it.adjustmentDate == snapDate }
        assertThat(reAnchor.amount).isEqualByComparingTo("1500.00")
    }

    @Test
    fun `with no adjustment series cash carries over and no adjustment is created`() {
        val (user, household) = seed()
        // A first manual snapshot with a plain cash value (compatibility: behaves as today).
        service.create(household.id, request(anchorDate, cash = "500.00"), user)

        val created = autoSnapshots.runForHousehold(household.id, SnapshotFrequency.daily, snapDate)

        assertThat(created).isTrue()
        val snap = snapshots.findUpTo(household.id, snapDate).first { it.snapshotDate == snapDate }
        val cashRow = service.toDto(snap).assets.first { it.assetClassCode == "cash" }
        assertThat(cashRow.value).isEqualByComparingTo("500.00")
        assertThat(cashRow.valueSource).isEqualTo(VALUE_SOURCE_CARRIED_OVER)
        assertThat(adjustments.findAllByHouseholdIdOrderByAdjustmentDateDescCreatedAtDesc(household.id)).isEmpty()
    }

    @Test
    fun `auto-snapshot sources cash from the estimate and never re-anchors`() {
        val (user, household) = seed()
        adjustment(household.id, user.id, anchorDate, "1000.00")
        income(household.id, user.id, LocalDate.of(2026, 6, 5), "200.00")
        val runDate = LocalDate.of(2026, 6, 15)

        val created = autoSnapshots.runForHousehold(household.id, SnapshotFrequency.daily, runDate)

        assertThat(created).isTrue()
        val snap = snapshots.findUpTo(household.id, runDate).first { it.snapshotDate == runDate }
        val cashRow = service.toDto(snap).assets.first { it.assetClassCode == "cash" }
        assertThat(cashRow.value).isEqualByComparingTo("1200.00")
        assertThat(cashRow.valueSource).isEqualTo(VALUE_SOURCE_COMPUTED)
        // Automated runs accept the estimate -> no new adjustment.
        assertThat(adjustments.findAllByHouseholdIdOrderByAdjustmentDateDescCreatedAtDesc(household.id)).hasSize(1)
    }

    // --- helpers ---

    private fun request(date: LocalDate, cash: String) = SnapshotRequest(
        snapshotDate = date,
        note = null,
        assets = allCodes.map { AssetValueInput(it, BigDecimal(if (it == "cash") cash else "0.00")) },
        liabilities = emptyList(),
        namedAssets = emptyList(),
        confirmLargeChanges = true,
    )

    private fun adjustment(householdId: UUID, userId: UUID, date: LocalDate, amount: String) =
        adjustments.save(
            CashAdjustment(
                householdId = householdId,
                adjustmentDate = date,
                amount = BigDecimal(amount),
                createdByUserId = userId,
                updatedByUserId = userId,
            ),
        )

    private fun income(householdId: UUID, userId: UUID, date: LocalDate, amount: String) =
        transactions.save(
            Transaction(
                householdId = householdId,
                occurrenceDate = date,
                direction = Direction.income,
                categoryCode = "misc",
                amount = BigDecimal(amount),
                createdByUserId = userId,
                updatedByUserId = userId,
            ),
        )

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "cs${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        members.save(HouseholdMember(HouseholdMemberId(household.id, user.id), HouseholdRole.owner))
        return user to household
    }
}
