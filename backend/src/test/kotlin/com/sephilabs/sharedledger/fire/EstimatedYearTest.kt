package com.sephilabs.sharedledger.fire

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.snapshot.Snapshot
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValue
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValueId
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class EstimatedYearTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val snapshots: SnapshotRepository,
    private val fireService: FireService,
) : IntegrationTestBase() {

    private fun setup(): Pair<Household, User> {
        val user = users.save(User(email = "est-${System.nanoTime()}@test.local", passwordHash = "x"))
        val household = households.save(Household(name = "EstHouse", currency = "EUR"))
        val snap = snapshots.save(
            Snapshot(
                householdId = household.id,
                snapshotDate = LocalDate.of(2026, 1, 1),
                createdByUserId = user.id,
                updatedByUserId = user.id,
            ),
        )
        snap.assetValues.add(SnapshotAssetValue(SnapshotAssetValueId(snap.id, "stocks"), BigDecimal("100000.00")))
        snapshots.save(snap)
        return household to user
    }

    private fun project(
        scenarios: List<Pair<String, String>>,
        inflationPct: String,
        taxOn: Boolean,
        monthlyContribution: String = "1500.00",
        totalSpending: String = "2000.00",
    ): FireProjectionResponse {
        val (household, user) = setup()
        fireService.update(
            household.id,
            FireSettingsRequest(
                targetAmount = BigDecimal("650000.00"),
                targetYear = 2046,
                monthlyContribution = BigDecimal(monthlyContribution),
                returnScenarios = scenarios.mapIndexed { i, (mean, sd) ->
                    ReturnScenarioInput(BigDecimal(mean), BigDecimal(sd), false, "S$i")
                },
                qualifyingAssetClasses = listOf("stocks"),
                expectedInflationPct = BigDecimal(inflationPct),
                safeWithdrawalRatePct = BigDecimal("4.00"),
                fatMultiplier = BigDecimal("1.50"),
                contributionMode = ContributionMode.manual,
                essentialSpendingMode = SpendingBaseMode.manual,
                manualEssentialSpending = BigDecimal("1200.00"),
                totalSpendingMode = SpendingBaseMode.manual,
                manualTotalSpending = BigDecimal(totalSpending),
                tierCustomEnabled = true,
                applyCapitalGainsTax = taxOn,
                fallbackGainFractionPct = BigDecimal("50.00"),
                taxBrackets = FireDefaults.SPANISH_SAVINGS_TAX_BRACKETS.map { TaxBracketInput(it.lowerBound, it.ratePct) },
            ),
            user,
        )
        return fireService.project(household.id)
    }

    /** The year the chart's p50 line first reaches the chart's target line, or null within the horizon. */
    private fun p50CrossingYear(sc: FireScenarioOutput, tier: FireTierOutput): Int? =
        sc.percentiles.zip(tier.targetCurve).firstOrNull { (p, target) -> p.p50 >= target.value }?.first?.year

    private fun cells(p: FireProjectionResponse) = p.scenarios.flatMap { sc ->
        p.tiers.filter { it.targetToday != null }.map { tier -> Triple(sc, tier, sc.tierStats.single { it.tier == tier.key }) }
    }

    @Test
    fun `estimated year equals the chart p50 crossing for every scenario and tier when tax is off and paths cannot dip`() {
        // Clamped worst-case return (mean − 3σ, ≈ 2.4 %) beats 1.5 % inflation, so a trial that reaches stays above.
        val p = project(listOf("6.00" to "1.20", "8.00" to "2.00", "10.00" to "2.50"), inflationPct = "1.50", taxOn = false)
        val soft = SoftAssertions()
        for ((sc, tier, stat) in cells(p)) {
            soft.assertThat(stat.estimatedYear).describedAs("${sc.name}/${tier.key}").isEqualTo(p50CrossingYear(sc, tier))
        }
        soft.assertAll()
        assertThat(cells(p).map { it.third.estimatedYear }).describedAs("fixture must exercise both outcomes").contains(null)
        assertThat(cells(p).mapNotNull { it.third.estimatedYear }).isNotEmpty
    }

    @Test
    fun `estimated year is never later than the chart p50 crossing, with tax off or on`() {
        val soft = SoftAssertions()
        for (taxOn in listOf(false, true)) {
            val p = project(HIGH_VOLATILITY, inflationPct = "2.50", taxOn = taxOn)
            for ((sc, tier, stat) in cells(p)) {
                val crossing = p50CrossingYear(sc, tier) ?: continue
                val label = "tax=$taxOn ${sc.name}/${tier.key}"
                soft.assertThat(stat.estimatedYear).describedAs(label).isNotNull
                stat.estimatedYear?.let { soft.assertThat(it).describedAs(label).isLessThanOrEqualTo(crossing) }
            }
        }
        soft.assertAll()
    }

    @Test
    fun `with high volatility the gap comes from trials that reach and later dip, and exists with tax off`() {
        // Tax off: every trial chases the same drawn target. At the estimated year half of all trials have
        // reached it, yet the median wealth is below it — only trials that touched the target and fell back
        // can explain that.
        val p = project(HIGH_VOLATILITY, inflationPct = "2.50", taxOn = false)
        val dipped = cells(p).filter { (sc, tier, stat) ->
            val year = stat.estimatedYear ?: return@filter false
            val idx = year - p.startYear
            sc.percentiles[idx].p50 < tier.targetCurve[idx].value
        }
        assertThat(dipped).describedAs("cells whose median sits below the target at the estimated year").isNotEmpty
        for ((sc, tier, stat) in dipped) {
            assertThat(p50CrossingYear(sc, tier) ?: Int.MAX_VALUE).isGreaterThan(stat.estimatedYear!!)
        }
    }

    @Test
    fun `a year exists exactly when at least half of all trials reach the target`() {
        val p = project(HIGH_VOLATILITY, inflationPct = "2.50", taxOn = true)
        for ((sc, tier, stat) in cells(p)) {
            assertThat(stat.estimatedYear != null)
                .describedAs("${sc.name}/${tier.key} p=${stat.probabilityOfReachingTarget}")
                .isEqualTo(stat.probabilityOfReachingTarget >= 0.5)
            assertThat(stat.coastEstimatedYear != null).isEqualTo(stat.coastProbabilityOfReachingTarget >= 0.5)
            if (stat.estimatedYear != null) {
                assertThat(stat.estimatedYear).isGreaterThanOrEqualTo(stat.medianYearAmongReaching)
            }
        }
    }

    private companion object {
        val HIGH_VOLATILITY = listOf("4.00" to "12.00", "6.00" to "15.00", "9.00" to "20.00")
    }
}
