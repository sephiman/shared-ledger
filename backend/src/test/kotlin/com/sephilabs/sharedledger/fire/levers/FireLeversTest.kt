package com.sephilabs.sharedledger.fire.levers

import com.sephilabs.sharedledger.fire.FirePreviewRequest
import com.sephilabs.sharedledger.fire.FireService
import com.sephilabs.sharedledger.fire.FireTierKey
import com.sephilabs.sharedledger.fire.fireSettingsRequest
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.snapshot.Snapshot
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValue
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValueId
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRequest
import com.sephilabs.sharedledger.transaction.TransactionService
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class FireLeversTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val users: UserRepository,
    private val snapshots: SnapshotRepository,
    private val transactions: TransactionService,
    private val fireService: FireService,
) : AuthHttpTestBase() {

    @Test
    fun `each lever equals a manual sandbox run with the same values`() {
        val f = fixture()
        val levers = getLevers(f)
        val rows = levers["rows"].toList()
        assertThat(rows.map { it["key"].asString() }).containsExactlyInAnyOrder(
            "contribute_more", "spend_less", "lump_sum", "safer_withdrawal", "returns_up", "returns_down",
        )

        val photo = fireService.project(f.householdId)
        val baseline = photo.scenarios[0].tierStats.single { it.tier == FireTierKey.fire }
        assertThat(levers["baselineProbability"].asDouble()).isEqualTo(baseline.probabilityOfReachingTarget)
        assertThat(levers["baselineYear"].takeUnless { it.isNull }?.asInt()).isEqualTo(baseline.estimatedYear)

        for (row in rows) {
            val preview = objectMapper.treeToValue(row["preview"], FirePreviewRequest::class.java)
            val manual = fireService.project(f.householdId, preview).scenarios[0].tierStats.single { it.tier == FireTierKey.fire }
            val key = row["key"].asString()
            assertThat(row["probability"].asDouble()).describedAs(key).isEqualTo(manual.probabilityOfReachingTarget)
            assertThat(row["estimatedYear"].takeUnless { it.isNull }?.asInt()).describedAs(key).isEqualTo(manual.estimatedYear)
            assertThat(row["probabilityChangePoints"].asDouble()).describedAs(key)
                .isCloseTo((manual.probabilityOfReachingTarget - baseline.probabilityOfReachingTarget) * 100.0, Offset.offset(1e-9))
        }
    }

    @Test
    fun `levers carry the named values and the real withdrawal rates`() {
        val f = fixture()
        val photo = fireService.project(f.householdId)
        val rows = getLevers(f)["rows"].toList().associateBy { it["key"].asString() }

        val more = rows.getValue("contribute_more")
        assertThat(BigDecimal(more["preview"]["monthlyContribution"].asString())).isEqualByComparingTo("1600.00")
        assertThat(BigDecimal(more["amount"].asString())).isEqualByComparingTo("100")

        val less = rows.getValue("spend_less")
        assertThat(BigDecimal(less["preview"]["monthlySpending"].asString()))
            .isEqualByComparingTo(photo.spending.totalMonthly.multiply(BigDecimal("0.95")).setScale(2, java.math.RoundingMode.HALF_EVEN))
        assertThat(BigDecimal(less["preview"]["monthlyEssentialSpending"].asString()))
            .isEqualByComparingTo(photo.spending.essentialMonthly.multiply(BigDecimal("0.95")).setScale(2, java.math.RoundingMode.HALF_EVEN))

        assertThat(BigDecimal(rows.getValue("lump_sum")["preview"]["lumpSum"].asString())).isEqualByComparingTo("10000")

        val safer = rows.getValue("safer_withdrawal")
        assertThat(BigDecimal(safer["withdrawalFromPct"].asString())).isEqualByComparingTo("4.0")
        assertThat(BigDecimal(safer["withdrawalToPct"].asString())).isEqualByComparingTo("3.5")
        assertThat(BigDecimal(safer["preview"]["safeWithdrawalRatePct"].asString())).isEqualByComparingTo("3.5")
        // A more prudent rate needs a bigger pot: it delays, and the row says so rather than hiding.
        assertThat(safer["probabilityChangePoints"].asDouble()).isNegative()

        assertThat(rows.getValue("returns_up")["outOfYourHands"].asBoolean()).isTrue()
        assertThat(BigDecimal(rows.getValue("returns_down")["preview"]["returnShiftPct"].asString())).isEqualByComparingTo("-1")
        assertThat(rows.getValue("contribute_more")["outOfYourHands"].asBoolean()).isFalse()
    }

    @Test
    fun `rows are ordered by their effect on the chance of getting there`() {
        val f = fixture()
        val changes = getLevers(f)["rows"].toList().map { it["probabilityChangePoints"].asDouble() }
        assertThat(changes).isSortedAccordingTo(Comparator.reverseOrder())
    }

    @Test
    fun `spending levers do not apply to an own target`() {
        val f = fixture()
        fireService.update(
            f.householdId,
            fireSettingsRequest(referenceTier = FireTierKey.custom, tierCustomEnabled = true, targetAmount = "600000.00"),
            f.user,
        )
        val less = getLevers(f)["rows"].toList().single { it["key"].asString() == "spend_less" }
        assertThat(less["applicable"].asBoolean()).isFalse()
        assertThat(less["preview"].isNull).isTrue()
    }

    // -------------------------------------------------------------------------------------

    private class Fixture(val householdId: UUID, val user: User, val session: Cookie)

    private fun fixture(): Fixture {
        val email = newEmail("fire-levers")
        val session = sessionOf(register(email))
        val householdId = UUID.fromString(objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString())
        val user = requireNotNull(users.findByEmailIgnoreCase(email))
        val f = Fixture(householdId, user, session)
        fireService.update(householdId, fireSettingsRequest(), user)
        val today = LocalDate.now()
        (0L until 12L).forEach { back ->
            transactions.create(
                householdId,
                TransactionRequest(
                    occurrenceDate = YearMonth.from(today).minusMonths(back).atDay(1),
                    direction = Direction.expense,
                    categoryCode = "groceries.groceries",
                    amount = BigDecimal("2000.00"),
                    description = null,
                ),
                user,
            )
        }
        val snapshot = Snapshot(householdId = householdId, snapshotDate = today, createdByUserId = user.id, updatedByUserId = user.id)
        snapshot.assetValues.add(SnapshotAssetValue(SnapshotAssetValueId(snapshot.id, "etfs"), BigDecimal("200000.00")))
        snapshots.save(snapshot)
        return f
    }

    private fun getLevers(f: Fixture): JsonNode =
        objectMapper.readTree(
            mockMvc.perform(get("/api/households/${f.householdId}/fire/levers").cookie(f.session))
                .andExpect(status().isOk).andReturn().response.contentAsString,
        )
}
