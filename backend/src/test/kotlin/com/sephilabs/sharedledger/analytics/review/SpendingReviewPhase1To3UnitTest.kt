package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.analytics.MonthDashboardResponse
import com.sephilabs.sharedledger.analytics.MoverRow
import com.sephilabs.sharedledger.analytics.TopMoversResponse
import com.sephilabs.sharedledger.analytics.TrailingPoint
import com.sephilabs.sharedledger.analytics.TrailingResponse
import com.sephilabs.sharedledger.analytics.TrailingSummary
import com.sephilabs.sharedledger.analytics.recurring.DetectedCadence
import com.sephilabs.sharedledger.analytics.recurring.ExpenseRecord
import com.sephilabs.sharedledger.analytics.recurring.PayeeVariantClusterer
import com.sephilabs.sharedledger.analytics.recurring.RecurringAction
import com.sephilabs.sharedledger.analytics.recurring.RecurringChargeDetector
import com.sephilabs.sharedledger.analytics.recurring.RecurringSource
import com.sephilabs.sharedledger.analytics.recurring.YoyChangeKind
import com.sephilabs.sharedledger.catalog.CategoryView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class SpendingReviewPhase1To3UnitTest {

    private val catMeta = mapOf(
        "outings.lottery" to ("outings" to false),
        "outings.subscriptions" to ("outings" to false),
        "outings.hobbies" to ("outings" to false),
        "outings.restaurants" to ("outings" to false),
        "home.utilities" to ("home" to true),
        "home.insurance_fees" to ("home" to true),
        "home.mortgage" to ("home" to true),
    )

    private fun rec(date: LocalDate, cat: String, amount: String, label: String): ExpenseRecord = ExpenseRecord(
        id = UUID.randomUUID(),
        occurrenceDate = date,
        categoryCode = cat,
        groupCode = catMeta[cat]?.first,
        essential = catMeta[cat]?.second ?: false,
        amount = BigDecimal(amount),
        rawLabel = label,
        bankDescription = "SEPA Incasso $label",
        recurringTemplateId = null,
    )

    @Test
    fun `stable subject key matches the same recurring charge across a 12-month replay even as variants evolve`() {
        val history = mutableListOf<ExpenseRecord>()
        // Seed 24 months (Jun 2024 - May 2026) with evolving spelling variants & a mid-year price hike.
        val startYm = YearMonth.of(2024, 6)
        for (i in 0 until 24) {
            val ym = startYm.plusMonths(i.toLong())
            val lotteryLabel = when (i % 4) {
                0 -> "Nat Postcode"
                1 -> "Nat postcode loterij"
                2 -> "Postcode Loterij"
                else -> "Nat. Postcode"
            }
            val lotteryAmt = if (i < 12) "20.50" else "21.00"
            history += rec(ym.atDay(10), "outings.lottery", lotteryAmt, lotteryLabel)

            val utilLabel = if (i % 3 == 0) "Vatenfall" else "Vattenfall"
            history += rec(ym.atDay(8), "home.utilities", "150.00", utilLabel)
        }

        // Record decisions in month 1 of the replay (Jun 2025).
        val month1End = YearMonth.of(2025, 6).atEndOfMonth()
        val detMonth1 = RecurringChargeDetector.detect(
            history24m = history.filter { !it.occurrenceDate.isAfter(month1End) },
            activeTemplates = emptyList(),
            categoryMeta = catMeta,
            trailing12Start = YearMonth.of(2024, 7).atDay(1),
        )
        val lotteryM1 = detMonth1.first { it.categoryCode == "outings.lottery" }
        val utilM1 = detMonth1.first { it.categoryCode == "home.utilities" }

        val lotteryDecision = DecodedDecision(
            id = UUID.randomUUID(),
            subjectKey = PayeeVariantClusterer.stableSubjectKey("recurring", lotteryM1.categoryCode, lotteryM1.memberKeys),
            subjectKind = DecisionSubjectKind.recurring,
            categoryCode = lotteryM1.categoryCode,
            decisionType = SpendingReviewDecisionType.keep,
            displayLabel = lotteryM1.merchant,
            memberKeys = lotteryM1.memberKeys,
            splitGroups = emptyList(),
            amountAtDecision = lotteryM1.monthlyCost,
            snoozeUntil = null,
            decidedByUserId = UUID.randomUUID(),
            decidedAt = Instant.parse("2025-06-30T10:00:00Z"),
        )
        val utilDecision = DecodedDecision(
            id = UUID.randomUUID(),
            subjectKey = PayeeVariantClusterer.stableSubjectKey("recurring", utilM1.categoryCode, utilM1.memberKeys),
            subjectKind = DecisionSubjectKind.recurring,
            categoryCode = utilM1.categoryCode,
            decisionType = SpendingReviewDecisionType.compared,
            displayLabel = utilM1.merchant,
            memberKeys = utilM1.memberKeys,
            splitGroups = emptyList(),
            amountAtDecision = utilM1.monthlyCost,
            snoozeUntil = LocalDate.of(2026, 6, 30),
            decidedByUserId = UUID.randomUUID(),
            decidedAt = Instant.parse("2025-06-30T10:00:00Z"),
        )

        // Replay all 12 consecutive months (Jun 2025 .. May 2026) and assert exact key stability & decision matching.
        val replayStart = YearMonth.of(2025, 6)
        for (offset in 0 until 12) {
            val ym = replayStart.plusMonths(offset.toLong())
            val sliceEnd = ym.atEndOfMonth()
            val sliceStart = ym.minusMonths(23).atDay(1)
            val slice = history.filter { !it.occurrenceDate.isBefore(sliceStart) && !it.occurrenceDate.isAfter(sliceEnd) }
            val detected = RecurringChargeDetector.detect(
                history24m = slice,
                activeTemplates = emptyList(),
                categoryMeta = catMeta,
                trailing12Start = ym.minusMonths(11).atDay(1),
            )
            val lot = detected.first { it.categoryCode == "outings.lottery" }
            val util = detected.first { it.categoryCode == "home.utilities" }

            assertThat(lotteryDecision.matches(DecisionSubjectKind.recurring, lot.categoryCode, lot.memberKeys))
                .describedAs("lottery matched in $ym")
                .isTrue()
            assertThat(utilDecision.matches(DecisionSubjectKind.recurring, util.categoryCode, util.memberKeys))
                .describedAs("utility matched in $ym")
                .isTrue()
            assertThat(PayeeVariantClusterer.stableSubjectKey("recurring", lot.categoryCode, lot.memberKeys))
                .isEqualTo(lotteryDecision.subjectKey)
            assertThat(PayeeVariantClusterer.stableSubjectKey("recurring", util.categoryCode, util.memberKeys))
                .isEqualTo(utilDecision.subjectKey)
        }
    }

    @Test
    fun `split decision keeps groups apart and classifies a later-month new variant to one side or unattached without re-merging`() {
        // Without a split rule, "Nordstream Fiber" and "Nordstream Mobile" merge on distinctive token "nordstream".
        val baseRecords = listOf(
            rec(LocalDate.of(2026, 1, 5), "home.utilities", "45.00", "Nordstream Fiber"),
            rec(LocalDate.of(2026, 2, 5), "home.utilities", "45.00", "Nordstream Fiber"),
            rec(LocalDate.of(2026, 1, 10), "home.utilities", "20.00", "Nordstream Mobile"),
            rec(LocalDate.of(2026, 2, 10), "home.utilities", "20.00", "Nordstream Mobile"),
        )
        val mergedBeforeSplit = PayeeVariantClusterer.clusterByCategory(baseRecords)
        assertThat(mergedBeforeSplit).hasSize(1)

        val splitRule = PayeeVariantClusterer.SplitRule(
            categoryCode = "home.utilities",
            groups = listOf(setOf("nordstream fiber"), setOf("nordstream mobile")),
        )
        val afterSplit = PayeeVariantClusterer.clusterByCategory(baseRecords, listOf(splitRule))
        assertThat(afterSplit).hasSize(2)

        // Later month introduces TWO new variants:
        // 1. "Fiberzone Nord" (shares distinctive token "fiber" only with side A? No, let's use "Fiberplus" on side A
        //    so a new variant "Fiberplus NL" matches ONLY side A, while "Nordstream Combo" matches BOTH sides via "nordstream"!)
        val splitRule2 = PayeeVariantClusterer.SplitRule(
            categoryCode = "home.utilities",
            groups = listOf(setOf("nordstream fiberplus"), setOf("nordstream mobilepro")),
        )
        val laterMonthRecords = listOf(
            rec(LocalDate.of(2026, 1, 5), "home.utilities", "45.00", "Nordstream Fiberplus"),
            rec(LocalDate.of(2026, 2, 5), "home.utilities", "45.00", "Nordstream Fiberplus"),
            rec(LocalDate.of(2026, 1, 10), "home.utilities", "20.00", "Nordstream Mobilepro"),
            rec(LocalDate.of(2026, 2, 10), "home.utilities", "20.00", "Nordstream Mobilepro"),
            // Unambiguous new variant: shares "fiberplus" ONLY with side A -> MUST attach to side A!
            rec(LocalDate.of(2026, 3, 5), "home.utilities", "45.00", "Fiberplus Billing"),
            // Ambiguous bridge variant: shares "nordstream" with BOTH sides -> MUST stay unattached, NEVER re-merge A and B!
            rec(LocalDate.of(2026, 3, 15), "home.utilities", "30.00", "Nordstream Bundle"),
        )

        val clusters = PayeeVariantClusterer.clusterByCategory(laterMonthRecords, listOf(splitRule2))
        assertThat(clusters).hasSize(3)

        val sideA = clusters.first { "nordstream fiberplus" in it.memberKeys }
        val sideB = clusters.first { "nordstream mobilepro" in it.memberKeys }
        val bridge = clusters.first { "nordstream bundle" in it.memberKeys }

        // Side A absorbed its unambiguous variant and stayed strictly separate from Side B
        assertThat(sideA.memberKeys).containsExactlyInAnyOrder("nordstream fiberplus", "fiberplus billing")
        assertThat(sideB.memberKeys).containsExactly("nordstream mobilepro")
        // Bridge variant stayed unattached in its own cluster and did not re-merge Side A and Side B
        assertThat(bridge.memberKeys).containsExactly("nordstream bundle")
    }

    @Test
    fun `compared decision snoozes within 12 months and resurfaces strictly above 5 percent increase`() {
        val d = DecodedDecision(
            id = UUID.randomUUID(),
            subjectKey = "recurring:home.utilities:odido",
            subjectKind = DecisionSubjectKind.recurring,
            categoryCode = "home.utilities",
            decisionType = SpendingReviewDecisionType.compared,
            displayLabel = "Odido",
            memberKeys = setOf("odido"),
            splitGroups = emptyList(),
            amountAtDecision = BigDecimal("100.00"),
            snoozeUntil = LocalDate.of(2027, 5, 1),
            decidedByUserId = UUID.randomUUID(),
            decidedAt = Instant.parse("2026-05-01T10:00:00Z"),
        )

        // Exactly +5.0% stays snoozed; +5.01% resurfaces early.
        assertThat(d.isComparedSnoozed(LocalDate.of(2026, 8, 1), BigDecimal("105.00"))).isTrue()
        assertThat(d.isComparedResurfaced(BigDecimal("105.00"))).isFalse()

        assertThat(d.isComparedResurfaced(BigDecimal("105.01"))).isTrue()
        assertThat(d.isComparedSnoozed(LocalDate.of(2026, 8, 1), BigDecimal("105.01"))).isFalse()

        // After 12 months, snooze expires even without a price rise.
        assertThat(d.isComparedExpired(LocalDate.of(2027, 5, 2))).isTrue()
        assertThat(d.isComparedSnoozed(LocalDate.of(2027, 5, 2), BigDecimal("100.00"))).isFalse()
    }

    @Test
    fun `coming up includes only quarterly and yearly charges within 60 days with no monthly charges and no amount floor`() {
        val asOf = LocalDate.of(2026, 5, 31)
        val dummyTx = TransactionLinkFilterDto(LocalDate.of(2025, 6, 1), asOf, "expense", "home.insurance_fees")
        val dummyExp = ExplorerLinkDto("category", "home.insurance_fees", "custom", LocalDate.of(2025, 6, 1), asOf)

        fun cand(
            id: String,
            merchant: String,
            cat: String,
            action: RecurringAction,
            cadence: DetectedCadence,
            amount: String,
            lastDate: LocalDate,
            kept: Boolean = false,
        ) = RecurringChargeCandidateDto(
            id = id,
            subjectKey = "recurring:$cat:${merchant.lowercase()}",
            memberKeys = listOf(merchant.lowercase()),
            merchant = merchant,
            payloadLabel = merchant,
            anonymized = false,
            categoryCode = cat,
            groupCode = cat.substringBefore('.'),
            essential = action != RecurringAction.cancellable,
            action = action,
            cadence = cadence,
            source = RecurringSource.detected,
            occurrences = 4,
            latestAmount = BigDecimal(amount),
            monthlyCost = BigDecimal(amount),
            annualCost = BigDecimal(amount),
            priorYearAmount = null,
            yoyDeltaAbs = null,
            yoyDeltaPct = null,
            yoyChangeKind = null,
            potentialMonthlySaving = null,
            potentialAnnualSaving = null,
            lastOccurrenceDate = lastDate,
            decision = if (kept) ActiveDecisionBadgeDto(UUID.randomUUID(), SpendingReviewDecisionType.keep, "active", "u@test", Instant.now()) else null,
            txFilter = dummyTx,
            explorerLink = dummyExp,
        )

        val upcoming = UpcomingChargesBuilder.build(
            recurringCharges = listOf(
                // Monthly huge charge -> MUST be excluded (non-monthly only!)
                cand("r_mort", "Florius", "home.mortgage", RecurringAction.negotiable, DetectedCadence.monthly, "1243.88", LocalDate.of(2026, 5, 30)),
                // Weekly charge -> MUST be excluded
                cand("r_week", "Gym", "outings.subscriptions", RecurringAction.cancellable, DetectedCadence.weekly, "15.00", LocalDate.of(2026, 5, 28)),
                // Small quarterly charge due in 15 days -> MUST be included (no amount threshold!)
                cand("r_q_small", "DomainHost", "home.services", RecurringAction.cancellable, DetectedCadence.quarterly, "12.50", LocalDate.of(2026, 3, 15), kept = true),
                // Negotiable yearly insurance due in 40 days -> MUST be included with compareBeforeRenewal = true
                cand("r_y_ins", "AllianzDirect", "home.insurance_fees", RecurringAction.negotiable, DetectedCadence.yearly, "911.95", LocalDate.of(2025, 7, 10)),
                // Yearly charge due in 90 days -> outside 60-day window, MUST be excluded
                cand("r_y_far", "FarClub", "outings.subscriptions", RecurringAction.cancellable, DetectedCadence.yearly, "120.00", LocalDate.of(2025, 9, 1)),
            ),
            asOf = asOf,
        )

        assertThat(upcoming.map { it.merchant }).containsExactly("DomainHost", "AllianzDirect")
        assertThat(upcoming[0].kept).isTrue()
        assertThat(upcoming[0].compareBeforeRenewal).isFalse()
        assertThat(upcoming[0].typicalAmount).isEqualByComparingTo("12.50")
        assertThat(upcoming[1].compareBeforeRenewal).isTrue()
        assertThat(upcoming[1].expectedFrom).isEqualTo(LocalDate.of(2026, 6, 26))
        assertThat(upcoming[1].expectedTo).isEqualTo(LocalDate.of(2026, 7, 24))
    }

    @Test
    fun `since last month diff detects stopped, new, amount changed, and resurfaced or expired compared decisions`() {
        val prevAsOf = LocalDate.of(2026, 4, 30)
        val currAsOf = LocalDate.of(2026, 5, 31)
        val dummyTx = TransactionLinkFilterDto(LocalDate.of(2025, 6, 1), currAsOf, "expense", "outings.lottery")
        val dummyExp = ExplorerLinkDto("category", "outings.lottery", "custom", LocalDate.of(2025, 6, 1), currAsOf)

        fun rc(id: String, key: String, merchant: String, cat: String, monthly: String) = RecurringChargeCandidateDto(
            id = id,
            subjectKey = "recurring:$cat:$key",
            memberKeys = listOf(key),
            merchant = merchant,
            payloadLabel = merchant,
            anonymized = false,
            categoryCode = cat,
            groupCode = cat.substringBefore('.'),
            essential = false,
            action = RecurringAction.cancellable,
            cadence = DetectedCadence.monthly,
            source = RecurringSource.detected,
            occurrences = 6,
            latestAmount = BigDecimal(monthly),
            monthlyCost = BigDecimal(monthly),
            annualCost = BigDecimal(monthly).multiply(BigDecimal("12")),
            priorYearAmount = null,
            yoyDeltaAbs = null,
            yoyDeltaPct = null,
            yoyChangeKind = null,
            potentialMonthlySaving = BigDecimal(monthly),
            potentialAnnualSaving = BigDecimal(monthly).multiply(BigDecimal("12")),
            lastOccurrenceDate = currAsOf,
            txFilter = dummyTx,
            explorerLink = dummyExp,
        )

        fun wrap(list: List<RecurringChargeCandidateDto>) = SavingsCandidatesDto(
            recurringCharges = list,
            topNonEssentialMerchants = emptyList(),
            otherMerchants = OtherMerchantsAggregateDto(BigDecimal.ZERO, 0, 0),
            feesAndInterest = FeesAndInterestDto(emptyList(), null),
            lumpyCategories = emptyList(),
            oneOffCategories = emptyList(),
            summary = SavingsCandidatesSummaryDto(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            ),
            cutCombinations = emptyList(),
        )

        val prev = wrap(
            listOf(
                rc("p1", "nat postcode", "Nat Postcode", "outings.lottery", "21.00"),
                rc("p2", "odido", "Odido", "home.utilities", "55.00"),
                rc("p3", "vitens", "Vitens", "home.utilities", "27.00"),
                rc("p4", "marked not rec", "Marked Not Rec", "outings.hobbies", "40.00"),
            ),
        )
        val curr = wrap(
            listOf(
                rc("c2", "odido", "Odido", "home.utilities", "63.00"), // +14.5% -> amount_changed AND resurfaces compared!
                rc("c3", "vitens", "Vitens", "home.utilities", "27.00"), // snooze expired between Apr 30 and May 31!
                rc("c5", "spotify", "Spotify", "outings.subscriptions", "11.99"), // new!
            ),
        )

        val decisions = listOf(
            DecodedDecision(
                id = UUID.randomUUID(),
                subjectKey = "recurring:home.utilities:odido",
                subjectKind = DecisionSubjectKind.recurring,
                categoryCode = "home.utilities",
                decisionType = SpendingReviewDecisionType.compared,
                displayLabel = "Odido",
                memberKeys = setOf("odido"),
                splitGroups = emptyList(),
                amountAtDecision = BigDecimal("55.00"),
                snoozeUntil = LocalDate.of(2027, 1, 1),
                decidedByUserId = UUID.randomUUID(),
                decidedAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
            DecodedDecision(
                id = UUID.randomUUID(),
                subjectKey = "recurring:home.utilities:vitens",
                subjectKind = DecisionSubjectKind.recurring,
                categoryCode = "home.utilities",
                decisionType = SpendingReviewDecisionType.compared,
                displayLabel = "Vitens",
                memberKeys = setOf("vitens"),
                splitGroups = emptyList(),
                amountAtDecision = BigDecimal("27.00"),
                snoozeUntil = LocalDate.of(2026, 5, 15), // active on Apr 30, expired on May 31!
                decidedByUserId = UUID.randomUUID(),
                decidedAt = Instant.parse("2025-05-15T00:00:00Z"),
            ),
            DecodedDecision(
                id = UUID.randomUUID(),
                subjectKey = "recurring:outings.hobbies:marked not rec",
                subjectKind = DecisionSubjectKind.recurring,
                categoryCode = "outings.hobbies",
                decisionType = SpendingReviewDecisionType.not_recurring,
                displayLabel = "Marked Not Rec",
                memberKeys = setOf("marked not rec"),
                splitGroups = emptyList(),
                amountAtDecision = null,
                snoozeUntil = null,
                decidedByUserId = UUID.randomUUID(),
                decidedAt = Instant.parse("2026-05-10T00:00:00Z"),
            ),
        )

        val diffEn = SinceLastMonthDiff.compute(curr, prev, decisions, currAsOf, prevAsOf, "EUR", "en")
        val diffEs = SinceLastMonthDiff.compute(curr, prev, decisions, currAsOf, prevAsOf, "EUR", "es")

        assertThat(diffEn.map { it.changeType }).containsExactlyInAnyOrder(
            "stopped_recurring",
            "new_recurring",
            "amount_changed",
            "decision_resurfaced",
            "decision_expired",
        )
        // Marked not_recurring MUST NOT appear as "stopped_recurring"
        assertThat(diffEn.none { it.merchant == "Marked Not Rec" }).isTrue()
        assertThat(diffEn.first { it.changeType == "stopped_recurring" }.detail)
            .contains("No longer paying Nat Postcode")
        assertThat(diffEs.first { it.changeType == "stopped_recurring" }.detail)
            .contains("Ya no pagas Nat Postcode")
    }

    @Test
    fun `selector applies keep and snoozed compared before selection, refills slots, routes kept price hike to What changed, and links unlabelled drivers`() {
        val windowFrom = LocalDate.of(2025, 6, 1)
        val windowTo = LocalDate.of(2026, 5, 31)
        fun link(cat: String, q: String? = null) = TransactionLinkFilterDto(windowFrom, windowTo, "expense", cat, null, q)
        fun exp(cat: String) = ExplorerLinkDto("category", cat, "custom", windowFrom, windowTo)

        val keepBadge = ActiveDecisionBadgeDto(UUID.randomUUID(), SpendingReviewDecisionType.keep, "active", "u@test", Instant.now(), BigDecimal("369.60"))
        val snoozedBadge = ActiveDecisionBadgeDto(UUID.randomUUID(), SpendingReviewDecisionType.compared, "snoozed", "u@test", Instant.now(), BigDecimal("63.76"), LocalDate.of(2027, 5, 1))

        val candidates = SavingsCandidatesDto(
            recurringCharges = listOf(
                // Kept negotiable with a price hike -> hidden from Savings, MUST appear in What changed as plain fact!
                RecurringChargeCandidateDto(
                    id = "rec_loonzorg", subjectKey = "recurring:home.insurance_fees:loonzorg", memberKeys = listOf("loonzorg"),
                    merchant = "Loonzorg", payloadLabel = "Loonzorg", anonymized = false,
                    categoryCode = "home.insurance_fees", groupCode = "home", essential = true,
                    action = RecurringAction.negotiable, cadence = DetectedCadence.monthly, source = RecurringSource.detected,
                    occurrences = 12, latestAmount = BigDecimal("369.60"), monthlyCost = BigDecimal("369.60"), annualCost = BigDecimal("4435.20"),
                    priorYearAmount = BigDecimal("356.90"), yoyDeltaAbs = BigDecimal("12.70"), yoyDeltaPct = 3.56,
                    yoyChangeKind = YoyChangeKind.price_increase, potentialMonthlySaving = null, potentialAnnualSaving = null,
                    lastOccurrenceDate = LocalDate.of(2026, 5, 7), decision = keepBadge,
                    txFilter = link("home.insurance_fees", "Loonzorg"), explorerLink = exp("home.insurance_fees"),
                ),
                // Snoozed compared negotiable -> hidden from Savings AND NOT in What changed
                RecurringChargeCandidateDto(
                    id = "rec_odido", subjectKey = "recurring:home.utilities:odido", memberKeys = listOf("odido"),
                    merchant = "Odido", payloadLabel = "Odido", anonymized = false,
                    categoryCode = "home.utilities", groupCode = "home", essential = true,
                    action = RecurringAction.negotiable, cadence = DetectedCadence.monthly, source = RecurringSource.detected,
                    occurrences = 12, latestAmount = BigDecimal("63.76"), monthlyCost = BigDecimal("63.76"), annualCost = BigDecimal("765.12"),
                    priorYearAmount = BigDecimal("55.45"), yoyDeltaAbs = BigDecimal("8.31"), yoyDeltaPct = 15.0,
                    yoyChangeKind = YoyChangeKind.price_increase, potentialMonthlySaving = null, potentialAnnualSaving = null,
                    lastOccurrenceDate = LocalDate.of(2026, 5, 20), decision = snoozedBadge,
                    txFilter = link("home.utilities", "Odido"), explorerLink = exp("home.utilities"),
                ),
                // Next negotiable waiting in queue -> fills freed slot!
                RecurringChargeCandidateDto(
                    id = "rec_allianz", subjectKey = "recurring:home.insurance_fees:allianz", memberKeys = listOf("allianz"),
                    merchant = "Allianz", payloadLabel = "Allianz", anonymized = false,
                    categoryCode = "home.insurance_fees", groupCode = "home", essential = true,
                    action = RecurringAction.negotiable, cadence = DetectedCadence.monthly, source = RecurringSource.detected,
                    occurrences = 12, latestAmount = BigDecimal("50.18"), monthlyCost = BigDecimal("50.18"), annualCost = BigDecimal("602.16"),
                    priorYearAmount = BigDecimal("46.66"), yoyDeltaAbs = BigDecimal("3.52"), yoyDeltaPct = 7.54,
                    yoyChangeKind = YoyChangeKind.price_increase, potentialMonthlySaving = null, potentialAnnualSaving = null,
                    lastOccurrenceDate = LocalDate.of(2026, 5, 15), decision = null,
                    txFilter = link("home.insurance_fees", "Allianz"), explorerLink = exp("home.insurance_fees"),
                ),
            ),
            topNonEssentialMerchants = emptyList(),
            otherMerchants = OtherMerchantsAggregateDto(BigDecimal.ZERO, 0, 0),
            feesAndInterest = FeesAndInterestDto(emptyList(), null),
            lumpyCategories = emptyList(),
            oneOffCategories = emptyList(),
            summary = SavingsCandidatesSummaryDto(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            ),
            cutCombinations = emptyList(),
        )

        val selected = SpendingReviewSelector.select(
            SpendingReviewSelector.Inputs(
                currency = "EUR",
                mode = ReviewMode.FULL,
                candidatesResponse = SpendingReviewCandidatesResponse(
                    period = SpendingReviewPeriodDto(2026, 5, "complete_month", windowFrom, windowTo, 31, 31, 7, true, null, false, 12),
                    candidates = candidates,
                ),
                monthDashboard = MonthDashboardResponse(
                    2026, 5, BigDecimal("4500.00"), BigDecimal("3800.00"), BigDecimal("700.00"), 15.5, emptyList(),
                ),
                trailing12 = TrailingResponse(
                    points = (1..12).map { m -> TrailingPoint(2025, m, BigDecimal("4500.00"), BigDecimal("3800.00"), BigDecimal("700.00"), 15.5) },
                    summary = TrailingSummary(BigDecimal("4500.00"), BigDecimal("3800.00"), BigDecimal("700.00"), BigDecimal("700.00")),
                ),
                moversVsTrailing6 = TopMoversResponse(
                    2026, 5, "trailing6_avg",
                    increases = listOf(MoverRow("outings.restaurants", "outings", BigDecimal("1386.45"), BigDecimal("131.44"), BigDecimal("1255.01"), 954.8)),
                    decreases = emptyList(), newActivity = emptyList(),
                    totalIncrease = BigDecimal("1255.01"), totalDecrease = BigDecimal.ZERO,
                ),
                moversVsYearAgo = TopMoversResponse(2026, 5, "year_ago", emptyList(), emptyList(), emptyList(), BigDecimal.ZERO, BigDecimal.ZERO),
                moverBreakdowns = mapOf(
                    "outings.restaurants" to MoverCategoryBreakdown(
                        categoryCode = "outings.restaurants",
                        periodTotal = BigDecimal("1386.45"),
                        periodPurchases = 5,
                        topMerchants = listOf(
                            MoverMerchantDriver("Unlabelled purchase in outings.restaurants", true, BigDecimal("1111.00"), 1, 80.13),
                        ),
                        topMerchantsSummary = null,
                        otherMerchants = null,
                    ),
                ),
                budgetSummary = null,
                paceProjection = null,
                categories = listOf(
                    CategoryView("home.insurance_fees", "Insurance", "expense", "home", 1, true, false),
                    CategoryView("home.utilities", "Utilities", "expense", "home", 2, true, false),
                    CategoryView("outings.restaurants", "Restaurants", "expense", "outings", 3, false, false),
                ),
            ),
        )

        // Freed Savings slot filled by Allianz; Loonzorg (keep) and Odido (snoozed) excluded from Savings
        assertThat(selected.savings.map { it.localSubject }).containsExactly("Allianz")

        // Kept Loonzorg price hike moved to What changed as a plain fact (non_actionable_price_change)
        assertThat(selected.whatChanged).anySatisfy {
            assertThat(it.kind).isEqualTo(FindingKind.non_actionable_price_change)
            assertThat(it.localSubject).isEqualTo("Loonzorg")
        }
        assertThat(selected.whatChanged.none { it.localSubject == "Odido" }).isTrue()

        // Unlabelled driver links directly to unlabelled=true
        val restInc = selected.whatChanged.first { it.kind == FindingKind.period_increase }
        assertThat(restInc.txFilter?.unlabelled).isTrue()
    }
}
