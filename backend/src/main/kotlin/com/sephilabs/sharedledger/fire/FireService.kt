package com.sephilabs.sharedledger.fire

import com.sephilabs.sharedledger.analytics.AnalyticsService
import com.sephilabs.sharedledger.analytics.CostOfLivingResponse
import com.sephilabs.sharedledger.analytics.MonthWindow
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.exp
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
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

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
        requireValidSettings(householdId, request)
        val s = getOrCreate(householdId)
        s.applyRequest(request)
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
        return s.toDto(requestBrackets(request))
    }

    /** The one check behind both Save and a preview of the unsaved form, so both refuse the same forms with the same codes. */
    private fun requireValidSettings(householdId: UUID, request: FireSettingsRequest) {
        if (request.returnScenarios.isEmpty()) throw AppException.badRequest("FIRE_SCENARIOS_REQUIRED")
        val bounds = request.taxBrackets.map { it.lowerBound }
        if (request.taxBrackets.isEmpty() || bounds.zipWithNext().any { (a, b) -> a >= b }) {
            throw AppException.badRequest("FIRE_TAX_BRACKETS_INVALID")
        }
        val startYear = snapshots.findLatest(householdId)?.snapshotDate?.year ?: LocalDate.now(clock).year
        requireSimulatedLumpSums(request.cashFlowEvents.map { it.toEvent() }, startYear)
    }

    private fun requestBrackets(request: FireSettingsRequest): List<TaxBracket> =
        request.taxBrackets.map { TaxBracket(Money.normalize(it.lowerBound), it.ratePct) }

    /** The saved settings, or a preview's unsaved form — never persisted, projected exactly as a save would be. */
    private fun settingsFor(householdId: UUID, preview: FirePreviewRequest?): FireSettings {
        val request = preview?.settings ?: return getOrCreate(householdId)
        requireValidSettings(householdId, request)
        return FireSettings(householdId = householdId).also { it.applyRequest(request) }
    }

    private fun FireSettings.applyRequest(request: FireSettingsRequest) {
        targetAmount = Money.normalize(request.targetAmount)
        targetYear = request.targetYear
        monthlyContribution = Money.normalize(request.monthlyContribution)
        returnScenarios = withoutDefaultNames(
            request.returnScenarios.map {
                ReturnScenario(
                    meanPercent = it.meanPercent,
                    stdDevPercent = it.stdDevPercent,
                    historical = it.historical,
                    name = it.name.trim(),
                )
            },
        )
        referenceTier = resolveReferenceTier(request.referenceTier, request)
        referenceScenarioIndex = resolveReferenceScenarioIndex(request.referenceScenarioIndex, returnScenarios).toShort()
        qualifyingAssetClasses = request.qualifyingAssetClasses.toTypedArray()
        expectedInflationPct = request.expectedInflationPct
        safeWithdrawalRatePct = request.safeWithdrawalRatePct
        fatMultiplier = request.fatMultiplier
        contributionMode = request.contributionMode
        indexContribution = request.indexContribution
        essentialSpendingMode = request.essentialSpendingMode
        manualEssentialSpending = Money.normalize(request.manualEssentialSpending)
        totalSpendingMode = request.totalSpendingMode
        manualTotalSpending = Money.normalize(request.manualTotalSpending)
        tierLeanEnabled = request.tierLeanEnabled
        tierFireEnabled = request.tierFireEnabled
        tierFatEnabled = request.tierFatEnabled
        tierCustomEnabled = request.tierCustomEnabled
        applyCapitalGainsTax = request.applyCapitalGainsTax
        indexTaxBrackets = request.indexTaxBrackets
        drawdownYears = request.drawdownYears.coerceIn(1, 80)
        spendingLookbackMonths = request.spendingLookbackMonths.toInt().coerceIn(1, 120).toShort()
        spendingAggregation = request.spendingAggregation
        excludeCurrentMonth = request.excludeCurrentMonth
        fallbackGainFractionPct = request.fallbackGainFractionPct
        cashFlowEvents = request.cashFlowEvents.map { it.toEvent() }
    }

    private fun FireCashFlowEventInput.toEvent() = FireCashFlowEvent(
        year = year,
        kind = kind,
        amount = Money.normalize(amount),
        label = label.trim(),
        template = template?.trim()?.takeIf { it.isNotEmpty() },
    )

    /** The engine starts adding one-off amounts the year after the snapshot year, so one dated earlier would be
     *  saved yet do nothing. */
    private fun requireSimulatedLumpSums(events: List<FireCashFlowEvent>, startYear: Int) {
        if (events.any { it.kind == CashFlowEventKind.lump_sum && it.year <= startYear }) {
            throw AppException.badRequest("FIRE_LUMP_SUM_NOT_SIMULATED", startYear + 1)
        }
    }

    @Transactional(readOnly = true)
    fun project(householdId: UUID, preview: FirePreviewRequest? = null): FireProjectionResponse {
        val s = settingsFor(householdId, preview)
        return photoAsOf(s, liveMoment(householdId), preview)
    }

    /** The reference tier under the reference scenario in today's photo, optionally with a what-if applied. */
    @Transactional(readOnly = true)
    fun referenceReachLive(householdId: UUID, preview: FirePreviewRequest? = null): FireReferenceReach {
        val s = settingsFor(householdId, preview)
        return referenceReach(s, photoInputs(s, liveMoment(householdId), preview), simulateIncompleteWindow = true)
    }

    /** The same figures as if looked at on `snapshot`'s date: its wealth, windows ending then, today's settings. */
    @Transactional(readOnly = true)
    fun referenceReachAt(householdId: UUID, snapshot: Snapshot, snapshotsUpTo: List<Snapshot>): FireReferenceReach {
        val s = getOrCreate(householdId)
        val inputs = photoInputs(s, PhotoMoment(snapshot.snapshotDate, snapshot, snapshotsUpTo), null)
        return referenceReach(s, inputs, simulateIncompleteWindow = false)
    }

    /** Net qualifying contributions in (after, upTo], and the date recording of qualifying movements began. */
    @Transactional(readOnly = true)
    fun qualifyingFlows(householdId: UUID, after: LocalDate, upTo: LocalDate): FireQualifyingFlows {
        val qualifying = getOrCreate(householdId).qualifyingAssetClasses.toSet()
        val all = movements.findInRange(householdId, FireDefaults.EARLIEST_DATA_DATE, upTo)
            .filter { it.type != MovementType.debt_payment && it.assetClassCode != null && it.assetClassCode in qualifying }
        val inRange = all.filter { it.movementDate.isAfter(after) }
        return FireQualifyingFlows(
            net = Money.normalize(inRange.fold(BigDecimal.ZERO) { acc, m -> acc + m.signedAmount() }),
            count = inRange.size,
            firstMovementDate = all.minOfOrNull { it.movementDate },
        )
    }

    private fun referenceReach(s: FireSettings, inputs: PhotoInputs, simulateIncompleteWindow: Boolean): FireReferenceReach {
        val tier = inputs.tiers.first { it.key == s.referenceTier }
        val scenarioIndex = resolveReferenceScenarioIndex(s.referenceScenarioIndex.toInt(), inputs.scenarios)
        val scenario = inputs.scenarios.getOrNull(scenarioIndex)
        val spendingMode = when (tier.key) {
            FireTierKey.lean -> inputs.spending.essentialMode
            FireTierKey.fire, FireTierKey.fat -> inputs.spending.totalMode
            FireTierKey.custom -> SpendingBaseMode.manual
        }
        val spendingDerived = spendingMode == SpendingBaseMode.derived
        val windowComplete = !spendingDerived || inputs.spending.monthsAvailable >= inputs.spending.lookbackMonths
        val simulate = scenario != null && tier.enabled && tier.targetToday != null && (windowComplete || simulateIncompleteWindow)
        val reach = scenario?.takeIf { simulate }?.let {
            inputs.engine.reach(it, inputs.monthlyContribution, inputs.trials, ScenarioRandomStreams(scenarioIndex))[tier.key]
        }
        return FireReferenceReach(
            tier = tier.key,
            horizonYear = inputs.targetYear.toInt(),
            wealth = Money.normalize(inputs.startingValue),
            target = tier.targetToday,
            coveragePercent = tier.coveragePercent,
            estimatedYear = reach?.estimatedYear,
            probability = reach?.probability,
            spendingDerived = spendingDerived,
            spendingMonthsAvailable = inputs.spending.monthsAvailable,
            spendingLookbackMonths = inputs.spending.lookbackMonths,
            contributionMode = inputs.contributions.mode,
            monthlyContribution = inputs.contributions.activeMonthly ?: Money.normalize(BigDecimal.ZERO),
            totalMonthlySpending = inputs.spending.totalMonthly,
            essentialMonthlySpending = inputs.spending.essentialMonthly,
            safeWithdrawalRatePct = inputs.swrPct,
        )
    }

    /** The photo taken today: windows end now and wealth is the latest snapshot. */
    private fun liveMoment(householdId: UUID): PhotoMoment =
        PhotoMoment(
            date = LocalDate.now(clock),
            snapshot = snapshots.findLatest(householdId),
            snapshotsUpTo = snapshots.findAllOrdered(householdId),
        )

    private fun photoAsOf(s: FireSettings, moment: PhotoMoment, preview: FirePreviewRequest?): FireProjectionResponse {
        val inputs = photoInputs(s, moment, preview)
        val qualifying = inputs.qualifying
        val orderedSnapshots = moment.snapshotsUpTo
        val (actualReturn, unavailableReason) =
            computeActualReturn(orderedSnapshots, inputs.movements, qualifying, inputs.uncoveredMonths)
        val historicalScenario =
            if (actualReturn != null) computeHistoricalScenario(orderedSnapshots, inputs.movements, qualifying) else null

        val scenarios = inputs.scenarios.mapIndexed { index, scenario ->
            inputs.engine.buildScenarioOutput(scenario, inputs.monthlyContribution, inputs.trials, ScenarioRandomStreams(index))
        }

        val effectiveSettings = s.toDto(inputs.brackets).copy(
            targetYear = inputs.targetYear,
            safeWithdrawalRatePct = inputs.swrPct,
            contributionMode = inputs.contributions.mode,
            monthlyContribution = inputs.contributions.manualMonthly,
        )

        return FireProjectionResponse(
            startYear = inputs.startYear,
            startingValue = Money.normalize(inputs.startingValue),
            snapshotDate = moment.snapshot?.snapshotDate,
            settings = effectiveSettings,
            spending = inputs.spending,
            contributions = inputs.contributions,
            gainFraction = inputs.gainFraction,
            actualReturn = actualReturn,
            actualReturnUnavailableReason = unavailableReason,
            historicalScenario = historicalScenario,
            suggestedScenario = suggestScenarioFromAssetMix(moment.snapshot, qualifying),
            tiers = inputs.tiers,
            scenarios = scenarios,
            historicalWealth = historicalWealth(orderedSnapshots, qualifying, inputs.startYear),
            cumulativeContributions = historicalCumulativeContributions(inputs.movements),
            monteCarloTrials = inputs.trials,
        )
    }

    /** Everything a projection is built from, as seen on `moment.date`, with today's settings and any what-if applied. */
    private fun photoInputs(s: FireSettings, moment: PhotoMoment, preview: FirePreviewRequest?): PhotoInputs {
        val householdId = s.householdId
        val brackets = preview?.settings?.let { requestBrackets(it) } ?: effectiveBrackets(householdId)
        val qualifying = s.qualifyingAssetClasses.toSet()
        val asOf = moment.date

        val effectiveTargetYear = preview?.targetYear ?: s.targetYear
        val effectiveSwrPct = preview?.safeWithdrawalRatePct ?: s.safeWithdrawalRatePct
        val effectiveMode = if (preview?.monthlyContribution != null) ContributionMode.manual else s.contributionMode
        val effectiveManualContrib = Money.normalize(preview?.monthlyContribution ?: s.monthlyContribution)
        val lumpSum = Money.normalize(preview?.lumpSum ?: BigDecimal.ZERO)

        val snapshot = moment.snapshot
        val rawStartingValue = qualifyingTotal(snapshot, qualifying)
        val startingValue = rawStartingValue + lumpSum
        val startYear = snapshot?.snapshotDate?.year ?: asOf.year
        val years = (effectiveTargetYear.toInt() - startYear).coerceAtLeast(0)
        val trials = props.fire.monteCarloTrials.coerceAtLeast(1)

        val inflation = s.expectedInflationPct.toDouble() / 100.0
        val swr = effectiveSwrPct.toDouble() / 100.0

        // Spending bases — derived over the configured lookback window ending at the moment's month unless
        // a manual override (or what-if preview) is active; the effective values feed everything downstream.
        val spending = resolveSpendingBasis(s, spendingAsOf(s, YearMonth.from(asOf)), preview)
            .copy(noSpendingSince = noSpendingSince(s, YearMonth.from(asOf)))

        // Qualifying movements since inception, oldest first (repository returns newest first).
        val allMovements = movements.findInRange(householdId, FireDefaults.EARLIEST_DATA_DATE, asOf)
            .filter { it.type != MovementType.debt_payment && it.assetClassCode != null && it.assetClassCode in qualifying }
            .sortedBy { it.movementDate }

        val asOfMonth = YearMonth.from(asOf)
        val contributions = deriveContributions(householdId, effectiveManualContrib, effectiveMode, allMovements, asOfMonth)
        val monthlyContribution = (contributions.activeMonthly ?: BigDecimal.ZERO).toDouble()

        val uncoveredMonths = uncoveredMovementMonths(moment.snapshotsUpTo, allMovements)
        val baseGainFraction = deriveGainFraction(s, rawStartingValue, allMovements, uncoveredMonths)
        val gainFraction = if (lumpSum.signum() > 0 && startingValue.signum() > 0) {
            val baseGainAmount = rawStartingValue.multiply(baseGainFraction.percent).divide(BigDecimal(100), 6, RoundingMode.HALF_EVEN)
            val pct = baseGainAmount.divide(startingValue, 6, RoundingMode.HALF_EVEN).multiply(BigDecimal(100))
            baseGainFraction.copy(percent = Money.normalize(pct.coerceIn(BigDecimal.ZERO, BigDecimal(100))))
        } else {
            baseGainFraction
        }
        val gainFractionRatio = gainFraction.percent.toDouble() / 100.0

        val cashFlowEvents = preview?.cashFlowEvents?.map { it.toEvent() } ?: s.cashFlowEvents
        val tiers = buildTiers(
            s = s,
            cashFlowEvents = cashFlowEvents,
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
            cashFlowEvents = cashFlowEvents,
        )

        val returnShift = preview?.returnShiftPct ?: BigDecimal.ZERO
        return PhotoInputs(
            brackets = brackets,
            qualifying = qualifying,
            targetYear = effectiveTargetYear,
            swrPct = effectiveSwrPct,
            startingValue = startingValue,
            startYear = startYear,
            trials = trials,
            spending = spending,
            movements = allMovements,
            contributions = contributions,
            monthlyContribution = monthlyContribution,
            uncoveredMonths = uncoveredMonths,
            gainFraction = gainFraction,
            tiers = tiers,
            engine = engine,
            scenarios = withoutDefaultNames(s.returnScenarios).map {
                if (returnShift.signum() == 0) it else it.copy(meanPercent = it.meanPercent + returnShift)
            },
        )
    }

    /** Today's spending window as the photo uses it, category by category. */
    @Transactional(readOnly = true)
    fun currentSpendingWindow(householdId: UUID): CostOfLivingResponse =
        spendingAsOf(getOrCreate(householdId), YearMonth.now(clock))

    /** The last month with any expense when the window's latest STALE_SPENDING_MONTHS complete months have none:
     *  transactions stopped coming in, so a derived target counts empty months and is probably understated. */
    private fun noSpendingSince(s: FireSettings, asOfMonth: YearMonth): YearMonth? {
        val derived = s.totalSpendingMode == SpendingBaseMode.derived || s.essentialSpendingMode == SpendingBaseMode.derived
        if (!derived) return null
        val windowEnd = if (s.excludeCurrentMonth) asOfMonth.minusMonths(1) else asOfMonth
        // The month the moment falls in is still open, so it neither proves nor disproves anything — unless it has spending.
        val lastComplete = if (windowEnd == asOfMonth) asOfMonth.minusMonths(1) else windowEnd
        val firstChecked = lastComplete.minusMonths(FireDefaults.STALE_SPENDING_MONTHS - 1L)
        val lastExpense = transactions.lastDateOf(s.householdId, Direction.expense, windowEnd.atEndOfMonth()) ?: return null
        return YearMonth.from(lastExpense).takeIf { it.isBefore(firstChecked) }
    }

    /** Derived cost of living over the configured window ending at `asOfMonth` (or the month before it). */
    private fun spendingAsOf(s: FireSettings, asOfMonth: YearMonth): CostOfLivingResponse {
        val endMonth = if (s.excludeCurrentMonth) asOfMonth.minusMonths(1) else asOfMonth
        val lookback = s.spendingLookbackMonths.toInt().coerceIn(1, 120)
        val spendingWindow = MonthWindow(endMonth.minusMonths((lookback - 1).toLong()), endMonth)
        return analytics.costOfLiving(
            s.householdId,
            endMonth,
            spendingWindow,
            useMedian = s.spendingAggregation == SpendingAggregationMode.median,
        )
    }

    /** When a photo is taken: the date its windows end at, its snapshot, and the snapshots known by then (oldest first). */
    private class PhotoMoment(
        val date: LocalDate,
        val snapshot: Snapshot?,
        val snapshotsUpTo: List<Snapshot>,
    )

    private class PhotoInputs(
        val brackets: List<TaxBracket>,
        val qualifying: Set<String>,
        val targetYear: Short,
        val swrPct: BigDecimal,
        val startingValue: BigDecimal,
        val startYear: Int,
        val trials: Int,
        val spending: FireSpendingBasis,
        val movements: List<NetWorthMovement>,
        val contributions: FireContributions,
        val monthlyContribution: Double,
        val uncoveredMonths: Int,
        val gainFraction: FireGainFraction,
        val tiers: List<FireTierOutput>,
        val engine: SimulationEngine,
        val scenarios: List<ReturnScenario>,
    )

    // ---------------------------------------------------------------------------------------
    // Settings helpers

    private fun effectiveBrackets(householdId: UUID): List<TaxBracket> {
        val rows = taxBrackets.findAllForHousehold(householdId)
        if (rows.isEmpty()) return FireDefaults.SPANISH_SAVINGS_TAX_BRACKETS
        return rows.map { TaxBracket(it.id.lowerBound, it.ratePct) }
    }

    /** Single definition of the effective spending bases: what-if / manual override when active, live trailing otherwise. */
    private fun resolveSpendingBasis(
        s: FireSettings,
        col: CostOfLivingResponse,
        preview: FirePreviewRequest? = null,
    ): FireSpendingBasis {
        val previewTotal = preview?.monthlySpending?.let { Money.normalize(it) }
        val totalMonthly = previewTotal ?: if (s.totalSpendingMode == SpendingBaseMode.manual) {
            Money.normalize(s.manualTotalSpending)
        } else {
            col.totalMonthlyAverage
        }
        val previewEssential = preview?.monthlyEssentialSpending?.let { Money.normalize(it) }
        val baseEssential = previewEssential ?: if (s.essentialSpendingMode == SpendingBaseMode.manual) {
            Money.normalize(s.manualEssentialSpending)
        } else {
            col.essentialMonthlyAverage
        }
        val essentialMonthly = if (previewTotal != null && baseEssential > totalMonthly) totalMonthly else baseEssential
        return FireSpendingBasis(
            essentialMonthly = essentialMonthly,
            totalMonthly = totalMonthly,
            derivedEssentialMonthly = col.essentialMonthlyAverage,
            derivedTotalMonthly = col.totalMonthlyAverage,
            essentialMode = if (previewEssential != null) SpendingBaseMode.manual else s.essentialSpendingMode,
            totalMode = if (previewTotal != null) SpendingBaseMode.manual else s.totalSpendingMode,
            monthsAvailable = col.monthsAvailable,
            lookbackMonths = s.spendingLookbackMonths.toInt().coerceIn(1, 120),
            aggregation = s.spendingAggregation,
            excludeCurrentMonth = s.excludeCurrentMonth,
        )
    }

    private fun FireSettings.toDto(brackets: List<TaxBracket>): FireSettingsDto {
        val named = withoutDefaultNames(returnScenarios)
        return FireSettingsDto(
            targetAmount = targetAmount,
            targetYear = targetYear,
            monthlyContribution = monthlyContribution,
            returnScenarios = named.map { ReturnScenarioDto(it.meanPercent, it.stdDevPercent, it.historical, it.name) },
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
            spendingLookbackMonths = spendingLookbackMonths,
            spendingAggregation = spendingAggregation,
            excludeCurrentMonth = excludeCurrentMonth,
            fallbackGainFractionPct = fallbackGainFractionPct,
            taxBrackets = brackets.map { TaxBracketDto(it.lowerBound, it.ratePct) },
            cashFlowEvents = cashFlowEvents.map { FireCashFlowEventDto(it.year, it.kind, it.amount, it.label, it.template) },
            referenceTier = referenceTier,
            referenceScenarioIndex = resolveReferenceScenarioIndex(referenceScenarioIndex.toInt(), named),
        )
    }

    /** Only a custom name is a name: blank, or a default name in any language, means "unnamed" and the UI shows the
     *  localized default for the scenario's rank by mean. Applied on read and on save, so old rows normalise lazily. */
    private fun withoutDefaultNames(scenarios: List<ReturnScenario>): List<ReturnScenario> =
        scenarios.map { sc ->
            val name = sc.name.trim().takeUnless { FireDefaults.isDefaultScenarioName(it) }.orEmpty()
            if (name == sc.name) sc else sc.copy(name = name)
        }

    private fun resolveReferenceScenarioIndex(requested: Int, scenarios: List<ReturnScenario>): Int {
        if (requested in scenarios.indices) return requested
        if (scenarios.isEmpty()) return 0
        val sorted = scenarios.indices.sortedWith(compareBy({ scenarios[it].meanPercent }, { it }))
        val middleMean = scenarios[sorted[sorted.size / 2]].meanPercent
        return scenarios.indexOfFirst { it.meanPercent.compareTo(middleMean) == 0 }.coerceAtLeast(0)
    }

    private fun resolveReferenceTier(requested: FireTierKey, request: FireSettingsRequest): FireTierKey {
        val enabled = mapOf(
            FireTierKey.lean to request.tierLeanEnabled,
            FireTierKey.fire to request.tierFireEnabled,
            FireTierKey.fat to request.tierFatEnabled,
            FireTierKey.custom to request.tierCustomEnabled,
        )
        if (enabled[requested] == true) return requested
        if (enabled[FireTierKey.fire] == true) return FireTierKey.fire
        return enabled.entries.firstOrNull { it.value }?.key ?: FireTierKey.fire
    }

    // ---------------------------------------------------------------------------------------
    // Derived inputs (never persisted — recomputed live from their sources)

    private fun deriveContributions(
        householdId: UUID,
        manualMonthly: BigDecimal,
        mode: ContributionMode,
        allMovements: List<NetWorthMovement>,
        asOf: YearMonth,
    ): FireContributions {
        val manual = Money.normalize(manualMonthly)
        val (savings, savingsRatePct) = deriveSavingsMonthly(householdId, asOf)
        val fromMovements = deriveMovementsMonthly(allMovements, asOf)
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
    private fun deriveSavingsMonthly(householdId: UUID, asOf: YearMonth): Pair<BigDecimal?, Double?> {
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
    private fun deriveMovementsMonthly(allMovements: List<NetWorthMovement>, asOf: YearMonth): BigDecimal? {
        if (allMovements.isEmpty()) return null
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

    /** Weighted long-term nominal annual return & volatility estimate from the latest qualifying snapshot. */
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
        cashFlowEvents: List<FireCashFlowEvent>,
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
            val spendingDeltas = cashFlowEvents.filter { it.kind == CashFlowEventKind.spending_delta }
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
                // Custom target is entered in today's money and inflates year by year like every other tier (no tax gross-up).
                val curve = (0..years).map { y ->
                    FireScenarioYear(
                        startYear + y,
                        Money.normalize(BigDecimal(target.toDouble() * (1.0 + inflation).pow(y))),
                    )
                }
                FireTierOutput(
                    key = FireTierKey.custom,
                    enabled = s.tierCustomEnabled,
                    monthlyNetSpending = null,
                    annualNetSpending = null,
                    annualGrossSpending = null,
                    estimatedAnnualTax = null,
                    targetToday = target,
                    coveragePercent = if (target.signum() > 0) startingValueD / target.toDouble() * 100.0 else null,
                    targetCurve = curve,
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
            if (y == 0) {
                0.0
            } else {
                val calYear = startYear + y
                val base = cashFlowEvents.filter { it.kind == CashFlowEventKind.lump_sum && it.year == calYear }
                    .sumOf { it.amount.toDouble() }
                base * (1.0 + inflation).pow(y - 1)
            }
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
        /** Custom tier targets inflated per year across the full accumulation + drawdown span; null for spending tiers. */
        private val customTargetByYear: Array<DoubleArray?> = Array(simTiers.size) { t ->
            if (simTiers[t].annualNetSpending == null) {
                simTiers[t].targetToday?.toDouble()?.let { base ->
                    DoubleArray(totalSpan + 1) { y -> base * (1.0 + inflation).pow(y) }
                }
            } else {
                null
            }
        }
        private val horizonTargets: DoubleArray = DoubleArray(simTiers.size) { t ->
            simTiers[t].targetCurve.lastOrNull()?.value?.toDouble()
                ?: simTiers[t].targetToday?.toDouble()
                ?: Double.NaN
        }

        /** With tax off targets don't depend on the path — precomputed once. */
        private val fixedTargets: Array<DoubleArray>? = if (taxOn) {
            null
        } else {
            Array(simTiers.size) { t ->
                DoubleArray(years + 1) { y ->
                    customTargetByYear[t]?.get(y) ?: (netAnnualByYear[t][y] / swr)
                }
            }
        }

        private fun targetFor(tier: Int, yearIdx: Int, gainFraction: Double): Double {
            customTargetByYear[tier]?.let { return it[yearIdx] }
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
            outNominal: DoubleArray,
            outReal: DoubleArray,
        ): Boolean {
            var value = hitValue
            var costBasis = hitCostBasis
            outNominal[0] = value
            outReal[0] = value / (1.0 + inflation).pow(startIdx)
            val customTargets = customTargetByYear[tierIdx]
            var survived = true
            for (k in 1..drawdownYears) {
                val defl = (1.0 + inflation).pow(startIdx + k)
                if (value <= 0.0) {
                    survived = false
                    outNominal[k] = 0.0
                    outReal[k] = 0.0
                    continue
                }
                // First withdrawal (k=1) is in hit-year money (startIdx = h), matching the contribution timing convention.
                val wIdx = startIdx + k - 1
                val gross = if (customTargets != null) {
                    // Custom tier is a wealth target, not a net-spending base: no tax gross-up.
                    customTargets[wIdx] * swr
                } else {
                    val netSpending = netAnnualByYear[tierIdx][wIdx]
                    if (taxOn) {
                        val gainFraction = ((value - costBasis) / value).coerceIn(0.0, 1.0)
                        compiledBrackets.grossUp(netSpending, gainFraction, bracketScales[wIdx])
                    } else {
                        netSpending
                    }
                }
                if (gross >= value) {
                    survived = false
                    value = 0.0
                    outNominal[k] = 0.0
                    outReal[k] = 0.0
                    continue
                }
                costBasis *= ((value - gross) / value).coerceAtLeast(0.0)
                value = (value - gross) * (1.0 + nextReturn())
                if (value <= 0.0) {
                    survived = false
                    value = 0.0
                }
                outNominal[k] = value
                outReal[k] = value / defl
            }
            return survived
        }

        private class McStats(
            val percentiles: List<FireScenarioPercentiles>,
            val probability: DoubleArray,
            val medianHitYearAmongReaching: Array<Int?>,
            val estimatedYear: Array<Int?>,
            val drawdownSurvivalRate: Array<Double?>,
            val drawdownPercentiles: Array<List<FireScenarioPercentiles>>,
            val drawdownRealPercentiles: Array<List<FireScenarioPercentiles>>,
        )

        private fun logNormalSampler(mean: Double, stdDev: Double, rng: SplittableRandom): () -> Double {
            if (stdDev == 0.0 || 1.0 + mean <= 0.0) return { mean.coerceAtLeast(-0.9999) }
            val (muLn, sigmaLn) = FireDefaults.logNormalParams(mean, stdDev)
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
            returnsRng: SplittableRandom,
            drawdownRng: SplittableRandom,
            collectPercentiles: Boolean,
        ): McStats {
            val mean = scenario.meanPercent.toDouble() / 100.0
            val stdDev = scenario.stdDevPercent.toDouble() / 100.0
            val sampleReturn = logNormalSampler(mean, stdDev, returnsRng)
            // Drawdown draws come from their own stream, so a run without drawdown sees identical accumulation paths.
            val sampleDrawdownReturn = logNormalSampler(mean, stdDev, drawdownRng)
            val columns = if (collectPercentiles) Array(years + 1) { DoubleArray(trials) } else null
            val hitYears = Array(tierKeys.size) { IntArray(trials) }
            val survivedCounts = IntArray(tierKeys.size)
            val hitCounts = IntArray(tierKeys.size)
            val drawdownPaths = if (collectPercentiles) Array(tierKeys.size) { mutableListOf<DoubleArray>() } else null
            val drawdownRealPaths = if (collectPercentiles) Array(tierKeys.size) { mutableListOf<DoubleArray>() } else null
            val scratchDrawdown = DoubleArray(drawdownYears + 1)
            val scratchDrawdownReal = DoubleArray(drawdownYears + 1)

            for (trial in 0 until trials) {
                val path = simulatePath(monthlyContribution) { sampleReturn() }
                columns?.let { for (i in 0..years) it[i][trial] = path.values[i] }
                for (t in tierKeys.indices) {
                    hitYears[t][trial] = path.hitYears[t]
                    if (collectPercentiles && path.hitIdx[t] >= 0) {
                        hitCounts[t]++
                        if (simulateDrawdown(t, path.hitIdx[t], path.hitValues[t], path.hitCostBases[t], sampleDrawdownReturn, scratchDrawdown, scratchDrawdownReal)) {
                            survivedCounts[t]++
                        }
                        drawdownPaths!![t].add(scratchDrawdown.copyOf())
                        drawdownRealPaths!![t].add(scratchDrawdownReal.copyOf())
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
            val medianHitYearAmongReaching = Array<Int?>(tierKeys.size) { null }
            val estimatedYear = Array<Int?>(tierKeys.size) { null }
            val halfOfTrials = (trials + 1) / 2
            val survivalRate = Array<Double?>(tierKeys.size) { null }
            val drawdownPercentiles = Array<List<FireScenarioPercentiles>>(tierKeys.size) { emptyList() }
            val drawdownRealPercentiles = Array<List<FireScenarioPercentiles>>(tierKeys.size) { emptyList() }
            for (t in tierKeys.indices) {
                val reachers = hitYears[t].filter { it >= 0 }
                probability[t] = reachers.size.toDouble() / trials
                if (reachers.isNotEmpty()) {
                    val sorted = reachers.sorted()
                    medianHitYearAmongReaching[t] = sorted[sorted.size / 2]
                    // The halfOfTrials-th earliest hit is the first year by which half of ALL trials have reached.
                    if (sorted.size >= halfOfTrials) estimatedYear[t] = sorted[halfOfTrials - 1]
                }
                if (collectPercentiles && hitCounts[t] > 0) {
                    survivalRate[t] = survivedCounts[t].toDouble() / hitCounts[t]
                    drawdownPercentiles[t] = computeDrawdownPercentiles(drawdownPaths!![t])
                    drawdownRealPercentiles[t] = computeDrawdownPercentiles(drawdownRealPaths!![t])
                }
            }
            return McStats(percentiles, probability, medianHitYearAmongReaching, estimatedYear, survivalRate, drawdownPercentiles, drawdownRealPercentiles)
        }

        /** Odds and estimated year per tier only — same draws as `buildScenarioOutput`'s accumulation run. */
        fun reach(
            scenario: ReturnScenario,
            monthlyContribution: Double,
            trials: Int,
            streams: ScenarioRandomStreams,
        ): Map<FireTierKey, TierReach> {
            val mc = monteCarlo(scenario, monthlyContribution, trials, streams.returns(), streams.drawdown(), collectPercentiles = false)
            return tierKeys.indices.associate { t -> tierKeys[t] to TierReach(mc.probability[t], mc.estimatedYear[t]) }
        }

        class TierReach(val probability: Double, val estimatedYear: Int?)

        private fun computeDrawdownPercentiles(paths: List<DoubleArray>): List<FireScenarioPercentiles> =
            (0..drawdownYears).map { k ->
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

        fun buildScenarioOutput(
            scenario: ReturnScenario,
            monthlyContribution: Double,
            trials: Int,
            streams: ScenarioRandomStreams,
        ): FireScenarioOutput {
            val mean = scenario.meanPercent.toDouble() / 100.0
            val deterministic = simulatePath(monthlyContribution) { mean }
            val mc = monteCarlo(scenario, monthlyContribution, trials, streams.returns(), streams.drawdown(), collectPercentiles = true)
            val coast = monteCarlo(scenario, 0.0, trials, streams.coast(), streams.drawdown(), collectPercentiles = false)

            val growthFactor = (1.0 + mean).pow(years)
            return FireScenarioOutput(
                name = scenario.name,
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
                        estimatedYear = mc.estimatedYear[t],
                        medianYearAmongReaching = mc.medianHitYearAmongReaching[t],
                        coastProbabilityOfReachingTarget = coast.probability[t],
                        coastEstimatedYear = coast.estimatedYear[t],
                        coastMedianYearAmongReaching = coast.medianHitYearAmongReaching[t],
                        coastTargetToday = coastTarget,
                        requiredMonthlyToHitTarget = requiredMonthlyFor(t, mean),
                        drawdownSurvivalRate = mc.drawdownSurvivalRate[t],
                        drawdownPercentiles = mc.drawdownPercentiles[t],
                        drawdownRealPercentiles = mc.drawdownRealPercentiles[t],
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
        /** Nominal long-term annual arithmetic mean & std dev assumptions (%) per asset class. */
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
