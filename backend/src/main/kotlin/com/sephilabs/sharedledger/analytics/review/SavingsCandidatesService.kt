package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.ai.SpendingStaleness
import com.sephilabs.sharedledger.analytics.MonthWindow
import com.sephilabs.sharedledger.analytics.recurring.ExpenseRecord
import com.sephilabs.sharedledger.analytics.recurring.PayeeVariantClusterer
import com.sephilabs.sharedledger.analytics.recurring.RecurringAction
import com.sephilabs.sharedledger.analytics.recurring.RecurringChargeDetector
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.recurring.RecurringTemplateRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Deterministic savings-candidate engine behind both the local "What this is based on" panel
 *  (available without AI) and the sanitized AI spending review payload. */
@Service
class SavingsCandidatesService(
    private val transactions: TransactionRepository,
    private val categoryService: CategoryService,
    private val recurringTemplates: RecurringTemplateRepository,
    private val dataQueries: SpendingReviewDataQueries,
    private val decisionService: SpendingReviewDecisionService,
    private val cache: SpendingReviewCache,
    private val clock: Clock,
) {

    private val TWELVE = BigDecimal.valueOf(12L)

    @Transactional(readOnly = true)
    fun candidates(householdId: UUID, year: Int? = null, month: Int? = null): SpendingReviewCandidatesResponse {
        val today = LocalDate.now(clock)
        val period = resolvePeriod(year, month, today)
        return computeForAsOf(householdId, period, today)
    }

    /** Computes (with fingerprint cache) savings candidates for [period] evaluated strictly as of [asOfDate].
     *  Used both for the live review and for clock-equivalent recomputation of the previous month. */
    @Transactional(readOnly = true)
    fun computeForAsOf(householdId: UUID, period: YearMonth, asOfDate: LocalDate): SpendingReviewCandidatesResponse =
        cache.getOrCompute(householdId, period.year, period.monthValue, asOfDate) {
            compute(householdId, period, asOfDate)
        }

    /** Resolves requested (year, month) or defaults to the last complete month. Rejects invalid or future months. */
    fun resolvePeriod(year: Int?, month: Int?, today: LocalDate = LocalDate.now(clock)): YearMonth {
        val currentYm = YearMonth.from(today)
        if (year == null && month == null) return currentYm.minusMonths(1)
        if (year == null || month == null || month !in 1..12 || year !in 1970..2200) {
            throw AppException.badRequest("INVALID_PARAMETER", if (month == null || month !in 1..12) "month" else "year")
        }
        val requested = YearMonth.of(year, month)
        if (requested.isAfter(currentYm)) {
            throw AppException.badRequest("INVALID_PARAMETER", "month")
        }
        return requested
    }

    private fun compute(
        householdId: UUID,
        period: YearMonth,
        today: LocalDate,
    ): SpendingReviewCandidatesResponse {
        val currentYm = YearMonth.from(today)
        val isPace = period == currentYm
        val daysInMonth = period.lengthOfMonth()
        val daysElapsed = if (isPace) today.dayOfMonth else daysInMonth
        val paceEligible = !isPace || daysElapsed >= SpendingReviewConstants.AI_REVIEW_MIN_DAYS_ELAPSED

        val window12 = MonthWindow(period.minusMonths(11), period)
        val windowFrom = window12.start.atDay(1)
        val windowTo = if (isPace) today else period.atEndOfMonth()

        val lookback24From = period.minusMonths((SpendingReviewConstants.RECURRING_LOOKBACK_MONTHS - 1).toLong()).atDay(1)
        val allTx24 = transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, lookback24From, windowTo)
        val bankMeta = dataQueries.bankMetadataForTransactions(householdId, lookback24From, windowTo)
        val memberTokens = dataQueries.householdMemberTokens(householdId)
        val categories = categoryService.listForHousehold(householdId)
        val catMeta = categories.associate { it.code to (it.group to it.essential) }

        val bounds = transactions.dateBounds(householdId)
        val monthsAvailable = window12.monthsCoveredSince(bounds.minDate)
        val lastNonTemplateDate = dataQueries.latestNonTemplateExpenseDate(householdId, today)
        val lastMonthWithDataYm = lastNonTemplateDate?.let { YearMonth.from(it) }
        val staleSinceFromAll = SpendingStaleness.noSpendingSince(transactions, householdId, period, currentYm)
        val staleSinceNonTemplate = run {
            val lastComplete = if (period == currentYm) currentYm.minusMonths(1) else period
            val firstChecked = lastComplete.minusMonths(com.sephilabs.sharedledger.fire.FireDefaults.STALE_SPENDING_MONTHS - 1L)
            val lastInPeriod = dataQueries.latestNonTemplateExpenseDate(householdId, windowTo)
            lastInPeriod?.let { YearMonth.from(it) }?.takeIf { it.isBefore(firstChecked) }
        }
        val staleSince = staleSinceFromAll ?: staleSinceNonTemplate

        val periodStart = period.atDay(1)
        val periodHasNoExpenses = allTx24.none {
            it.direction == Direction.expense &&
                it.recurringTemplateId == null &&
                !it.occurrenceDate.isBefore(periodStart) &&
                !it.occurrenceDate.isAfter(windowTo)
        }
        val reviewBlockedReason = when {
            !paceEligible -> "pace_too_early"
            periodHasNoExpenses || staleSince != null -> "empty_or_stale"
            else -> null
        }

        // Non-refund expense records over 24m for recurring detection on raw local counterparties.
        val nonRefund24 = allTx24
            .filter { it.direction == Direction.expense && !it.isRefund && it.amount.signum() > 0 }
            .map { tx ->
                val link = bankMeta[tx.id]
                val meta = catMeta[tx.categoryCode]
                ExpenseRecord(
                    id = tx.id,
                    occurrenceDate = tx.occurrenceDate,
                    categoryCode = tx.categoryCode,
                    groupCode = meta?.first,
                    essential = meta?.second ?: false,
                    amount = tx.amount,
                    rawLabel = MerchantSanitizer.extractLocalLabel(link?.counterparty, tx.description),
                    bankDescription = link?.description ?: tx.description,
                    recurringTemplateId = tx.recurringTemplateId,
                )
            }

        val activeExpenseTemplates = recurringTemplates.findAllByHouseholdIdAndActiveTrue(householdId)
            .filter { it.direction == Direction.expense }

        val decodedDecisions = decisionService.listDecoded(householdId)
        val splitRules = decodedDecisions.mapNotNull { it.toSplitRule() }
        val notRecurringDecisions = decodedDecisions.filter { it.decisionType == SpendingReviewDecisionType.not_recurring }
        val decisionEmails = dataQueries.userEmails(decodedDecisions.map { it.decidedByUserId })

        val observedKeysByCategory: Map<String, Set<String>> = nonRefund24
            .mapNotNull { r -> r.rawLabel?.let(PayeeVariantClusterer::canonicalKey)?.takeIf { it.isNotBlank() }?.let { r.categoryCode to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.toSet() }

        fun resolveBadge(
            kind: DecisionSubjectKind,
            categoryCode: String,
            memberKeys: Set<String>,
            currentMonthlyCost: BigDecimal,
        ): ActiveDecisionBadgeDto? {
            val match = decodedDecisions.firstOrNull { d ->
                (d.decisionType == SpendingReviewDecisionType.keep || d.decisionType == SpendingReviewDecisionType.compared) &&
                    d.matches(kind, categoryCode, memberKeys)
            } ?: return null
            val status = when {
                match.decisionType == SpendingReviewDecisionType.keep -> "active"
                match.isComparedExpired(windowTo) -> "expired"
                match.isComparedResurfaced(currentMonthlyCost) -> "resurfaced"
                else -> "snoozed"
            }
            return ActiveDecisionBadgeDto(
                id = match.id,
                decisionType = match.decisionType,
                status = status,
                decidedBy = decisionEmails[match.decidedByUserId].orEmpty(),
                decidedAt = match.decidedAt,
                amountAtDecision = match.amountAtDecision,
                snoozeUntil = match.snoozeUntil,
            )
        }

        // 1. Recurring charges (templates + detected over 24m, honouring split and not_recurring decisions).
        val detectedRecurring = RecurringChargeDetector.detect(
            history24m = nonRefund24,
            activeTemplates = activeExpenseTemplates,
            categoryMeta = catMeta,
            trailing12Start = windowFrom,
            splitRules = splitRules,
            excludedPayeeMatcher = { catCode, mKeys ->
                notRecurringDecisions.any { d -> d.matches(DecisionSubjectKind.recurring, catCode, mKeys) }
            },
            householdMinDate = bounds.minDate,
            twoYearWindowEnd = period,
        )
        val recurringDtos = detectedRecurring.mapIndexed { idx, r ->
            val id = "rec_${idx + 1}"
            val subjectKey = PayeeVariantClusterer.stableSubjectKey(DecisionSubjectKind.recurring.name, r.categoryCode, r.memberKeys)
            val badge = resolveBadge(DecisionSubjectKind.recurring, r.categoryCode, r.memberKeys, r.monthlyCost)
            val cls = MerchantSanitizer.classifyForPayload(
                localLabel = r.merchant,
                bankDescription = r.bankDescription,
                categoryCode = r.categoryCode,
                memberTokens = memberTokens,
                occurrences = r.occurrences,
            )
            val payloadLabel = if (cls.allowedInPayload) cls.sanitizedLabel!! else MerchantSanitizer.anonymizedLabel("Recurring charge", r.categoryCode)
            val isKept = badge?.decisionType == SpendingReviewDecisionType.keep
            val canCut = !isKept && (r.action == RecurringAction.cancellable || isFeeCategory(r.categoryCode, r.groupCode))
            val potMonthly = if (canCut) r.monthlyCost else null
            val potAnnual = if (canCut) r.annualCost else null
            RecurringChargeCandidateDto(
                id = id,
                subjectKey = subjectKey,
                memberKeys = r.memberKeys.sorted(),
                merchant = r.merchant,
                mergedVariants = r.mergedVariants,
                attributedEntries = r.attributedEntries,
                payloadLabel = payloadLabel,
                anonymized = !cls.allowedInPayload,
                categoryCode = r.categoryCode,
                groupCode = r.groupCode,
                essential = r.essential,
                action = r.action,
                cadence = r.cadence,
                source = r.source,
                occurrences = r.occurrences,
                latestAmount = r.latestAmount,
                monthlyCost = r.monthlyCost,
                annualCost = r.annualCost,
                priorYearAmount = r.priorYearAmount,
                yoyDeltaAbs = r.yoyDeltaAbs,
                yoyDeltaPct = r.yoyDeltaPct,
                yoyChangeKind = r.yoyChangeKind,
                potentialMonthlySaving = potMonthly,
                potentialAnnualSaving = potAnnual,
                lastOccurrenceDate = r.lastOccurrenceDate,
                decision = badge,
                txFilter = TransactionLinkFilterDto(
                    from = windowFrom,
                    to = windowTo,
                    direction = "expense",
                    categoryCode = r.categoryCode,
                    q = r.searchQuery,
                ),
                explorerLink = ExplorerLinkDto(
                    scopeType = "category",
                    scopeCode = r.categoryCode,
                    preset = "custom",
                    from = windowFrom,
                    to = windowTo,
                ),
            )
        }

        // Pre-compute 12m non-essential category medians so zero-median categories never enter Savings
        // (neither as merchants nor as habitual-but-uneven categories; they go to What changed as one-offs).
        val allExpenses12 = allTx24.filter {
            it.direction == Direction.expense && !it.occurrenceDate.isBefore(windowFrom) && !it.occurrenceDate.isAfter(windowTo)
        }
        val activeMonths = if (monthsAvailable > 0) {
            (0 until monthsAvailable).map { offset -> period.minusMonths(offset.toLong()) }.reversed()
        } else emptyList()
        val nonEssentialSeriesByCategory: Map<String, List<BigDecimal>> = if (activeMonths.isNotEmpty()) {
            allExpenses12
                .filter { catMeta[it.categoryCode]?.second == false }
                .groupBy { it.categoryCode }
                .mapValues { (_, list) ->
                    val perMonth = list.groupBy { YearMonth.from(it.occurrenceDate) }
                        .mapValues { (_, txs) -> txs.fold(BigDecimal.ZERO) { a, t -> a + t.amount } }
                    activeMonths.map { ym -> (perMonth[ym] ?: BigDecimal.ZERO).max(BigDecimal.ZERO) }
                }
        } else emptyMap()
        val zeroMedianCategories: Set<String> = nonEssentialSeriesByCategory
            .filterValues { series -> median(series).signum() <= 0 }
            .keys

        // 2. Top merchants in non-essential categories over trailing 12m (variant-merged, excluding zero-median categories).
        val nonRefund12 = nonRefund24.filter { !it.occurrenceDate.isBefore(windowFrom) }
        val nonEssential12 = nonRefund12.filter { !it.essential && it.categoryCode !in zeroMedianCategories }
        val nonEssentialCategoryTotals = nonEssential12.groupBy { it.categoryCode }
            .mapValues { (_, list) -> list.fold(BigDecimal.ZERO) { acc, r -> acc + r.amount } }
        val denomMonths = monthsAvailable.coerceAtLeast(1).toBigDecimal()

        val merchantClusters = PayeeVariantClusterer.clusterByCategory(nonEssential12, splitRules)
            .sortedByDescending { c -> c.records.fold(BigDecimal.ZERO) { acc, r -> acc + r.amount } }

        val qualifiedMerchants = mutableListOf<TopMerchantCandidateDto>()
        var otherTotal = nonEssential12.filter { it.rawLabel.isNullOrBlank() }.fold(BigDecimal.ZERO) { a, r -> a + r.amount }
        var otherPurchases = nonEssential12.count { it.rawLabel.isNullOrBlank() }
        var otherMerchantCount = 0

        for (cluster in merchantClusters) {
            val list = cluster.records
            val total = Money.normalize(list.fold(BigDecimal.ZERO) { acc, r -> acc + r.amount })
            val purchases = list.size
            val catCode = cluster.categoryCode
            val catTotal = nonEssentialCategoryTotals[catCode] ?: BigDecimal.ZERO
            val sharePct = if (catTotal.signum() > 0) {
                total.divide(catTotal, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0
            } else 0.0

            if (purchases < SpendingReviewConstants.MIN_MERCHANT_PURCHASES ||
                sharePct < SpendingReviewConstants.MIN_MERCHANT_CATEGORY_SHARE_PCT
            ) {
                otherTotal += total
                otherPurchases += purchases
                otherMerchantCount += 1
                continue
            }

            val latest = list.last()
            val label = cluster.canonicalMerchant
            val bankDesc = list.lastOrNull { !it.bankDescription.isNullOrBlank() }?.bankDescription
            val cls = MerchantSanitizer.classifyForPayload(
                localLabel = label,
                bankDescription = bankDesc,
                categoryCode = catCode,
                memberTokens = memberTokens,
                occurrences = purchases,
            )
            val payloadLabel = if (cls.allowedInPayload) cls.sanitizedLabel!! else MerchantSanitizer.anonymizedLabel("Frequent counterparty", catCode)
            val avgTicket = total.divide(BigDecimal.valueOf(purchases.toLong()), 2, RoundingMode.HALF_EVEN)
            val monthlyEq = Money.normalize(total.divide(denomMonths, 2, RoundingMode.HALF_EVEN))
            val subjectKey = PayeeVariantClusterer.stableSubjectKey(DecisionSubjectKind.merchant.name, catCode, cluster.memberKeys)
            val badge = resolveBadge(DecisionSubjectKind.merchant, catCode, cluster.memberKeys, monthlyEq)

            qualifiedMerchants += TopMerchantCandidateDto(
                id = "merch_${qualifiedMerchants.size + 1}",
                subjectKey = subjectKey,
                memberKeys = cluster.memberKeys.sorted(),
                merchant = label,
                mergedVariants = cluster.mergedVariants,
                payloadLabel = payloadLabel,
                anonymized = !cls.allowedInPayload,
                categoryCode = catCode,
                groupCode = latest.groupCode,
                essential = false,
                total = total,
                purchases = purchases,
                averageTicket = Money.normalize(avgTicket),
                shareOfCategoryPct = sharePct,
                monthlyEquivalent = monthlyEq,
                potentialMonthlySaving = null,
                potentialAnnualSaving = null,
                decision = badge,
                txFilter = TransactionLinkFilterDto(
                    from = windowFrom,
                    to = windowTo,
                    direction = "expense",
                    categoryCode = catCode,
                    q = cluster.searchQuery,
                ),
                explorerLink = ExplorerLinkDto(
                    scopeType = "category",
                    scopeCode = catCode,
                    preset = "custom",
                    from = windowFrom,
                    to = windowTo,
                ),
            )
        }

        // 3. Fees and loan interest paid over 12m (allExpenses12 already netted over window12).
        val feeRows = allExpenses12
            .filter { isFeeCategory(it.categoryCode, catMeta[it.categoryCode]?.first) }
            .groupBy { it.categoryCode }
            .entries
            .mapNotNull { (code, list) ->
                val sum = Money.normalize(list.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount })
                if (sum.signum() <= 0) return@mapNotNull null
                val monthlyAvg = Money.normalize(sum.divide(denomMonths, 2, RoundingMode.HALF_EVEN))
                val annual = Money.normalize(monthlyAvg.multiply(TWELVE))
                val meta = catMeta[code]
                FeeCandidateDto(
                    id = "fee_${code.replace('.', '_')}",
                    categoryCode = code,
                    groupCode = meta?.first,
                    essential = meta?.second ?: true,
                    total12m = sum,
                    monthlyAverage = monthlyAvg,
                    annualCost = annual,
                    occurrences = list.count { !it.isRefund },
                    potentialMonthlySaving = monthlyAvg,
                    potentialAnnualSaving = annual,
                    txFilter = TransactionLinkFilterDto(
                        from = windowFrom,
                        to = windowTo,
                        direction = "expense",
                        categoryCode = code,
                    ),
                    explorerLink = ExplorerLinkDto(
                        scopeType = "category",
                        scopeCode = code,
                        preset = "custom",
                        from = windowFrom,
                        to = windowTo,
                    ),
                )
            }
            .sortedByDescending { it.total12m }

        val loanInterest = dataQueries.chargedLoanInterest(householdId, windowFrom, windowTo)?.let { agg ->
            val total = Money.normalize(agg.total)
            LoanInterestInfoDto(
                total12m = total,
                monthlyAverage = Money.normalize(total.divide(denomMonths, 2, RoundingMode.HALF_EVEN)),
                occurrences = agg.count,
                essential = true,
            )
        }

        // 4. Habitual-but-uneven non-essential categories (median > 0) vs zero-median one-off categories.
        //    Zero-median categories NEVER appear in Savings; they go to What changed as one-offs.
        val habitualCandidates = mutableListOf<LumpyCategoryCandidateDto>()
        val oneOffCandidates = mutableListOf<LumpyCategoryCandidateDto>()
        if (activeMonths.size >= SpendingReviewConstants.LUMPY_MIN_NON_ZERO_MONTHS) {
            for ((code, series) in nonEssentialSeriesByCategory) {
                val nonZeroMonths = series.count { it.signum() > 0 }
                if (nonZeroMonths == 0) continue

                val total = series.fold(BigDecimal.ZERO) { a, v -> a + v }
                val mean = Money.normalize(total.divide(BigDecimal.valueOf(series.size.toLong()), 2, RoundingMode.HALF_EVEN))
                val med = Money.normalize(median(series))
                val trimMonthly = Money.normalize((mean - med).max(BigDecimal.ZERO))
                if (trimMonthly < SpendingReviewConstants.LUMPY_MIN_TRIM_MONTHLY_EUR) continue

                val trimAnnual = Money.normalize(trimMonthly.multiply(TWELVE))
                val peak = Money.normalize(series.max())
                val groupCode = catMeta[code]?.first
                val dto = LumpyCategoryCandidateDto(
                    id = if (med.signum() > 0) "habitual_${code.replace('.', '_')}" else "oneoff_${code.replace('.', '_')}",
                    categoryCode = code,
                    groupCode = groupCode,
                    essential = false,
                    monthlyMean = mean,
                    monthlyMedian = med,
                    trimToMedianMonthly = trimMonthly,
                    trimToMedianAnnual = trimAnnual,
                    potentialMonthlySaving = if (med.signum() > 0) trimMonthly else BigDecimal.ZERO,
                    potentialAnnualSaving = if (med.signum() > 0) trimAnnual else BigDecimal.ZERO,
                    nonZeroMonths = nonZeroMonths,
                    peakMonthAmount = peak,
                    txFilter = TransactionLinkFilterDto(
                        from = windowFrom,
                        to = windowTo,
                        direction = "expense",
                        categoryCode = code,
                    ),
                    explorerLink = ExplorerLinkDto(
                        scopeType = "category",
                        scopeCode = code,
                        preset = "custom",
                        from = windowFrom,
                        to = windowTo,
                    ),
                )

                if (med.signum() > 0) {
                    val ratioOk = mean.divide(med, 4, RoundingMode.HALF_EVEN) >= SpendingReviewConstants.LUMPY_MIN_MEAN_MEDIAN_RATIO
                    val activeOk = nonZeroMonths >= SpendingReviewConstants.LUMPY_MIN_ACTIVE_MONTHS ||
                        activeMonths.size < SpendingReviewConstants.LUMPY_MIN_ACTIVE_MONTHS
                    if (ratioOk && activeOk) habitualCandidates += dto
                } else {
                    val oneOffOk = nonZeroMonths >= SpendingReviewConstants.LUMPY_MIN_NON_ZERO_MONTHS ||
                        total >= SpendingReviewConstants.LUMPY_MIN_TRIM_MONTHLY_EUR.multiply(TWELVE)
                    if (oneOffOk) oneOffCandidates += dto
                }
            }
        }
        val sortedLumpy = habitualCandidates.sortedByDescending { it.trimToMedianMonthly }
        val sortedOneOffs = oneOffCandidates.sortedByDescending { it.monthlyMean }

        // 5. Pre-computed rollups & cumulative cut bundles (zero arithmetic for the model).
        val nonEssRecMonthly = Money.normalize(
            recurringDtos.filter { !it.essential && it.potentialMonthlySaving != null }
                .fold(BigDecimal.ZERO) { a, r -> a + r.potentialMonthlySaving!! },
        )
        val nonEssRecAnnual = Money.normalize(nonEssRecMonthly.multiply(TWELVE))
        val feesMonthly = Money.normalize(feeRows.fold(BigDecimal.ZERO) { a, f -> a + (f.potentialMonthlySaving ?: BigDecimal.ZERO) })
        val feesAnnual = Money.normalize(feesMonthly.multiply(TWELVE))
        val lumpyMonthly = Money.normalize(sortedLumpy.fold(BigDecimal.ZERO) { a, l -> a + l.potentialMonthlySaving })
        val lumpyAnnual = Money.normalize(lumpyMonthly.multiply(TWELVE))
        val combinedMonthly = Money.normalize(nonEssRecMonthly + feesMonthly + lumpyMonthly)
        val combinedAnnual = Money.normalize(combinedMonthly.multiply(TWELVE))

        val cuttablePool: List<Pair<String, BigDecimal>> = (
            recurringDtos.mapNotNull { r -> r.potentialMonthlySaving?.takeIf { it.signum() > 0 }?.let { r.id to it } } +
                feeRows.mapNotNull { f -> f.potentialMonthlySaving?.takeIf { it.signum() > 0 }?.let { f.id to it } } +
                sortedLumpy.map { l -> l.id to l.potentialMonthlySaving }
            ).sortedByDescending { it.second }

        val cutCombinations = SpendingReviewConstants.CUT_COMBINATION_TARGETS_EUR.map { target ->
            val targetDec = BigDecimal.valueOf(target.toLong())
            val picked = mutableListOf<String>()
            var acc = BigDecimal.ZERO
            for ((cid, monthly) in cuttablePool) {
                picked += cid
                acc += monthly
                if (acc >= targetDec) break
            }
            val normAcc = Money.normalize(acc)
            CutCombinationDto(
                targetMonthly = target,
                reached = normAcc >= targetDec,
                candidateIds = picked,
                combinedMonthlySaving = normAcc,
                combinedAnnualSaving = Money.normalize(normAcc.multiply(TWELVE)),
            )
        }

        val periodDto = SpendingReviewPeriodDto(
            year = period.year,
            month = period.monthValue,
            reviewKind = if (isPace) "pace_review" else "complete_month",
            windowFrom = windowFrom,
            windowTo = windowTo,
            daysElapsed = daysElapsed,
            daysInMonth = daysInMonth,
            minDaysElapsed = SpendingReviewConstants.AI_REVIEW_MIN_DAYS_ELAPSED,
            paceReviewEligible = paceEligible,
            staleSpendingSince = staleSince?.toString(),
            periodHasNoExpenses = periodHasNoExpenses,
            monthsAvailable = monthsAvailable,
            lastMonthWithData = lastMonthWithDataYm?.toString(),
            reviewBlockedReason = reviewBlockedReason,
        )

        val evaluatedDecisions = decisionService.evaluateAgainst(
            decisions = decodedDecisions,
            recurring = recurringDtos,
            merchants = qualifiedMerchants,
            observedKeysByCategory = observedKeysByCategory,
            asOf = windowTo,
        )

        return SpendingReviewCandidatesResponse(
            period = periodDto,
            candidates = SavingsCandidatesDto(
                recurringCharges = recurringDtos,
                topNonEssentialMerchants = qualifiedMerchants,
                otherMerchants = OtherMerchantsAggregateDto(
                    total = Money.normalize(otherTotal),
                    purchases = otherPurchases,
                    merchantCount = otherMerchantCount,
                ),
                feesAndInterest = FeesAndInterestDto(
                    fees = feeRows,
                    loanInterest = loanInterest,
                ),
                lumpyCategories = sortedLumpy,
                oneOffCategories = sortedOneOffs,
                summary = SavingsCandidatesSummaryDto(
                    nonEssentialRecurringMonthly = nonEssRecMonthly,
                    nonEssentialRecurringAnnual = nonEssRecAnnual,
                    feesMonthly = feesMonthly,
                    feesAnnual = feesAnnual,
                    lumpyTrimMonthly = lumpyMonthly,
                    lumpyTrimAnnual = lumpyAnnual,
                    combinedPotentialMonthly = combinedMonthly,
                    combinedPotentialAnnual = combinedAnnual,
                ),
                cutCombinations = cutCombinations,
            ),
            decisions = evaluatedDecisions,
        )
    }

    /** Pre-computes sanitized top merchant drivers inside each top increase category for the reviewed period. */
    @Transactional(readOnly = true)
    fun moverBreakdowns(
        householdId: UUID,
        period: YearMonth,
        today: LocalDate,
        increaseCategoryCodes: Set<String>,
        recurringCandidates: List<RecurringChargeCandidateDto>,
    ): Map<String, MoverCategoryBreakdown> {
        if (increaseCategoryCodes.isEmpty()) return emptyMap()
        val isPace = period == YearMonth.from(today)
        val window12 = MonthWindow(period.minusMonths(11), period)
        val windowFrom = window12.start.atDay(1)
        val windowTo = if (isPace) today else period.atEndOfMonth()
        val periodStart = period.atDay(1)

        val allTx12 = transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, windowFrom, windowTo)
        val bankMeta = dataQueries.bankMetadataForTransactions(householdId, windowFrom, windowTo)
        val memberTokens = dataQueries.householdMemberTokens(householdId)
        val catMeta = categoryService.listForHousehold(householdId).associate { it.code to (it.group to it.essential) }

        val nonRefund12 = allTx12
            .filter { it.direction == Direction.expense && !it.isRefund && it.amount.signum() > 0 && it.categoryCode in increaseCategoryCodes }
            .map { tx ->
                val link = bankMeta[tx.id]
                val meta = catMeta[tx.categoryCode]
                ExpenseRecord(
                    id = tx.id,
                    occurrenceDate = tx.occurrenceDate,
                    categoryCode = tx.categoryCode,
                    groupCode = meta?.first,
                    essential = meta?.second ?: false,
                    amount = tx.amount,
                    rawLabel = MerchantSanitizer.extractLocalLabel(link?.counterparty, tx.description),
                    bankDescription = link?.description ?: tx.description,
                    recurringTemplateId = tx.recurringTemplateId,
                )
            }

        val recKeysByCat = recurringCandidates.groupBy({ it.categoryCode }) { r ->
            (listOf(r.merchant) + r.mergedVariants).map(PayeeVariantClusterer::canonicalKey)
        }.mapValues { (_, lists) -> lists.flatten().toSet() }

        val splitRules = decisionService.listDecoded(householdId).mapNotNull { it.toSplitRule() }

        return MoverMerchantsBuilder.build(
            nonRefund12m = nonRefund12,
            periodStart = periodStart,
            periodEnd = windowTo,
            increaseCategoryCodes = increaseCategoryCodes,
            recurringKeysByCategory = recKeysByCat,
            memberTokens = memberTokens,
            splitRules = splitRules,
        )
    }

    private fun isFeeCategory(categoryCode: String, groupCode: String?): Boolean =
        categoryCode == "financial.fees" || groupCode == "financial"

    private fun median(values: List<BigDecimal>): BigDecimal {
        if (values.isEmpty()) return BigDecimal.ZERO
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]).divide(BigDecimal.valueOf(2L), 2, RoundingMode.HALF_EVEN)
    }
}
