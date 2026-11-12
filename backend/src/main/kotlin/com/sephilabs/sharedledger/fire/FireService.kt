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
import org.slf4j.LoggerFactory
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
import kotlin.math.exp
import kotlin.math.ln
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

    private val log = LoggerFactory.getLogger(javaClass)

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
        s.indexTaxBrackets = request.indexTaxBrackets
        s.drawdownYears = request.drawdownYears.coerceIn(1, 80)
        s.fallbackGainFractionPct = request.fallbackGainFractionPct
        s.cashFlowEvents = request.cashFlowEvents.map {
            FireCashFlowEvent(
                year = it.year,
                kind = it.kind,
                amount = Money.normalize(it.amount),
                label = it.label.trim(),
            )
        }
        s.updatedByUserId = by.id
        s.updatedAt = Instant.now()

        taxBrackets.hardDeleteAllByHouseholdId(householdId)
        taxBrackets.saveAll(request.taxBrackets.map {
            FireTaxBracket(FireTaxBracketId(householdId, Money.normalize(it.lowerBound)), it.ratePct)
        })
        val tiers = listOfNotNull(
            "lean".takeIf { s.tierLeanEnabled },
            "fire".takeIf { s.tierFireEnabled },
            "fat".takeIf { s.tierFatEnabled },
            "custom".takeIf { s.tierCustomEnabled },
        )
        log.info(
            "fire_settings_updated scenarios={} brackets={} tiers={} contribution_mode={} capital_gains_tax={}",
            request.returnScenarios.size, request.taxBrackets.size, tiers.joinToString(","), s.contributionMode,
            s.applyCapitalGainsTax,
        )
        return s.toDto(request.taxBrackets.map { TaxBracket(Money.normalize(it.lowerBound), it.ratePct) })
    }

    @Transactional(readOnly = true)
    fun project(householdId: UUID, preview: FirePreviewRequest? = null): FireProjectionResponse {
        val s = getOrCreate(householdId)
        val brackets = effectiveBrackets(householdId)
        val qualifying = s.qualifyingAssetClasses.toSet()
        val today = LocalDate.now()

        val effectiveTargetYear = preview?.targetYear ?: s.targetYear
        val effectiveSwrPct = preview?.safeWithdrawalRatePct ?: s.safeWithdrawalRatePct
        val effectiveMode = if (preview?.monthlyContribution != null) ContributionMode.manual else s.contributionMode
        val effectiveManualContrib = Money.normalize(preview?.monthlyContribution ?: s.monthlyContribution)
        val lumpSum = Money.normalize(preview?.lumpSum ?: BigDecimal.ZERO)

        val latest = snapshots.findLatest(householdId)
        val rawStartingValue = qualifyingTotal(latest, qualifying)
        val startingValue = rawStartingValue + lumpSum
        val startYear = latest?.snapshotDate?.year ?: today.year
        val years = (effectiveTargetYear.toInt() - startYear).coerceAtLeast(0)
        val trials = props.fire.monteCarloTrials.coerceAtLeast(1)

        val inflation = s.expectedInflationPct.toDouble() / 100.0
        val swr = effectiveSwrPct.toDouble() / 100.0

        // Spending bases — derived live (same definition as the Cost of living tile) unless
        // a manual override is active; the effective values feed everything downstream.
        val col = analytics.costOfLiving(householdId, YearMonth.now())
        val spending = resolveSpendingBasis(s, col)

        // Qualifying movements since inception, oldest first (repository returns newest first).
        val allMovements = movements.findInRange(householdId, FireDefaults.EARLIEST_DATA_DATE, today)
            .filter { it.type != MovementType.debt_payment && it.assetClassCode != null && it.assetClassCode in qualifying }
            .sortedBy { it.movementDate }

        val contributions = deriveContributions(householdId, effectiveManualContrib, effectiveMode, allMovements)
        val monthlyContribution = (contributions.activeMonthly ?: BigDecimal.ZERO).toDouble()

        val orderedSnapshots = snapshots.findAllOrdered(householdId)
        val uncoveredMonths = uncoveredMovementMonths(orderedSnapshots, allMovements)
        val (actualReturn, unavailableReason) = computeActualReturn(orderedSnapshots, allMovements, qualifying, uncoveredMonths)
        val historicalScenario =
            if (actualReturn != null) computeHistoricalScenario(orderedSnapshots, allMovements, qualifying) else null

        val baseGainFraction = deriveGainFraction(s, rawStartingValue, allMovements, uncoveredMonths)
        val gainFraction = if (lumpSum.signum() > 0 && startingValue.signum() > 0) {
            val baseGainAmount = rawStartingValue.multiply(baseGainFraction.percent).divide(BigDecimal(100), 6, RoundingMode.HALF_EVEN)
            val pct = baseGainAmount.divide(startingValue, 6, RoundingMode.HALF_EVEN).multiply(BigDecimal(100))
            baseGainFraction.copy(percent = Money.normalize(pct.coerceIn(BigDecimal.ZERO, BigDecimal(100))))
        } else {
            baseGainFraction
        }
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
            indexTaxBrackets = s.indexTaxBrackets,
            drawdownYears = s.drawdownYears.toInt().coerceIn(1, 80),
            cashFlowEvents = s.cashFlowEvents,
        )

        val scenarios = s.returnScenarios.map { scenario ->
            engine.buildScenarioOutput(scenario, monthlyContribution, trials, rng)
        }

        val effectiveSettings = s.toDto(brackets).copy(
            targetYear = effectiveTargetYear,
            safeWithdrawalRatePct = effectiveSwrPct,
            contributionMode = effectiveMode,
            monthlyContribution = effectiveManualContrib,
        )

        return FireProjectionResponse(
            startYear = startYear,
            startingValue = Money.normalize(startingValue),
            snapshotDate = latest?.snapshotDate,
            settings = effectiveSettings,
            spending = spending,
            contributions = contributions,
            gainFraction = gainFraction,
            actualReturn = actualReturn,
            actualReturnUnavailableReason = unavailableReason,
            historicalScenario = historicalScenario,
            suggestedScenario = suggestScenarioFromAssetMix(latest, qualifying),
            tiers = tiers,
            scenarios = scenarios,
            historicalWealth = historicalWealth(orderedSnapshots, qualifying, startYear),
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
        indexTaxBrackets = indexTaxBrackets,
        drawdownYears = drawdownYears,
        fallbackGainFractionPct = fallbackGainFractionPct,
        taxBrackets = brackets.map { TaxBracketDto(it.lowerBound, it.ratePct) },
        cashFlowEvents = cashFlowEvents.map { FireCashFlowEventDto(it.year, it.kind, it.amount, it.label) },
    )

    // ---------------------------------------------------------------------------------------
    // Derived inputs (never persisted — recomputed live from their sources)

    private fun deriveContributions(
        householdId: UUID,
        manualMonthly: BigDecimal,
        mode: ContributionMode,
        allMovements: List<NetWorthMovement>,
    ): FireContributions {
        val manual = Money.normalize(manualMonthly)
        val (savings, savingsRatePct) = deriveSavingsMonthly(householdId)
        val fromMovements = deriveMovementsMonthly(allMovements)
        val active = when (mode) {
            ContributionMode.manual -> manual
            ContributionMode.savings -> savings
            ContributionMode.movements -> fromMovements
        }
        return FireContributions(
            manualMonthly = manual,
            savingsMonthly = savings,
            savingsRatePct = savingsRatePct,
            movementsMonthly = fromMovements,
            mode = mode,
            activeMonthly = active,
        )
    }

    /** Trailing-12 monthly average of (income − expenses) and savings rate %, same month-count convention as cost of living. */
    private fun deriveSavingsMonthly(householdId: UUID): Pair<BigDecimal?, Double?> {
        val asOf = YearMonth.now()
        val minDate = transactions.dateBounds(householdId).minDate ?: return null to null
        val months = (ChronoUnit.MONTHS.between(YearMonth.from(minDate), asOf).toInt() + 1)
            .coerceIn(0, FireDefaults.TRAILING_WINDOW_MONTHS)
        if (months <= 0) return null to null
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
        val net = income - expense
        val monthly = Money.normalize(net.divide(months.toBigDecimal(), Money.SCALE, RoundingMode.HALF_EVEN))
        val ratePct = if (income.signum() > 0) (net.toDouble() / income.toDouble()) * 100.0 else null
        return monthly to ratePct
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

    /** Share of a hypothetical withdrawal that would be realized gain:
     *  `(qualifying_wealth − cumulative_net_contributions) / qualifying_wealth`. Falls back to the manual
     *  estimate while movements are insufficient to derive it. */
    private fun deriveGainFraction(
        s: FireSettings,
        startingValue: BigDecimal,
        allMovements: List<NetWorthMovement>,
        uncoveredMonths: Int,
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
            uncoveredMonths = uncoveredMonths,
        )
    }

    private fun uncoveredMovementMonths(
        orderedSnapshots: List<Snapshot>,
        allMovements: List<NetWorthMovement>,
    ): Int {
        val firstSnap = orderedSnapshots.firstOrNull()?.snapshotDate ?: return 0
        val firstMovement = allMovements.firstOrNull { it.movementDate.isAfter(firstSnap) }?.movementDate ?: return 0
        return if (firstMovement.isAfter(firstSnap.plusMonths(FireDefaults.MOVEMENT_COVERAGE_GAP_MONTHS))) {
            ChronoUnit.MONTHS.between(firstSnap, firstMovement).toInt()
        } else {
            0
        }
    }

    private fun NetWorthMovement.signedAmount(): BigDecimal = when (type) {
        MovementType.contribution -> amount
        MovementType.withdrawal -> amount.negate()
        MovementType.debt_payment -> BigDecimal.ZERO
    }

    // ---------------------------------------------------------------------------------------
    // Real money-weighted return (XIRR) and the historical reference scenario

    /** Money-weighted annualized return over the full snapshot range: first snapshot's qualifying value in,
     *  every movement in or out, latest qualifying value back. Without movements there is no return figure at
     *  all — net-worth growth is not a return. */
    private fun computeActualReturn(
        orderedSnapshots: List<Snapshot>,
        allMovements: List<NetWorthMovement>,
        qualifying: Set<String>,
        uncoveredMonths: Int,
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
        // inRange preserves allMovements' ascending order, so first() is the earliest covered movement.
        val firstMovementDate = inRange.first().movementDate
        return FireActualReturn(
            annualizedPercent = toPercent(rate),
            fromDate = first.snapshotDate,
            toDate = last.snapshotDate,
            movementCount = inRange.size,
            firstMovementDate = firstMovementDate,
            uncoveredMonths = uncoveredMonths,
        ) to null
    }

    /** Mean and stddev of per-calendar-year money-weighted returns. A thin sample by construction — labeled
     *  "(historical — reference)" in the UI and never a default scenario. */
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

    private fun suggestScenarioFromAssetMix(latest: Snapshot?, qualifying: Set<String>): ReturnScenarioDto? {
        val assets = latest?.assetValues
            ?.filter { it.id.assetClassCode in qualifying && it.value.signum() > 0 }
            ?: return null
        val total = assets.fold(BigDecimal.ZERO) { acc, v -> acc + v.value }.toDouble()
        if (total <= 0.0) return null
        var weightedMean = 0.0
        var weightedStd = 0.0
        for (asset in assets) {
            val w = asset.value.toDouble() / total
            val (m, sd) = ASSET_CLASS_ASSUMPTIONS[asset.id.assetClassCode] ?: DEFAULT_ASSET_ASSUMPTION
            weightedMean += w * m
            weightedStd += w * sd
        }
        return ReturnScenarioDto(
            meanPercent = BigDecimal(weightedMean).setScale(1, RoundingMode.HALF_EVEN),
            stdDevPercent = BigDecimal(weightedStd).setScale(1, RoundingMode.HALF_EVEN),
            historical = false,
        )
    }

    private fun qualifyingTotal(snapshot: Snapshot?, qualifying: Set<String>): BigDecimal =
        snapshot?.assetValues?.filter { it.id.assetClassCode in qualifying }
            ?.fold(BigDecimal.ZERO) { acc, v -> acc + v.value } ?: BigDecimal.ZERO

    private fun toPercent(ratio: Double): BigDecimal =
        BigDecimal(ratio * 100.0).setScale(2, RoundingMode.HALF_EVEN)

    private fun historicalWealth(
        orderedSnapshots: List<Snapshot>,
        qualifying: Set<String>,
        startYear: Int,
    ): List<FireScenarioYear> =
        orderedSnapshots
            .filter { it.snapshotDate.year <= startYear }
            .groupBy { it.snapshotDate.year }
            .toSortedMap()
            .map { (year, snaps) ->
                FireScenarioYear(year, Money.normalize(qualifyingTotal(snaps.last(), qualifying)))
            }

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
            val spendingDeltas = s.cashFlowEvents.filter { it.kind == CashFlowEventKind.spending_delta }
            val curve = (0..years).map { y ->
                val calYear = startYear + y
                val deltaAnnual = spendingDeltas.filter { it.year <= calYear }.sumOf { it.amount.toDouble() * 12.0 }
                val netY = ((annualNetD + deltaAnnual).coerceAtLeast(0.0)) * (1.0 + inflation).pow(y)
                val bracketScale = if (s.indexTaxBrackets) (1.0 + inflation).pow(y) else 1.0
                val grossY = if (taxOn) compiled.grossUp(netY, gainFractionRatio, bracketScale) else netY
                FireScenarioYear(calYear, Money.normalize(BigDecimal(grossY / swr)))
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

    /** Runs yearly paths `value_i = (value_{i-1} + contribution_i) × (1 + r_i)` against each tier's inflated
     *  target. With capital-gains tax on, each path tracks its own cost basis, so the gain fraction — and the
     *  gross target — is per path and per year, exactly as the withdrawal would be taxed. */
    private class SimulationEngine(
        simTiers: List<FireTierOutput>,
        private val years: Int,
        private val startYear: Int,
        private val startValue: Double,
        private val costBasis0: Double,
        private val inflation: Double,
        private val swr: Double,
        private val taxOn: Boolean,
        private val compiledBrackets: CapitalGainsTax.Compiled,
        private val indexContribution: Boolean,
        private val indexTaxBrackets: Boolean,
        private val drawdownYears: Int,
        cashFlowEvents: List<FireCashFlowEvent>,
    ) {

        private val tierKeys: List<FireTierKey> = simTiers.map { it.key }
        private val totalSpan: Int = years + drawdownYears

        private val bracketScales: DoubleArray = DoubleArray(totalSpan + 1) { y ->
            if (indexTaxBrackets) (1.0 + inflation).pow(y) else 1.0
        }

        private val lumpSumByYear: DoubleArray = DoubleArray(years + 1) { y ->
            val calYear = startYear + y
            cashFlowEvents.filter { it.kind == CashFlowEventKind.lump_sum && it.year == calYear }
                .sumOf { it.amount.toDouble() }
        }

        private val contribDeltaByYear: DoubleArray = DoubleArray(years + 1) { y ->
            val calYear = startYear + y
            val base = cashFlowEvents.filter { it.kind == CashFlowEventKind.contribution_delta && it.year <= calYear }
                .sumOf { it.amount.toDouble() * FireDefaults.MONTHS_PER_YEAR }
            if (y == 0) 0.0 else if (indexContribution) base * (1.0 + inflation).pow(y - 1) else base
        }

        /** Net annual spending inflated per year (including post-FIRE drawdown span); NaN row marks a flat (custom) target. */
        private val netAnnualByYear: Array<DoubleArray> = Array(simTiers.size) { t ->
            val annualNet = simTiers[t].annualNetSpending?.toDouble()
            if (annualNet == null) {
                DoubleArray(totalSpan + 1) { Double.NaN }
            } else {
                val spendingDeltas = cashFlowEvents.filter { it.kind == CashFlowEventKind.spending_delta }
                DoubleArray(totalSpan + 1) { y ->
                    val calYear = startYear + y
                    val delta = spendingDeltas.filter { it.year <= calYear }
                        .sumOf { it.amount.toDouble() * FireDefaults.MONTHS_PER_YEAR }
                    ((annualNet + delta).coerceAtLeast(0.0)) * (1.0 + inflation).pow(y)
                }
            }
        }
        private val flatTargets: DoubleArray
        private val horizonTargets: DoubleArray = DoubleArray(simTiers.size) { t ->
            simTiers[t].targetCurve.lastOrNull()?.value?.toDouble()
                ?: simTiers[t].targetToday?.toDouble()
                ?: Double.NaN
        }

        /** With tax off targets don't depend on the path — precomputed once. */
        private val fixedTargets: Array<DoubleArray>?

        init {
            flatTargets = DoubleArray(simTiers.size) { t ->
                simTiers[t].targetToday?.toDouble() ?: Double.NaN
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
            return compiledBrackets.grossUp(netAnnualByYear[tier][yearIdx], gainFraction, bracketScales[yearIdx]) / swr
        }

        /** Contribution paid in during simulated year i (1-based), optionally indexed to inflation, plus any active delta. */
        private fun annualContribution(monthlyContribution: Double, yearIdx: Int): Double {
            val base = monthlyContribution * FireDefaults.MONTHS_PER_YEAR
            val indexed = if (indexContribution) base * (1.0 + inflation).pow(yearIdx - 1) else base
            return (indexed + contribDeltaByYear[yearIdx]).coerceAtLeast(0.0)
        }

        private class Path(
            val values: DoubleArray,
            val hitYears: IntArray,
            val hitIdx: IntArray,
            val hitValues: DoubleArray,
            val hitCostBases: DoubleArray,
        )

        private fun simulatePath(monthlyContribution: Double, returnFor: (Int) -> Double): Path {
            val values = DoubleArray(years + 1)
            val hit = IntArray(tierKeys.size) { -1 }
            val hitIdx = IntArray(tierKeys.size) { -1 }
            val hitValues = DoubleArray(tierKeys.size)
            val hitCostBases = DoubleArray(tierKeys.size)
            var value = startValue
            var costBasis = costBasis0
            for (i in 0..years) {
                if (i > 0) {
                    val contribution = annualContribution(monthlyContribution, i) + lumpSumByYear[i]
                    value = (value + contribution) * (1.0 + returnFor(i))
                    if (value < 0.0) value = 0.0
                    if (contribution > 0.0) costBasis += contribution
                }
                values[i] = value
                val gainFraction = if (value > 0.0) ((value - costBasis) / value).coerceIn(0.0, 1.0) else 0.0
                for (t in tierKeys.indices) {
                    if (hit[t] == -1 && value >= targetFor(t, i, gainFraction)) {
                        hit[t] = startYear + i
                        hitIdx[t] = i
                        hitValues[t] = value
                        hitCostBases[t] = costBasis
                    }
                }
            }
            return Path(values, hit, hitIdx, hitValues, hitCostBases)
        }

        private fun simulateDrawdown(
            tierIdx: Int,
            startIdx: Int,
            hitValue: Double,
            hitCostBasis: Double,
            nextReturn: () -> Double,
            outPath: DoubleArray,
        ): Boolean {
            var value = hitValue
            var costBasis = hitCostBasis
            outPath[0] = value
            val customBaseNet = if (!flatTargets[tierIdx].isNaN()) flatTargets[tierIdx] * swr else Double.NaN
            var survived = true
            for (k in 1..drawdownYears) {
                if (value <= 0.0) {
                    survived = false
                    outPath[k] = 0.0
                    continue
                }
                val y = startIdx + k
                val netSpending = if (!customBaseNet.isNaN()) {
                    customBaseNet * (1.0 + inflation).pow(k)
                } else {
                    netAnnualByYear[tierIdx][y]
                }
                val gainFraction = ((value - costBasis) / value).coerceIn(0.0, 1.0)
                val gross = if (taxOn) {
                    compiledBrackets.grossUp(netSpending, gainFraction, bracketScales[y])
                } else {
                    netSpending
                }
                if (gross >= value) {
                    survived = false
                    value = 0.0
                    outPath[k] = 0.0
                    continue
                }
                costBasis *= ((value - gross) / value).coerceAtLeast(0.0)
                value = (value - gross) * (1.0 + nextReturn())
                if (value <= 0.0) {
                    survived = false
                    value = 0.0
                }
                outPath[k] = value
            }
            return survived
        }

        private class McStats(
            val percentiles: List<FireScenarioPercentiles>,
            val probability: DoubleArray,
            val medianHitYear: Array<Int?>,
            val drawdownSurvivalRate: Array<Double?>,
            val drawdownPercentiles: Array<List<FireScenarioPercentiles>>,
        )

        private fun logNormalSampler(mean: Double, stdDev: Double, rng: SplittableRandom): () -> Double {
            if (stdDev == 0.0 || 1.0 + mean <= 0.0) return { mean.coerceAtLeast(-0.9999) }
            val onePlusMean = 1.0 + mean
            val varianceRatio = (stdDev / onePlusMean) * (stdDev / onePlusMean)
            val sigmaLn = sqrt(ln(1.0 + varianceRatio))
            val muLn = ln(onePlusMean) - 0.5 * sigmaLn * sigmaLn
            val clamp = FireDefaults.RETURN_CLAMP_STDDEVS
            return {
                val z = rng.nextGaussian().coerceIn(-clamp, clamp)
                exp(muLn + sigmaLn * z) - 1.0
            }
        }

        private fun monteCarlo(
            scenario: ReturnScenario,
            monthlyContribution: Double,
            trials: Int,
            rng: SplittableRandom,
            collectPercentiles: Boolean,
        ): McStats {
            val mean = scenario.meanPercent.toDouble() / 100.0
            val stdDev = scenario.stdDevPercent.toDouble() / 100.0
            val sampleReturn = logNormalSampler(mean, stdDev, rng)
            val columns = if (collectPercentiles) Array(years + 1) { DoubleArray(trials) } else null
            val hitYears = Array(tierKeys.size) { IntArray(trials) }
            val survivedCounts = IntArray(tierKeys.size)
            val hitCounts = IntArray(tierKeys.size)
            val drawdownPaths = if (collectPercentiles) Array(tierKeys.size) { mutableListOf<DoubleArray>() } else null
            val scratchDrawdown = DoubleArray(drawdownYears + 1)

            for (trial in 0 until trials) {
                val path = simulatePath(monthlyContribution) { sampleReturn() }
                columns?.let { for (i in 0..years) it[i][trial] = path.values[i] }
                for (t in tierKeys.indices) {
                    hitYears[t][trial] = path.hitYears[t]
                    if (collectPercentiles && path.hitIdx[t] >= 0) {
                        hitCounts[t]++
                        if (simulateDrawdown(t, path.hitIdx[t], path.hitValues[t], path.hitCostBases[t], sampleReturn, scratchDrawdown)) {
                            survivedCounts[t]++
                        }
                        drawdownPaths!![t].add(scratchDrawdown.copyOf())
                    }
                }
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
            val survivalRate = Array<Double?>(tierKeys.size) { null }
            val drawdownPercentiles = Array<List<FireScenarioPercentiles>>(tierKeys.size) { emptyList() }
            for (t in tierKeys.indices) {
                val reachers = hitYears[t].filter { it >= 0 }
                probability[t] = reachers.size.toDouble() / trials
                if (reachers.isNotEmpty()) {
                    val sorted = reachers.sorted()
                    medianHitYear[t] = sorted[sorted.size / 2]
                }
                if (collectPercentiles && hitCounts[t] > 0) {
                    survivalRate[t] = survivedCounts[t].toDouble() / hitCounts[t]
                    val paths = drawdownPaths!![t]
                    drawdownPercentiles[t] = (0..drawdownYears).map { k ->
                        val col = DoubleArray(paths.size) { idx -> paths[idx][k] }
                        col.sort()
                        FireScenarioPercentiles(
                            year = k,
                            p10 = percentile(col, 10.0),
                            p25 = percentile(col, 25.0),
                            p50 = percentile(col, 50.0),
                            p75 = percentile(col, 75.0),
                            p90 = percentile(col, 90.0),
                        )
                    }
                }
            }
            return McStats(percentiles, probability, medianHitYear, survivalRate, drawdownPercentiles)
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

            val growthFactor = (1.0 + mean).pow(years)
            return FireScenarioOutput(
                meanPercent = scenario.meanPercent,
                stdDevPercent = scenario.stdDevPercent,
                historical = scenario.historical,
                series = deterministic.values.mapIndexed { i, v ->
                    FireScenarioYear(startYear + i, Money.normalize(BigDecimal(v)))
                },
                percentiles = mc.percentiles,
                tierStats = tierKeys.indices.map { t ->
                    val horizonTarget = horizonTargets[t]
                    val coastTarget = if (!horizonTarget.isNaN() && growthFactor > 0.0) {
                        Money.normalize(BigDecimal(horizonTarget / growthFactor))
                    } else {
                        null
                    }
                    FireTierScenarioStats(
                        tier = tierKeys[t],
                        deterministicHitYear = deterministic.hitYears[t].takeIf { it >= 0 },
                        probabilityOfReachingTarget = mc.probability[t],
                        medianYearReachingTarget = mc.medianHitYear[t],
                        coastProbabilityOfReachingTarget = coast.probability[t],
                        coastMedianYearReachingTarget = coast.medianHitYear[t],
                        coastTargetToday = coastTarget,
                        requiredMonthlyToHitTarget = requiredMonthlyFor(t, mean),
                        drawdownSurvivalRate = mc.drawdownSurvivalRate[t],
                        drawdownPercentiles = mc.drawdownPercentiles[t],
                    )
                },
            )
        }

        private fun requiredMonthlyFor(tierIdx: Int, mean: Double): BigDecimal? {
            if (simulatePath(0.0) { mean }.hitYears[tierIdx] != -1) return Money.normalize(BigDecimal.ZERO)
            if (years <= 0) return null
            var lo = 0.0
            var hi = 500.0
            while (hi < 10_000_000.0 && simulatePath(hi) { mean }.hitYears[tierIdx] == -1) {
                lo = hi
                hi *= 2.0
            }
            if (simulatePath(hi) { mean }.hitYears[tierIdx] == -1) return null
            repeat(40) {
                val mid = (lo + hi) / 2.0
                if (simulatePath(mid) { mean }.hitYears[tierIdx] != -1) hi = mid else lo = mid
            }
            return Money.normalize(BigDecimal(hi))
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
        private val DEFAULT_ASSET_ASSUMPTION = 6.0 to 12.0
        private val ASSET_CLASS_ASSUMPTIONS = mapOf(
            "stocks" to (8.0 to 16.0),
            "etfs" to (8.0 to 16.0),
            "fund" to (6.5 to 11.0),
            "pension" to (6.5 to 11.0),
            "crypto" to (12.0 to 45.0),
            "real_estate" to (5.5 to 9.0),
            "property" to (5.5 to 9.0),
            "bonds" to (3.5 to 5.0),
            "fixed_income" to (3.5 to 5.0),
            "cash" to (2.0 to 1.0),
            "savings" to (2.0 to 1.0),
        )
    }
}
