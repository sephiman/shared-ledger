package com.sephilabs.sharedledger.networth.cash

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.lending.InterestType
import com.sephilabs.sharedledger.lending.Lending
import com.sephilabs.sharedledger.lending.LendingPayment
import com.sephilabs.sharedledger.lending.LendingPaymentRepository
import com.sephilabs.sharedledger.lending.LendingRepository
import com.sephilabs.sharedledger.networth.liability.Liability
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.networth.movement.MovementRepository
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.networth.movement.NetWorthMovement
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.Transaction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Cash estimate = latest adjustment on/before the date + net of marked flows strictly after it
 *  (end-of-day). Each flow carries its own sign, per-type toggles gate flow types, no adjustment = no estimate. */
class CashEstimateIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: CashEstimateService,
    private val adjustments: CashAdjustmentRepository,
    private val transactions: TransactionRepository,
    private val movements: MovementRepository,
    private val lendings: LendingRepository,
    private val lendingPayments: LendingPaymentRepository,
    private val liabilities: LiabilityRepository,
) : IntegrationTestBase() {

    private val anchorDate = LocalDate.of(2026, 6, 1)
    private val asOf = LocalDate.of(2026, 6, 30)

    @Test
    fun `estimate sums signed flows across all three types after the anchor`() {
        val (user, household) = seed()
        adjustment(household.id, user.id, anchorDate, "1000.00")

        income(household.id, user.id, LocalDate.of(2026, 6, 5), "200.00")
        expense(household.id, user.id, LocalDate.of(2026, 6, 10), "50.00")
        movement(household.id, user.id, MovementType.withdrawal, LocalDate.of(2026, 6, 7), "300.00")
        movement(household.id, user.id, MovementType.contribution, LocalDate.of(2026, 6, 8), "100.00")
        val mortgage = liabilities.save(Liability(householdId = household.id, name = "Mortgage", createdByUserId = user.id, updatedByUserId = user.id))
        debtPayment(household.id, user.id, mortgage.id, LocalDate.of(2026, 6, 9), "40.00")
        val lending = lentOut(household.id, user.id, LocalDate.of(2026, 6, 6), "500.00")
        repayment(lending.id, user.id, LocalDate.of(2026, 6, 11), "80.00")

        val e = service.estimateAt(household.id, asOf)

        // 1000 +200 -50 +300 -100 -40 -500 +80 = 890
        assertThat(e.estimate).isEqualByComparingTo("890.00")
        assertThat(e.anchorDate).isEqualTo(anchorDate)
        assertThat(e.anchorAmount).isEqualByComparingTo("1000.00")
        assertThat(e.flows.transactions).isEqualByComparingTo("150.00")
        assertThat(e.flows.movements).isEqualByComparingTo("160.00")
        assertThat(e.flows.lendings).isEqualByComparingTo("-420.00")
    }

    @Test
    fun `a flow on the anchor day is already included, only the next day counts`() {
        val (user, household) = seed()
        adjustment(household.id, user.id, anchorDate, "1000.00")
        income(household.id, user.id, anchorDate, "100.00") // same day -> excluded
        income(household.id, user.id, anchorDate.plusDays(1), "100.00") // next day -> counts

        val e = service.estimateAt(household.id, asOf)

        assertThat(e.estimate).isEqualByComparingTo("1100.00")
    }

    @Test
    fun `between same-day adjustments the last created wins as that day's truth`() {
        val (user, household) = seed()
        adjustment(household.id, user.id, anchorDate, "500.00")
        Thread.sleep(5) // guarantee a distinct createdAt for a deterministic tie-break
        adjustment(household.id, user.id, anchorDate, "800.00")

        val e = service.estimateAt(household.id, anchorDate)

        assertThat(e.anchorAmount).isEqualByComparingTo("800.00")
        assertThat(e.estimate).isEqualByComparingTo("800.00")
    }

    @Test
    fun `a disabled flow type is excluded from the estimate`() {
        val (user, household) = seed()
        adjustment(household.id, user.id, anchorDate, "1000.00")
        income(household.id, user.id, LocalDate.of(2026, 6, 5), "200.00")
        movement(household.id, user.id, MovementType.withdrawal, LocalDate.of(2026, 6, 7), "300.00")

        service.updateSettings(household.id, includeTransactions = false, includeLendings = true, includeMovements = true, by = user)

        val e = service.estimateAt(household.id, asOf)

        // Transactions off -> only the +300 movement adjusts the 1000 anchor.
        assertThat(e.flows.transactions).isEqualByComparingTo("0.00")
        assertThat(e.estimate).isEqualByComparingTo("1300.00")
    }

    @Test
    fun `with no adjustment there is no estimate`() {
        val (user, household) = seed()
        income(household.id, user.id, LocalDate.of(2026, 6, 5), "200.00")

        val e = service.estimateAt(household.id, asOf)

        assertThat(e.estimate).isNull()
        assertThat(e.anchorDate).isNull()
    }

    @Test
    fun `settings default to all on and are created lazily`() {
        val (_, household) = seed()
        val s = service.getOrCreateSettings(household.id)
        assertThat(s.includeTransactions).isTrue()
        assertThat(s.includeLendings).isTrue()
        assertThat(s.includeMovements).isTrue()
    }

    // --- helpers ---

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
        tx(householdId, userId, Direction.income, date, amount)

    private fun expense(householdId: UUID, userId: UUID, date: LocalDate, amount: String) =
        tx(householdId, userId, Direction.expense, date, amount)

    private fun tx(householdId: UUID, userId: UUID, direction: Direction, date: LocalDate, amount: String) =
        transactions.save(
            Transaction(
                householdId = householdId,
                occurrenceDate = date,
                direction = direction,
                categoryCode = "misc",
                amount = BigDecimal(amount),
                createdByUserId = userId,
                updatedByUserId = userId,
            ),
        )

    private fun movement(householdId: UUID, userId: UUID, type: MovementType, date: LocalDate, amount: String) =
        movements.save(
            NetWorthMovement(
                householdId = householdId,
                movementDate = date,
                type = type,
                assetClassCode = "crypto",
                liabilityId = null,
                amount = BigDecimal(amount),
                createdByUserId = userId,
                updatedByUserId = userId,
            ),
        )

    private fun debtPayment(householdId: UUID, userId: UUID, liabilityId: UUID, date: LocalDate, amount: String) =
        movements.save(
            NetWorthMovement(
                householdId = householdId,
                movementDate = date,
                type = MovementType.debt_payment,
                assetClassCode = null,
                liabilityId = liabilityId,
                amount = BigDecimal(amount),
                createdByUserId = userId,
                updatedByUserId = userId,
            ),
        )

    private fun lentOut(householdId: UUID, userId: UUID, date: LocalDate, amount: String) =
        lendings.save(
            Lending(
                householdId = householdId,
                borrowerName = "Bob",
                principalAmount = BigDecimal(amount),
                startDate = date,
                interestType = InterestType.none,
                createdByUserId = userId,
                updatedByUserId = userId,
            ),
        )

    private fun repayment(lendingId: UUID, userId: UUID, date: LocalDate, amount: String) =
        lendingPayments.save(
            LendingPayment(
                lendingId = lendingId,
                paymentDate = date,
                amount = BigDecimal(amount),
                createdByUserId = userId,
                updatedByUserId = userId,
            ),
        )

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "cash${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
