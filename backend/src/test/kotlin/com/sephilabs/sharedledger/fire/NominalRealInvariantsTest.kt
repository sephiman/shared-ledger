package com.sephilabs.sharedledger.fire

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.snapshot.Snapshot
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValue
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValueId
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

class NominalRealInvariantsTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val snapshots: SnapshotRepository,
    private val fireService: FireService,
) : IntegrationTestBase() {

    private fun setup(startingValue: String = "100000.00"): Pair<Household, User> {
        val user = users.save(User(email = "inv-${System.nanoTime()}@test.local", passwordHash = "x"))
        val household = households.save(Household(name = "InvHouse", currency = "EUR"))
        val snap = snapshots.save(
            Snapshot(
                householdId = household.id,
                snapshotDate = LocalDate.of(2026, 1, 1),
                createdByUserId = user.id,
                updatedByUserId = user.id,
            ),
        )
        snap.assetValues.add(
            SnapshotAssetValue(
                SnapshotAssetValueId(snap.id, "stocks"),
                BigDecimal(startingValue),
            ),
        )
        snapshots.save(snap)
        return household to user
    }

    private fun runProjection(
        meanPct: String,
        inflationPct: String,
        stdDevPct: String = "0.00",
        taxOn: Boolean = false,
        indexBrackets: Boolean = true,
        indexContribution: Boolean = true,
        targetYear: Short = 2046,
        drawdownYears: Short = 30,
        customTarget: String = "0.00",
        customEnabled: Boolean = false,
        monthlyContribution: String = "1500.00",
        manualTotalSpending: String = "2000.00",
        events: List<FireCashFlowEventInput> = emptyList(),
    ): FireProjectionResponse {
        val (household, user) = setup()
        fireService.update(
            household.id,
            FireSettingsRequest(
                targetAmount = BigDecimal(customTarget),
                targetYear = targetYear,
                monthlyContribution = BigDecimal(monthlyContribution),
                returnScenarios = listOf(ReturnScenarioInput(BigDecimal(meanPct), BigDecimal(stdDevPct), false, "Base")),
                qualifyingAssetClasses = listOf("stocks"),
                expectedInflationPct = BigDecimal(inflationPct),
                safeWithdrawalRatePct = BigDecimal("4.00"),
                fatMultiplier = BigDecimal("1.50"),
                contributionMode = ContributionMode.manual,
                indexContribution = indexContribution,
                essentialSpendingMode = SpendingBaseMode.manual,
                manualEssentialSpending = BigDecimal("1200.00"),
                totalSpendingMode = SpendingBaseMode.manual,
                manualTotalSpending = BigDecimal(manualTotalSpending),
                tierLeanEnabled = true,
                tierFireEnabled = true,
                tierFatEnabled = true,
                tierCustomEnabled = customEnabled,
                applyCapitalGainsTax = taxOn,
                indexTaxBrackets = indexBrackets,
                drawdownYears = drawdownYears,
                fallbackGainFractionPct = BigDecimal("50.00"),
                taxBrackets = FireDefaults.SPANISH_SAVINGS_TAX_BRACKETS.map { TaxBracketInput(it.lowerBound, it.ratePct) },
                cashFlowEvents = events,
                referenceTier = FireTierKey.fire,
                referenceScenarioIndex = 0,
            ),
            user,
        )
        return fireService.project(household.id)
    }

    private fun realRatePct(nominalPct: Double, inflationPct: Double): String {
        val r = nominalPct / 100.0
        val i = inflationPct / 100.0
        val rr = ((1.0 + r) / (1.0 + i) - 1.0) * 100.0
        return "%.8f".format(Locale.ROOT, rr)
    }

    private fun assertRelClose(actual: Double, expected: Double, tol: Double = 1e-3, label: String) {
        val denom = maxOf(1.0, abs(expected))
        val rel = abs(actual - expected) / denom
        assertThat(rel).describedAs("$label (actual=$actual, expected=$expected)").isLessThanOrEqualTo(tol)
    }

    @Test
    fun `invariant (a) zero inflation makes nominal and buying-power frames identical everywhere`() {
        val p = runProjection(meanPct = "6.00", inflationPct = "0.00", taxOn = false, customTarget = "750000.00", customEnabled = true)
        val sc = p.scenarios.single()
        for (tier in p.tiers.filter { it.enabled }) {
            val firstTarget = tier.targetCurve.first().value.toDouble()
            val lastTarget = tier.targetCurve.last().value.toDouble()
            assertThat(lastTarget).isCloseTo(firstTarget, Offset.offset(0.01))
            val stat = sc.tierStats.first { it.tier == tier.key }
            for (k in stat.drawdownPercentiles.indices) {
                assertThat(stat.drawdownRealPercentiles[k].p50)
                    .isEqualByComparingTo(stat.drawdownPercentiles[k].p50)
            }
        }
    }

    @Test
    fun `invariant (b) buying-power view of (r, i) equals (r_prime, 0) across all paths, tiers, events, and drawdown when tax is off`() {
        val iPct = 2.5
        val rPct = 7.0
        val rPrimePct = realRatePct(rPct, iPct)
        val events = listOf(
            FireCashFlowEventInput(2031, CashFlowEventKind.lump_sum, BigDecimal("50000.00"), "Bonus"),
            FireCashFlowEventInput(2033, CashFlowEventKind.contribution_delta, BigDecimal("300.00"), "Raise"),
            FireCashFlowEventInput(2035, CashFlowEventKind.spending_delta, BigDecimal("-200.00"), "Mortgage"),
        )
        val a = runProjection(
            meanPct = "%.2f".format(Locale.ROOT, rPct),
            inflationPct = "%.2f".format(Locale.ROOT, iPct),
            taxOn = false,
            customTarget = "650000.00",
            customEnabled = true,
            events = events,
        )
        val b = runProjection(
            meanPct = rPrimePct,
            inflationPct = "0.00",
            taxOn = false,
            customTarget = "650000.00",
            customEnabled = true,
            events = events,
        )

        val infl = iPct / 100.0
        val scA = a.scenarios.single()
        val scB = b.scenarios.single()

        // Wealth path deflated by (1+i)^y equals b's wealth path
        for (idx in scA.series.indices) {
            val deflated = scA.series[idx].value.toDouble() / (1.0 + infl).pow(idx)
            assertRelClose(deflated, scB.series[idx].value.toDouble(), 1e-3, "wealth series idx=$idx")
        }

        // Target curves deflated by (1+i)^y equal b's target curves
        for (tierKey in listOf(FireTierKey.lean, FireTierKey.fire, FireTierKey.fat, FireTierKey.custom)) {
            val tA = a.tiers.first { it.key == tierKey }
            val tB = b.tiers.first { it.key == tierKey }
            for (idx in tA.targetCurve.indices) {
                val deflated = tA.targetCurve[idx].value.toDouble() / (1.0 + infl).pow(idx)
                assertRelClose(deflated, tB.targetCurve[idx].value.toDouble(), 1e-3, "targetCurve $tierKey idx=$idx")
            }

            val stA = scA.tierStats.first { it.tier == tierKey }
            val stB = scB.tierStats.first { it.tier == tierKey }
            assertThat(stA.deterministicHitYear).describedAs("detHit $tierKey").isEqualTo(stB.deterministicHitYear)
            assertThat(stA.medianYearReachingTarget).describedAs("medHit $tierKey").isEqualTo(stB.medianYearReachingTarget)
            assertThat(stA.coastMedianYearReachingTarget).describedAs("coastMedHit $tierKey").isEqualTo(stB.coastMedianYearReachingTarget)
            assertRelClose(stA.coastTargetToday!!.toDouble(), stB.coastTargetToday!!.toDouble(), 1e-3, "coastTarget $tierKey")
            assertRelClose(stA.requiredMonthlyToHitTarget!!.toDouble(), stB.requiredMonthlyToHitTarget!!.toDouble(), 1e-3, "reqMonthly $tierKey")
            assertThat(stA.drawdownSurvivalRate).describedAs("survival $tierKey").isEqualTo(stB.drawdownSurvivalRate)

            // Drawdown buying-power percentiles computed per trial in backend equal b's drawdown
            assertThat(stA.drawdownRealPercentiles.size).isEqualTo(stB.drawdownPercentiles.size)
            for (k in stA.drawdownRealPercentiles.indices) {
                assertRelClose(
                    stA.drawdownRealPercentiles[k].p50.toDouble(),
                    stB.drawdownPercentiles[k].p50.toDouble(),
                    1e-3,
                    "drawdownReal $tierKey k=$k",
                )
            }
        }
    }

    @Test
    fun `invariant (b) with tax on has documented bounded deviation due to nominal-gain taxation`() {
        // When capital-gains tax is on, tax is levied on nominal gains (1+r)^y - 1 rather than real gains (1+r')^y - 1,
        // so higher inflation increases the taxable gain fraction and slightly lowers buying-power wealth (< 3% over 20y at 2% inflation).
        val rPrimePct = realRatePct(6.0, 2.0)
        val a = runProjection(meanPct = "6.00", inflationPct = "2.00", taxOn = true, indexBrackets = true)
        val b = runProjection(meanPct = rPrimePct, inflationPct = "0.00", taxOn = true, indexBrackets = true)
        val scA = a.scenarios.single()
        val scB = b.scenarios.single()
        val fireA = scA.tierStats.first { it.tier == FireTierKey.fire }
        val fireB = scB.tierStats.first { it.tier == FireTierKey.fire }

        // Hit year under nominal-gain taxation is the same or at most 1 year later than zero-inflation real-return run.
        val hitA = fireA.deterministicHitYear!!
        val hitB = fireB.deterministicHitYear!!
        assertThat(hitA).isBetween(hitB, hitB + 1)
        // Because nominal gains are taxed every year of the 30y drawdown, terminal buying-power wealth is lower
        // than the zero-inflation real-return run, bounded by the compounded nominal-gain tax drag (< 20% over 30y).
        val ddEndA = fireA.drawdownRealPercentiles.last().p50.toDouble()
        val ddEndB = fireB.drawdownPercentiles.last().p50.toDouble()
        assertThat(ddEndA).isLessThanOrEqualTo(ddEndB + 1.0)
        assertRelClose(ddEndA, ddEndB, 0.20, "tax-on 30y drawdown end within 20% compounded nominal-gain bound")
    }

    @Test
    fun `invariant (c) doubling inflation at constant real return leaves buying-power hit years and coverage unchanged`() {
        val realReturn = 0.04
        val r2 = ((1.0 + realReturn) * 1.02 - 1.0) * 100.0
        val r4 = ((1.0 + realReturn) * 1.04 - 1.0) * 100.0
        val p2 = runProjection(meanPct = "%.6f".format(Locale.ROOT, r2), inflationPct = "2.00", taxOn = false)
        val p4 = runProjection(meanPct = "%.6f".format(Locale.ROOT, r4), inflationPct = "4.00", taxOn = false)

        for (tierKey in listOf(FireTierKey.lean, FireTierKey.fire, FireTierKey.fat)) {
            val t2 = p2.tiers.first { it.key == tierKey }
            val t4 = p4.tiers.first { it.key == tierKey }
            assertThat(t2.coveragePercent!!).isCloseTo(t4.coveragePercent!!, Offset.offset(1e-6))

            val s2 = p2.scenarios.single().tierStats.first { it.tier == tierKey }
            val s4 = p4.scenarios.single().tierStats.first { it.tier == tierKey }
            assertThat(s2.deterministicHitYear).isEqualTo(s4.deterministicHitYear)
            assertThat(s2.medianYearReachingTarget).isEqualTo(s4.medianYearReachingTarget)
            assertRelClose(s2.requiredMonthlyToHitTarget!!.toDouble(), s4.requiredMonthlyToHitTarget!!.toDouble(), 1e-3, "reqMonthly $tierKey")
        }
    }

    @Test
    fun `invariant (d) indexed vs flat contribution differs solely by inflation compounding on contributions`() {
        val pIndexed = runProjection(meanPct = "0.00", inflationPct = "3.00", indexContribution = true, monthlyContribution = "1000.00")
        val pFlat = runProjection(meanPct = "0.00", inflationPct = "3.00", indexContribution = false, monthlyContribution = "1000.00")
        val sIdx = pIndexed.scenarios.single().series
        val sFlat = pFlat.scenarios.single().series
        val start = 100_000.0
        val annual = 12_000.0
        val expectedDiff = (0 until sIdx.size - 1).sumOf { k -> annual * ((1.03).pow(k) - 1.0) }
        val actualDiff = sIdx.last().value.toDouble() - sFlat.last().value.toDouble()
        assertThat(sFlat.last().value.toDouble()).isCloseTo(start + annual * (sIdx.size - 1), Offset.offset(0.05))
        assertThat(actualDiff).isCloseTo(expectedDiff, Offset.offset(1.0))
    }

    @Test
    fun `hit in the last horizon year with full drawdown length stays within bounds for spending and custom tiers`() {
        // Choose starting wealth and contributions so hit happens right at targetYear = 2031 (year 5), with 30y drawdown
        val p = runProjection(
            meanPct = "6.00",
            inflationPct = "2.00",
            targetYear = 2031,
            drawdownYears = 30,
            taxOn = true,
            indexBrackets = true,
            customTarget = "205000.00",
            customEnabled = true,
            monthlyContribution = "1500.00",
            manualTotalSpending = "680.00",
        )
        val sc = p.scenarios.single()
        val fireStat = sc.tierStats.first { it.tier == FireTierKey.fire }
        val customStat = sc.tierStats.first { it.tier == FireTierKey.custom }
        assertThat(fireStat.deterministicHitYear).isEqualTo(2031)
        assertThat(customStat.deterministicHitYear).isEqualTo(2031)
        assertThat(fireStat.drawdownPercentiles).hasSize(31)
        assertThat(fireStat.drawdownRealPercentiles).hasSize(31)
        assertThat(customStat.drawdownPercentiles).hasSize(31)
        assertThat(customStat.drawdownRealPercentiles).hasSize(31)
    }

    @Test
    fun `tier strip hit year is never earlier than the chart wealth path crossing its target curve`() {
        // Regression test for 669a369: flatTargets caused tier hit years to fire before p50/series crossed targetCurve
        val p = runProjection(
            meanPct = "6.00",
            stdDevPct = "10.00",
            inflationPct = "2.50",
            taxOn = true,
            indexBrackets = false,
            customTarget = "650000.00",
            customEnabled = true,
        )
        val sc = p.scenarios.single()
        for (tier in p.tiers.filter { it.enabled }) {
            val stat = sc.tierStats.first { it.tier == tier.key }
            val chartDetCross = sc.series.indices.firstOrNull { idx ->
                sc.series[idx].value >= tier.targetCurve[idx].value
            }?.let { p.startYear + it }

            if (stat.deterministicHitYear != null && chartDetCross != null) {
                assertThat(stat.deterministicHitYear)
                    .describedAs("detHitYear for ${tier.key} must not precede chart series crossing targetCurve")
                    .isGreaterThanOrEqualTo(chartDetCross)
            }
        }
    }
}
