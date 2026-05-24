package com.sharedledger.fire

import com.sharedledger.common.Money
import com.sharedledger.config.AppProperties
import com.sharedledger.identity.user.User
import com.sharedledger.networth.movement.MovementRepository
import com.sharedledger.networth.movement.MovementType
import com.sharedledger.networth.snapshot.SnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.SplittableRandom
import java.util.UUID
import kotlin.math.pow

@Service
class FireService(
    private val settings: FireSettingsRepository,
    private val snapshots: SnapshotRepository,
    private val movements: MovementRepository,
    private val props: AppProperties,
) {

    private val rng: SplittableRandom = SplittableRandom()

    @Transactional
    fun getOrCreate(householdId: UUID): FireSettings =
        settings.findById(householdId).orElseGet {
            settings.save(FireSettings(householdId = householdId))
        }

    @Transactional
    fun update(householdId: UUID, request: FireSettingsRequest, by: User): FireSettings {
        val s = getOrCreate(householdId)
        s.targetAmount = Money.normalize(request.targetAmount)
        s.targetYear = request.targetYear
        s.monthlyContribution = Money.normalize(request.monthlyContribution)
        s.returnScenarios = request.returnScenarios.map {
            ReturnScenario(meanPercent = it.meanPercent, stdDevPercent = it.stdDevPercent)
        }
        s.qualifyingAssetClasses = request.qualifyingAssetClasses.toTypedArray()
        s.updatedByUserId = by.id
        s.updatedAt = Instant.now()
        return s
    }

    @Transactional(readOnly = true)
    fun project(householdId: UUID): FireProjectionResponse {
        val s = getOrCreate(householdId)
        val latest = snapshots.findLatest(householdId)
        val qualifying = s.qualifyingAssetClasses.toSet()
        val startingValue = latest?.assetValues?.filter { it.id.assetClassCode in qualifying }
            ?.fold(BigDecimal.ZERO) { acc, v -> acc + v.value } ?: BigDecimal.ZERO
        val startYear = latest?.snapshotDate?.year ?: LocalDate.now().year
        val targetYear = s.targetYear.toInt()
        val years = (targetYear - startYear).coerceAtLeast(0)
        val annualContribution = s.monthlyContribution.multiply(BigDecimal.valueOf(12))
        val trials = props.fire.monteCarloTrials.coerceAtLeast(1)

        val scenarios = s.returnScenarios.map { scenario ->
            buildScenarioOutput(scenario, startingValue, annualContribution, startYear, years, s.targetAmount, trials)
        }

        val actualReturn = computeAnnualizedReturn(householdId, qualifying)
        val historicalContributions = computeHistoricalCumulativeContributions(householdId, qualifying)

        return FireProjectionResponse(
            startYear = startYear,
            startingValue = Money.normalize(startingValue),
            settings = s.toDto(),
            scenarios = scenarios,
            actualAnnualizedReturnPercent = actualReturn,
            cumulativeContributions = historicalContributions,
            monteCarloTrials = trials,
        )
    }

    private fun buildScenarioOutput(
        scenario: ReturnScenario,
        startingValue: BigDecimal,
        annualContribution: BigDecimal,
        startYear: Int,
        years: Int,
        targetAmount: BigDecimal,
        trials: Int,
    ): FireScenarioOutput {
        val deterministic = runDeterministic(scenario.meanPercent, startingValue, annualContribution, startYear, years, targetAmount)
        val mc = runMonteCarlo(
            scenario = scenario,
            startingValue = startingValue.toDouble(),
            annualContribution = annualContribution.toDouble(),
            startYear = startYear,
            years = years,
            targetAmount = targetAmount.toDouble(),
            trials = trials,
        )
        return FireScenarioOutput(
            meanPercent = scenario.meanPercent,
            stdDevPercent = scenario.stdDevPercent,
            series = deterministic.series,
            targetHitYear = deterministic.targetHitYear,
            percentiles = mc.percentiles,
            probabilityOfReachingTarget = mc.probabilityOfReachingTarget,
            medianYearReachingTarget = mc.medianYearReachingTarget,
        )
    }

    private data class DeterministicResult(val series: List<FireScenarioYear>, val targetHitYear: Int?)

    private fun runDeterministic(
        meanPercent: BigDecimal,
        startingValue: BigDecimal,
        annualContribution: BigDecimal,
        startYear: Int,
        years: Int,
        targetAmount: BigDecimal,
    ): DeterministicResult {
        val r = meanPercent.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_EVEN)
        var value = startingValue
        var targetHit: Int? = if (value >= targetAmount && targetAmount.signum() > 0) startYear else null
        val series = mutableListOf(FireScenarioYear(startYear, Money.normalize(value)))
        for (i in 1..years) {
            value = (value + annualContribution).multiply(BigDecimal.ONE + r, MathContext.DECIMAL64)
            val year = startYear + i
            series += FireScenarioYear(year, Money.normalize(value))
            if (targetHit == null && value >= targetAmount && targetAmount.signum() > 0) targetHit = year
        }
        return DeterministicResult(series, targetHit)
    }

    private data class MonteCarloResult(
        val percentiles: List<FireScenarioPercentiles>,
        val probabilityOfReachingTarget: Double,
        val medianYearReachingTarget: Int?,
    )

    private fun runMonteCarlo(
        scenario: ReturnScenario,
        startingValue: Double,
        annualContribution: Double,
        startYear: Int,
        years: Int,
        targetAmount: Double,
        trials: Int,
    ): MonteCarloResult {
        val mean = scenario.meanPercent.toDouble() / 100.0
        val stdDev = scenario.stdDevPercent.toDouble() / 100.0
        val targetIsLive = targetAmount > 0.0
        val columns = Array(years + 1) { DoubleArray(trials) }
        val hitYearByTrial = IntArray(trials) { -1 }

        for (t in 0 until trials) {
            var value = startingValue
            columns[0][t] = value
            if (targetIsLive && value >= targetAmount) hitYearByTrial[t] = startYear
            for (i in 1..years) {
                val r = if (stdDev == 0.0) {
                    mean
                } else {
                    val sample = rng.nextGaussian() * stdDev + mean
                    sample.coerceIn(mean - 3.0 * stdDev, mean + 3.0 * stdDev)
                }
                value = (value + annualContribution) * (1.0 + r)
                if (value < 0.0) value = 0.0
                columns[i][t] = value
                if (targetIsLive && hitYearByTrial[t] == -1 && value >= targetAmount) {
                    hitYearByTrial[t] = startYear + i
                }
            }
        }

        val percentiles = columns.mapIndexed { i, col ->
            col.sort()
            FireScenarioPercentiles(
                year = startYear + i,
                p10 = percentile(col, 10.0),
                p25 = percentile(col, 25.0),
                p50 = percentile(col, 50.0),
                p75 = percentile(col, 75.0),
                p90 = percentile(col, 90.0),
            )
        }

        val reachers = hitYearByTrial.filter { it >= 0 }
        val probabilityOfReachingTarget = if (targetIsLive) reachers.size.toDouble() / trials else 0.0
        val medianYear = if (!targetIsLive || reachers.isEmpty()) null else {
            val sortedHits = reachers.sorted()
            sortedHits[sortedHits.size / 2]
        }

        return MonteCarloResult(percentiles, probabilityOfReachingTarget, medianYear)
    }

    private fun percentile(sortedAsc: DoubleArray, p: Double): BigDecimal {
        if (sortedAsc.isEmpty()) return BigDecimal.ZERO
        val n = sortedAsc.size
        val rank = (p / 100.0) * (n - 1)
        val low = rank.toInt()
        val high = (low + 1).coerceAtMost(n - 1)
        val frac = rank - low
        val v = sortedAsc[low] * (1.0 - frac) + sortedAsc[high] * frac
        return BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN)
    }

    private fun computeAnnualizedReturn(householdId: UUID, qualifying: Set<String>): BigDecimal? {
        val all = snapshots.findAllOrdered(householdId)
        if (all.size < 12) return null
        val first = all.first()
        val last = all.last()
        val firstTotal = first.assetValues.filter { it.id.assetClassCode in qualifying }
            .fold(BigDecimal.ZERO) { acc, v -> acc + v.value }
        val lastTotal = last.assetValues.filter { it.id.assetClassCode in qualifying }
            .fold(BigDecimal.ZERO) { acc, v -> acc + v.value }
        if (firstTotal.signum() <= 0) return null
        val years = java.time.temporal.ChronoUnit.DAYS.between(first.snapshotDate, last.snapshotDate).toDouble() / 365.25
        if (years <= 0) return null
        val ratio = lastTotal.toDouble() / firstTotal.toDouble()
        val annualized = ratio.pow(1.0 / years) - 1.0
        return BigDecimal(annualized * 100.0).setScale(2, RoundingMode.HALF_EVEN)
    }

    private fun computeHistoricalCumulativeContributions(householdId: UUID, qualifying: Set<String>): List<FireHistoricalContribution> {
        val start = LocalDate.of(2000, 1, 1)
        val end = LocalDate.now()
        val items = movements.findInRange(householdId, start, end)
            .filter { it.assetClassCode != null && it.assetClassCode in qualifying }
        val byYear = items.groupBy { it.movementDate.year }
            .toSortedMap()
        var cumulative = BigDecimal.ZERO
        return byYear.map { (year, list) ->
            val delta = list.fold(BigDecimal.ZERO) { acc, m ->
                acc + if (m.type == MovementType.contribution) m.amount else -m.amount
            }
            cumulative = cumulative + delta
            FireHistoricalContribution(year, Money.normalize(cumulative))
        }
    }

    fun FireSettings.toDto() = FireSettingsDto(
        targetAmount = targetAmount,
        targetYear = targetYear,
        monthlyContribution = monthlyContribution,
        returnScenarios = returnScenarios.map { ReturnScenarioDto(it.meanPercent, it.stdDevPercent) },
        qualifyingAssetClasses = qualifyingAssetClasses.toList(),
    )
}
