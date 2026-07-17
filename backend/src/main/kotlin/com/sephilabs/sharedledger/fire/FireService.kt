package com.sephilabs.sharedledger.fire

import com.sephilabs.sharedledger.analytics.AnalyticsService
import com.sephilabs.sharedledger.analytics.CostOfLivingResponse
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.networth.movement.MovementRepository
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.networth.movement.NetWorthMovement
import com.sephilabs.sharedledger.networth.snapshot.Snapshot
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.SplittableRandom
import java.util.UUID
import kotlin.math.pow
import kotlin.math.sqrt

@Service
class FireService(
    private val settings: FireSettingsRepository,
    private val taxBrackets: FireTaxBracketRepository,
    private val snapshots: SnapshotRepository,
    private val movements: MovementRepository,
    private val transactions: TransactionRepository,
    private val analytics: AnalyticsService,
    private val props: AppProperties,
) {

    private val rng: SplittableRandom = SplittableRandom()

    @Transactional
    fun getOrCreate(householdId: UUID): FireSettings =
        settings.findById(householdId).orElseGet {
            settings.save(FireSettings(householdId = householdId))
        }

    @Transactional(readOnly = true)
    fun settingsDto(householdId: UUID): FireSettingsDto =
        getOrCreate(householdId).toDto(effectiveBrackets(householdId))

    @Transactional
    fun update(householdId: UUID, request: FireSettingsRequest, by: User): FireSettingsDto {
        if (request.returnScenarios.isEmpty()) throw AppException.badRequest("FIRE_SCENARIOS_REQUIRED")
        val bounds = request.taxBrackets.map { it.lowerBound }
        if (request.taxBrackets.isEmpty() || bounds.zipWithNext().any { (a, b) -> a >= b }) {
            throw AppException.badRequest("FIRE_TAX_BRACKETS_INVALID")
        }
        val s = getOrCreate(householdId)
        s.targetAmount = Money.normalize(request.targetAmount)
        s.targetYear = request.targetYear
        s.monthlyContribution = Money.normalize(request.monthlyContribution)
        s.returnScenarios = request.returnScenarios.map {
            ReturnScenario(meanPercent = it.meanPercent, stdDevPercent = it.stdDevPercent, historical = it.historical)
        }
        s.qualifyingAssetClasses = request.qualifyingAssetClasses.toTypedArray()
        s.expectedInflationPct = request.expectedInflationPct
        s.safeWithdrawalRatePct = request.safeWithdrawalRatePct
        s.fatMultiplier = request.fatMultiplier
        s.contributionMode = request.contributionMode
        s.indexContribution = request.indexContribution
        s.essentialSpendingMode = request.essentialSpendingMode
        s.manualEssentialSpending = Money.normalize(request.manualEssentialSpending)
        s.totalSpendingMode = request.totalSpendingMode
        s.manualTotalSpending = Money.normalize(request.manualTotalSpending)
        s.tierLeanEnabled = request.tierLeanEnabled
        s.tierFireEnabled = request.tierFireEnabled
        s.tierFatEnabled = request.tierFatEnabled
        s.tierCustomEnabled = request.tierCustomEnabled
        s.applyCapitalGainsTax = request.applyCapitalGainsTax
        s.fallbackGainFractionPct = request.fallbackGainFractionPct
        s.updatedByUserId = by.id
        s.updatedAt = Instant.now()

        taxBrackets.hardDeleteAllByHouseholdId(householdId)
        taxBrackets.saveAll(request.taxBrackets.map {
            FireTaxBracket(FireTaxBracketId(householdId, Money.normalize(it.lowerBound)), it.ratePct)
        })
        return s.toDto(request.taxBrackets.map { TaxBracket(Money.normalize(it.lowerBound), it.ratePct) })
    }

    @Transactional(readOnly = true)
    fun project(householdId: UUID): FireProjectionResponse {
        val s = getOrCreate(householdId)
        val brackets = effectiveBrackets(householdId)
        val qualifying = s.qualifyingAssetClasses.toSet()
        val today = LocalDate.now()

        val latest = snapshots.findLatest(householdId)
        val startingValue = qualifyingTotal(latest, qualifying)
        val startYear = latest?.snapshotDate?.year ?: today.year
        val years = (s.targetYear.toInt() - startYear).coerceAtLeast(0)
        val trials = props.fire.monteCarloTrials.coerceAtLeast(1)

        val inflation = s.expectedInflationPct.toDouble() / 100.0
        val swr = s.safeWithdrawalRatePct.toDouble() / 100.0

        // Spending bases — derived live (same definition as the Cost of living tile) unless
        // a manual override is active; the effective values feed everything downstream.
        val col = analytics.costOfLiving(householdId, YearMonth.now())
        val spending = resolveSpendingBasis(s, col)

        // Qualifying movements since inception, oldest first (repository returns newest first).
        val allMovements = movements.findInRange(householdId, FireDefaults.EARLIEST_DATA_DATE, today)
            .filter { it.type != MovementType.debt_payment && it.assetClassCode != null && it.assetClassCode in qualifying }
            .sortedBy { it.movementDate }

        val contributions = deriveContributions(householdId, s, allMovements)
        val monthlyContribution = (contributions.activeMonthly ?: BigDecimal.ZERO).toDouble()

        val orderedSnapshots = snapshots.findAllOrdered(householdId)
        val (actualReturn, unavailableReason) = computeActualReturn(orderedSnapshots, allMovements, qualifying)
        val historicalScenario =
            if (actualReturn != null) computeHistoricalScenario(orderedSnapshots, allMovements, qualifying) else null

        val gainFraction = deriveGainFraction(s, startingValue, allMovements)
        val gainFractionRatio = gainFraction.percent.toDouble() / 100.0

        val tiers = buildTiers(
            s = s,
            spending = spending,
            startingValue = startingValue,
            brackets = brackets,
            gainFractionRatio = gainFractionRatio,
            startYear = startYear,
            years = years,
            swr = swr,
            inflation = inflation,
        )

        val engine = SimulationEngine(
            simTiers = tiers.filter { it.enabled && it.targetToday != null },
            years = years,
            startYear = startYear,
            startValue = startingValue.toDouble(),
            costBasis0 = startingValue.toDouble() * (1.0 - gainFractionRatio),
            inflation = inflation,
            swr = swr,
            taxOn = s.applyCapitalGainsTax,
            compiledBrackets = CapitalGainsTax.compile(brackets),
            indexContribution = s.indexContribution,
        )

        val scenarios = s.returnScenarios.map { scenario ->
            engine.buildScenarioOutput(scenario, monthlyContribution, trials, rng)
        }

        return FireProjectionResponse(
            startYear = startYear,
            startingValue = Money.normalize(startingValue),
            snapshotDate = latest?.snapshotDate,
            settings = s.toDto(brackets),
            spending = spending,
            contributions = contributions,
            gainFraction = gainFraction,
            actualReturn = actualReturn,
            actualReturnUnavailableReason = unavailableReason,
            historicalScenario = historicalScenario,
            tiers = tiers,
            scenarios = scenarios,
            cumulativeContributions = historicalCumulativeContributions(allMovements),
            monteCarloTrials = trials,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Settings helpers

    private fun effectiveBrackets(householdId: UUID): List<TaxBracket> {
        val rows = taxBrackets.findAllForHousehold(householdId)
        if (rows.isEmpty()) return FireDefaults.SPANISH_SAVINGS_TAX_BRACKETS
        return rows.map { TaxBracket(it.id.lowerBound, it.ratePct) }
    }

    /** Single definition of the effective spending bases: manual override when active, live trailing-12 otherwise. */
    private fun resolveSpendingBasis(s: FireSettings, col: CostOfLivingResponse) = FireSpendingBasis(
        essentialMonthly = if (s.essentialSpendingMode == SpendingBaseMode.manual) {
            Money.normalize(s.manualEssentialSpending)
        } else {
            col.essentialMonthlyAverage
        },
        totalMonthly = if (s.totalSpendingMode == SpendingBaseMode.manual) {
            Money.normalize(s.manualTotalSpending)
        } else {
            col.totalMonthlyAverage
        },
        derivedEssentialMonthly = col.essentialMonthlyAverage,
        derivedTotalMonthly = col.totalMonthlyAverage,
        essentialMode = s.essentialSpendingMode,
        totalMode = s.totalSpendingMode,
        monthsAvailable = col.monthsAvailable,
    )

    private fun FireSettings.toDto(brackets: List<TaxBracket>) = FireSettingsDto(
        targetAmount = targetAmount,
        targetYear = targetYear,
        monthlyContribution = monthlyContribution,
        returnScenarios = returnScenarios.map { ReturnScenarioDto(it.meanPercent, it.stdDevPercent, it.historical) },
        qualifyingAssetClasses = qualifyingAssetClasses.toList(),
        expectedInflationPct = expectedInflationPct,
        safeWithdrawalRatePct = safeWithdrawalRatePct,
        fatMultiplier = fatMultiplier,
        contributionMode = contributionMode,
        indexContribution = indexContribution,
        essentialSpendingMode = essentialSpendingMode,
        manualEssentialSpending = manualEssentialSpending,
        totalSpendingMode = totalSpendingMode,
        manualTotalSpending = manualTotalSpending,
        tierLeanEnabled = tierLeanEnabled,
        tierFireEnabled = tierFireEnabled,
        tierFatEnabled = tierFatEnabled,
        tierCustomEnabled = tierCustomEnabled,
        applyCapitalGainsTax = applyCapitalGainsTax,
        fallbackGainFractionPct = fallbackGainFractionPct,
        taxBrackets = brackets.map { TaxBracketDto(it.lowerBound, it.ratePct) },
    )

    // ---------------------------------------------------------------------------------------
    // Derived inputs (never persisted — recomputed live from their sources)

    private fun deriveContributions(
        householdId: UUID,
        s: FireSettings,
        allMovements: List<NetWorthMovement>,
    ): FireContributions {
        val manual = Money.normalize(s.monthlyContribution)
        val savings = deriveSavingsMonthly(householdId)
        val fromMovements = deriveMovementsMonthly(allMovements)
        val active = when (s.contributionMode) {
            ContributionMode.manual -> manual
            ContributionMode.savings -> savings
            ContributionMode.movements -> fromMovements
        }
        return FireContributions(
            manualMonthly = manual,
            savingsMonthly = savings,
            movementsMonthly = fromMovements,
            mode = s.contributionMode,
            activeMonthly = active,
        )
    }

    /** Trailing-12 monthly average of (income − expenses), same month-count convention as cost of living. */
    private fun deriveSavingsMonthly(householdId: UUID): BigDecimal? {
        val asOf = YearMonth.now()
        val minDate = transactions.dateBounds(householdId).minDate ?: return null
        val months = (ChronoUnit.MONTHS.between(YearMonth.from(minDate), asOf).toInt() + 1)
            .coerceIn(0, FireDefaults.TRAILING_WINDOW_MONTHS)
        if (months <= 0) return null
        val from = asOf.minusMonths((FireDefaults.TRAILING_WINDOW_MONTHS - 1).toLong()).atDay(1)
        val rows = transactions.aggregationRows(householdId, from, asOf.atEndOfMonth())
        var income = BigDecimal.ZERO
        var expense = BigDecimal.ZERO
        for (r in rows) {
            when (r.direction) {
                Direction.income -> income += r.amount
                Direction.expense -> expense += r.amount
            }
        }
        return Money.normalize((income - expense).divide(months.toBigDecimal(), Money.SCALE, RoundingMode.HALF_EVEN))
    }

    /** Trailing-12 monthly average of net contributions (contributions − withdrawals) into qualifying classes. */
    private fun deriveMovementsMonthly(allMovements: List<NetWorthMovement>): BigDecimal? {
        if (allMovements.isEmpty()) return null
        val asOf = YearMonth.now()
        val firstMonth = YearMonth.from(allMovements.first().movementDate)
        val months = (ChronoUnit.MONTHS.between(firstMonth, asOf).toInt() + 1)
            .coerceIn(1, FireDefaults.TRAILING_WINDOW_MONTHS)
        val from = asOf.minusMonths((FireDefaults.TRAILING_WINDOW_MONTHS - 1).toLong()).atDay(1)
        val net = allMovements.filter { it.movementDate >= from }.fold(BigDecimal.ZERO) { acc, m -> acc + m.signedAmount() }
        return Money.normalize(net.divide(months.toBigDecimal(), Money.SCALE, RoundingMode.HALF_EVEN))
    }

    /**
     * Share of a hypothetical withdrawal that would be realized gain, derived from real data:
     * `(qualifying_wealth − cumulative_net_contributions) / qualifying_wealth`. Falls back to
     * the household's manual estimate while movements are insufficient to derive it.
     */
    private fun deriveGainFraction(
        s: FireSettings,
        startingValue: BigDecimal,
        allMovements: List<NetWorthMovement>,
    ): FireGainFraction {
        if (allMovements.isEmpty() || startingValue.signum() <= 0) {
            return FireGainFraction(percent = Money.normalize(s.fallbackGainFractionPct), source = GAIN_FRACTION_SOURCE_MANUAL)
        }
        val netContributions = allMovements.fold(BigDecimal.ZERO) { acc, m -> acc + m.signedAmount() }
        val ratio = (startingValue - netContributions)
            .divide(startingValue, 6, RoundingMode.HALF_EVEN)
            .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        return FireGainFraction(
            percent = Money.normalize(ratio.multiply(BigDecimal(100))),
            source = GAIN_FRACTION_SOURCE_MOVEMENTS,
        )
    }

    private fun NetWorthMovement.signedAmount(): BigDecimal = when (type) {
        MovementType.contribution -> amount
        MovementType.withdrawal -> amount.negate()
        MovementType.debt_payment -> BigDecimal.ZERO
    }

    // ---------------------------------------------------------------------------------------
    // Real money-weighted return (XIRR) and the historical reference scenario

    /**
     * Money-weighted annualized return over the full snapshot range: the first snapshot's
     * qualifying value goes in, every recorded movement flows in or out, and the latest
     * snapshot's qualifying value comes back. Without movements there is no return figure at
     * all — net-worth growth is not a return.
     */
    private fun computeActualReturn(
        orderedSnapshots: List<Snapshot>,
        allMovements: List<NetWorthMovement>,
        qualifying: Set<String>,
    ): Pair<FireActualReturn?, FireActualReturnUnavailableReason?> {
        if (orderedSnapshots.size < 2) return null to FireActualReturnUnavailableReason.insufficient_snapshots
        val first = orderedSnapshots.first()
        val last = orderedSnapshots.last()
        if (!first.snapshotDate.isBefore(last.snapshotDate)) {
            return null to FireActualReturnUnavailableReason.insufficient_snapshots
        }
        val inRange = allMovements.filter { it.movementDate.isAfter(first.snapshotDate) && !it.movementDate.isAfter(last.snapshotDate) }
        if (inRange.isEmpty()) return null to FireActualReturnUnavailableReason.no_movements

        val rate = Xirr.rate(cashFlows(first, last, inRange, qualifying))
            ?: return null to FireActualReturnUnavailableReason.not_computable
        return FireActualReturn(
            annualizedPercent = toPercent(rate),
            fromDate = first.snapshotDate,
            toDate = last.snapshotDate,
            movementCount = inRange.size,
        ) to null
    }

    /**
     * Mean and standard deviation of per-calendar-year money-weighted returns. A statistically
     * thin sample by construction — the output is labeled "(historical — reference)" in the UI
     * and never becomes a default scenario.
     */
    private fun computeHistoricalScenario(
        orderedSnapshots: List<Snapshot>,
        allMovements: List<NetWorthMovement>,
        qualifying: Set<String>,
    ): FireHistoricalScenario? {
        if (orderedSnapshots.size < 2) return null
        val firstYear = orderedSnapshots.first().snapshotDate.year
        val lastYear = orderedSnapshots.last().snapshotDate.year
        val yearlyReturns = mutableListOf<Double>()
        for (year in firstYear..lastYear) {
            // Year y runs from the last snapshot at or before Jan 1 of y (or the first snapshot
            // inside y) to the last snapshot at or before Jan 1 of y+1.
            val startSnap = orderedSnapshots.lastOrNull { !it.snapshotDate.isAfter(LocalDate.of(year, 1, 1)) }
                ?: orderedSnapshots.firstOrNull { it.snapshotDate.year == year }
                ?: continue
            val endSnap = orderedSnapshots.lastOrNull { !it.snapshotDate.isAfter(LocalDate.of(year + 1, 1, 1)) } ?: continue
            if (!startSnap.snapshotDate.isBefore(endSnap.snapshotDate)) continue
            if (ChronoUnit.DAYS.between(startSnap.snapshotDate, endSnap.snapshotDate) < FireDefaults.MIN_HISTORICAL_PERIOD_DAYS) continue
            val inRange = allMovements.filter {
                it.movementDate.isAfter(startSnap.snapshotDate) && !it.movementDate.isAfter(endSnap.snapshotDate)
            }
            val rate = Xirr.rate(cashFlows(startSnap, endSnap, inRange, qualifying)) ?: continue
            yearlyReturns += rate
        }
        if (yearlyReturns.isEmpty()) return null
        val mean = yearlyReturns.average()
        val stdDev = if (yearlyReturns.size > 1) {
            sqrt(yearlyReturns.sumOf { (it - mean) * (it - mean) } / (yearlyReturns.size - 1))
        } else 0.0
        return FireHistoricalScenario(
            meanPercent = toPercent(mean),
            stdDevPercent = toPercent(stdDev),
            yearsOfData = yearlyReturns.size,
        )
    }

    private fun cashFlows(
        startSnap: Snapshot,
        endSnap: Snapshot,
        movementsInRange: List<NetWorthMovement>,
        qualifying: Set<String>,
    ): List<Xirr.CashFlow> = buildList {
        add(Xirr.CashFlow(startSnap.snapshotDate, -qualifyingTotal(startSnap, qualifying).toDouble()))
        movementsInRange.forEach { add(Xirr.CashFlow(it.movementDate, -it.signedAmount().toDouble())) }
        add(Xirr.CashFlow(endSnap.snapshotDate, qualifyingTotal(endSnap, qualifying).toDouble()))
    }

    private fun qualifyingTotal(snapshot: Snapshot?, qualifying: Set<String>): BigDecimal =
        snapshot?.assetValues?.filter { it.id.assetClassCode in qualifying }
            ?.fold(BigDecimal.ZERO) { acc, v -> acc + v.value } ?: BigDecimal.ZERO

    private fun toPercent(ratio: Double): BigDecimal =
        BigDecimal(ratio * 100.0).setScale(2, RoundingMode.HALF_EVEN)

    private fun historicalCumulativeContributions(allMovements: List<NetWorthMovement>): List<FireHistoricalContribution> {
        var cumulative = BigDecimal.ZERO
        return allMovements.groupBy { it.movementDate.year }.toSortedMap().map { (year, list) ->
            cumulative += list.fold(BigDecimal.ZERO) { acc, m -> acc + m.signedAmount() }
            FireHistoricalContribution(year, Money.normalize(cumulative))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Tiers

    private fun buildTiers(
        s: FireSettings,
        spending: FireSpendingBasis,
        startingValue: BigDecimal,
        brackets: List<TaxBracket>,
        gainFractionRatio: Double,
        startYear: Int,
        years: Int,
        swr: Double,
        inflation: Double,
    ): List<FireTierOutput> {
        val compiled = CapitalGainsTax.compile(brackets)
        val taxOn = s.applyCapitalGainsTax
        val startingValueD = startingValue.toDouble()

        fun spendingTier(key: FireTierKey, enabled: Boolean, monthlyNet: BigDecimal, mode: SpendingBaseMode): FireTierOutput {
            val annualNet = monthlyNet.multiply(BigDecimal(FireDefaults.MONTHS_PER_YEAR))
            // A derived base additionally needs transaction history; a manual base stands on its own.
            val missingDerivedData = mode == SpendingBaseMode.derived && spending.monthsAvailable <= 0
            if (missingDerivedData || annualNet.signum() <= 0) {
                return FireTierOutput(key, enabled, null, null, null, null, null, null, emptyList())
            }
            val annualNetD = annualNet.toDouble()
            val annualGrossD = if (taxOn) compiled.grossUp(annualNetD, gainFractionRatio) else annualNetD
            val targetTodayD = annualGrossD / swr
            val curve = (0..years).map { y ->
                val netY = annualNetD * (1.0 + inflation).pow(y)
                val grossY = if (taxOn) compiled.grossUp(netY, gainFractionRatio) else netY
                FireScenarioYear(startYear + y, Money.normalize(BigDecimal(grossY / swr)))
            }
            return FireTierOutput(
                key = key,
                enabled = enabled,
                monthlyNetSpending = Money.normalize(monthlyNet),
                annualNetSpending = Money.normalize(annualNet),
                annualGrossSpending = Money.normalize(BigDecimal(annualGrossD)),
                estimatedAnnualTax = Money.normalize(BigDecimal(annualGrossD - annualNetD)),
                targetToday = Money.normalize(BigDecimal(targetTodayD)),
                coveragePercent = if (targetTodayD > 0.0) startingValueD / targetTodayD * 100.0 else null,
                targetCurve = curve,
            )
        }

        val custom = run {
            val target = Money.normalize(s.targetAmount)
            if (target.signum() <= 0) {
                FireTierOutput(FireTierKey.custom, s.tierCustomEnabled, null, null, null, null, null, null, emptyList())
            } else {
                // The custom target is the user's own nominal figure: it neither inflates nor grosses up.
                FireTierOutput(
                    key = FireTierKey.custom,
                    enabled = s.tierCustomEnabled,
                    monthlyNetSpending = null,
                    annualNetSpending = null,
                    annualGrossSpending = null,
                    estimatedAnnualTax = null,
                    targetToday = target,
                    coveragePercent = if (target.signum() > 0) startingValueD / target.toDouble() * 100.0 else null,
                    targetCurve = (0..years).map { y -> FireScenarioYear(startYear + y, target) },
                )
            }
        }

        return listOf(
            spendingTier(FireTierKey.lean, s.tierLeanEnabled, spending.essentialMonthly, spending.essentialMode),
            spendingTier(FireTierKey.fire, s.tierFireEnabled, spending.totalMonthly, spending.totalMode),
            spendingTier(FireTierKey.fat, s.tierFatEnabled, spending.totalMonthly.multiply(s.fatMultiplier), spending.totalMode),
            custom,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Simulation engine (deterministic path + Monte Carlo + Coast FIRE in one place)

    /**
     * Runs yearly paths `value_{i} = (value_{i-1} + contribution_i) × (1 + r_i)` and checks each
     * enabled tier against its inflated target. With capital-gains tax on, each path knows its
     * own cost basis, so the gain fraction — and with it the gross target — is per path and per
     * year, exactly as the withdrawal would be taxed.
     */
    private class SimulationEngine(
        simTiers: List<FireTierOutput>,
        private val years: Int,
        private val startYear: Int,
        private val startValue: Double,
        private val costBasis0: Double,
        private val inflation: Double,
        private val swr: Double,
        taxOn: Boolean,
        private val compiledBrackets: CapitalGainsTax.Compiled,
        private val indexContribution: Boolean,
    ) {

        private val tierKeys: List<FireTierKey> = simTiers.map { it.key }

        /** Net annual spending inflated per year; NaN row marks a flat (custom) target. */
        private val netAnnualByYear: Array<DoubleArray> = Array(simTiers.size) { t ->
            val annualNet = simTiers[t].annualNetSpending?.toDouble()
            if (annualNet == null) {
                DoubleArray(years + 1) { Double.NaN }
            } else {
                DoubleArray(years + 1) { y -> annualNet * (1.0 + inflation).pow(y) }
            }
        }
        private val flatTargets: DoubleArray

        /** With tax off targets don't depend on the path — precomputed once. */
        private val fixedTargets: Array<DoubleArray>?

        init {
            flatTargets = DoubleArray(simTiers.size) { t ->
                if (simTiers[t].annualNetSpending == null) simTiers[t].targetToday!!.toDouble() else Double.NaN
            }
            fixedTargets = if (taxOn) null else Array(simTiers.size) { t ->
                DoubleArray(years + 1) { y ->
                    if (!flatTargets[t].isNaN()) flatTargets[t] else netAnnualByYear[t][y] / swr
                }
            }
        }

        private fun targetFor(tier: Int, yearIdx: Int, gainFraction: Double): Double {
            if (!flatTargets[tier].isNaN()) return flatTargets[tier]
            fixedTargets?.let { return it[tier][yearIdx] }
            return compiledBrackets.grossUp(netAnnualByYear[tier][yearIdx], gainFraction) / swr
        }

        /** Contribution paid in during simulated year i (1-based), optionally indexed to inflation. */
        private fun annualContribution(monthlyContribution: Double, yearIdx: Int): Double {
            val base = monthlyContribution * FireDefaults.MONTHS_PER_YEAR
            return if (indexContribution) base * (1.0 + inflation).pow(yearIdx - 1) else base
        }

        private class Path(val values: DoubleArray, val hitYears: IntArray)

        private fun simulatePath(monthlyContribution: Double, returnFor: (Int) -> Double): Path {
            val values = DoubleArray(years + 1)
            val hit = IntArray(tierKeys.size) { -1 }
            var value = startValue
            var costBasis = costBasis0
            for (i in 0..years) {
                if (i > 0) {
                    val contribution = annualContribution(monthlyContribution, i)
                    value = (value + contribution) * (1.0 + returnFor(i))
                    if (value < 0.0) value = 0.0
                    costBasis += contribution
                }
                values[i] = value
                val gainFraction = if (value > 0.0) ((value - costBasis) / value).coerceIn(0.0, 1.0) else 0.0
                for (t in tierKeys.indices) {
                    if (hit[t] == -1 && value >= targetFor(t, i, gainFraction)) hit[t] = startYear + i
                }
            }
            return Path(values, hit)
        }

        private class McStats(
            val percentiles: List<FireScenarioPercentiles>,
            val probability: DoubleArray,
            val medianHitYear: Array<Int?>,
        )

        private fun monteCarlo(
            scenario: ReturnScenario,
            monthlyContribution: Double,
            trials: Int,
            rng: SplittableRandom,
            collectPercentiles: Boolean,
        ): McStats {
            val mean = scenario.meanPercent.toDouble() / 100.0
            val stdDev = scenario.stdDevPercent.toDouble() / 100.0
            val clamp = FireDefaults.RETURN_CLAMP_STDDEVS
            val columns = if (collectPercentiles) Array(years + 1) { DoubleArray(trials) } else null
            val hitYears = Array(tierKeys.size) { IntArray(trials) }

            for (trial in 0 until trials) {
                val path = simulatePath(monthlyContribution) {
                    if (stdDev == 0.0) {
                        mean
                    } else {
                        (rng.nextGaussian() * stdDev + mean).coerceIn(mean - clamp * stdDev, mean + clamp * stdDev)
                    }
                }
                columns?.let { for (i in 0..years) it[i][trial] = path.values[i] }
                for (t in tierKeys.indices) hitYears[t][trial] = path.hitYears[t]
            }

            val percentiles = columns?.mapIndexed { i, col ->
                col.sort()
                FireScenarioPercentiles(
                    year = startYear + i,
                    p10 = percentile(col, 10.0),
                    p25 = percentile(col, 25.0),
                    p50 = percentile(col, 50.0),
                    p75 = percentile(col, 75.0),
                    p90 = percentile(col, 90.0),
                )
            } ?: emptyList()

            val probability = DoubleArray(tierKeys.size)
            val medianHitYear = Array<Int?>(tierKeys.size) { null }
            for (t in tierKeys.indices) {
                val reachers = hitYears[t].filter { it >= 0 }
                probability[t] = reachers.size.toDouble() / trials
                if (reachers.isNotEmpty()) {
                    val sorted = reachers.sorted()
                    medianHitYear[t] = sorted[sorted.size / 2]
                }
            }
            return McStats(percentiles, probability, medianHitYear)
        }

        fun buildScenarioOutput(
            scenario: ReturnScenario,
            monthlyContribution: Double,
            trials: Int,
            rng: SplittableRandom,
        ): FireScenarioOutput {
            val mean = scenario.meanPercent.toDouble() / 100.0
            val deterministic = simulatePath(monthlyContribution) { mean }
            val mc = monteCarlo(scenario, monthlyContribution, trials, rng, collectPercentiles = true)
            val coast = monteCarlo(scenario, 0.0, trials, rng, collectPercentiles = false)

            return FireScenarioOutput(
                meanPercent = scenario.meanPercent,
                stdDevPercent = scenario.stdDevPercent,
                historical = scenario.historical,
                series = deterministic.values.mapIndexed { i, v ->
                    FireScenarioYear(startYear + i, Money.normalize(BigDecimal(v)))
                },
                percentiles = mc.percentiles,
                tierStats = tierKeys.indices.map { t ->
                    FireTierScenarioStats(
                        tier = tierKeys[t],
                        deterministicHitYear = deterministic.hitYears[t].takeIf { it >= 0 },
                        probabilityOfReachingTarget = mc.probability[t],
                        medianYearReachingTarget = mc.medianHitYear[t],
                        coastProbabilityOfReachingTarget = coast.probability[t],
                        coastMedianYearReachingTarget = coast.medianHitYear[t],
                    )
                },
            )
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
    }

    companion object {
        const val GAIN_FRACTION_SOURCE_MOVEMENTS = "movements"
        const val GAIN_FRACTION_SOURCE_MANUAL = "manual"
    }
}
