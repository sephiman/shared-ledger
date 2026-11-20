package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.analytics.MonthDashboardResponse
import com.sephilabs.sharedledger.analytics.MoverRow
import com.sephilabs.sharedledger.analytics.TopMoversResponse
import com.sephilabs.sharedledger.analytics.TrailingPoint
import com.sephilabs.sharedledger.analytics.TrailingResponse
import com.sephilabs.sharedledger.analytics.TrailingSummary
import com.sephilabs.sharedledger.analytics.question.AdjustmentKind
import com.sephilabs.sharedledger.analytics.question.ScopeType
import com.sephilabs.sharedledger.budget.MonthSummaryResponse
import com.sephilabs.sharedledger.budget.MonthSummaryRow
import com.sephilabs.sharedledger.catalog.CategoryView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate

class AnalyticsAiReviewVerifierTest {

    private val mapper = JsonMapper.builder().build()

    private val categories = listOf(
        CategoryView("home.rent", "Rent", "expense", "home", 1, true, false),
        CategoryView("home.utilities", "Utilities", "expense", "home", 2, true, false),
        CategoryView("home.taxes", "Taxes", "expense", "home", 3, true, false),
        CategoryView("financial.fees", "Fees", "expense", "financial", 4, true, false),
        CategoryView("outings.subscriptions", "Subscriptions", "expense", "outings", 5, false, false),
        CategoryView("outings.lottery", "Lottery", "expense", "outings", 6, false, false),
        CategoryView("outings.hobbies", "Hobbies", "expense", "outings", 7, false, false),
        CategoryView("outings.restaurants", "Restaurants", "expense", "outings", 8, false, false),
        CategoryView("outings.travel", "Travel", "expense", "outings", 9, false, false),
        CategoryView("transport.car_maintenance", "Car maintenance", "expense", "transport", 10, false, false),
        CategoryView("groceries.groceries", "Groceries", "expense", "groceries", 11, true, false),
        CategoryView("income.salary", "Salary", "income", "income", 12, false, false),
    )

    private val catLabelsEn: (String) -> String = { code -> categories.firstOrNull { it.code == code }?.name ?: code }
    private val catLabelsEs: (String) -> String = { code ->
        mapOf(
            "home.utilities" to "Suministros",
            "home.taxes" to "Impuestos",
            "financial.fees" to "Comisiones bancarias",
            "outings.subscriptions" to "Suscripciones",
            "outings.lottery" to "Lotería",
            "outings.hobbies" to "Aficiones",
            "outings.restaurants" to "Restaurantes y bares",
            "outings.travel" to "Viajes",
            "transport.car_maintenance" to "Mantenimiento coche",
            "groceries.groceries" to "Supermercado",
            "income.salary" to "Salario",
        )[code] ?: code
    }

    private fun sampleInputs(): SpendingReviewSelector.Inputs {
        val from = LocalDate.of(2025, 6, 1)
        val to = LocalDate.of(2026, 5, 31)
        val link: (String, String?) -> TransactionLinkFilterDto = { cat, q ->
            TransactionLinkFilterDto(from, to, "expense", cat, null, q)
        }
        val expLink: (String) -> ExplorerLinkDto = { cat ->
            ExplorerLinkDto("category", cat, "custom", from, to)
        }

        val candidates = SavingsCandidatesDto(
            recurringCharges = listOf(
                RecurringChargeCandidateDto(
                    id = "rec_lot", merchant = "Nat Postcode", mergedVariants = listOf("Nat Postcode", "Postcode Loterij"),
                    payloadLabel = "Nat Postcode", anonymized = false, categoryCode = "outings.lottery", groupCode = "outings",
                    essential = false, action = RecurringAction.cancellable, cadence = DetectedCadence.monthly, source = RecurringSource.detected,
                    occurrences = 27, latestAmount = BigDecimal("21.00"), monthlyCost = BigDecimal("21.00"), annualCost = BigDecimal("252.00"),
                    priorYearAmount = BigDecimal("20.50"), yoyDeltaAbs = BigDecimal("0.50"), yoyDeltaPct = 2.44,
                    yoyChangeKind = YoyChangeKind.price_increase, potentialMonthlySaving = BigDecimal("21.00"),
                    potentialAnnualSaving = BigDecimal("252.00"), txFilter = link("outings.lottery", "Nat Postcode"), explorerLink = expLink("outings.lottery"),
                ),
                RecurringChargeCandidateDto(
                    id = "rec_odido", merchant = "Odido", mergedVariants = listOf("Odido", "Odido Internet"),
                    payloadLabel = "Odido", anonymized = false, categoryCode = "home.utilities", groupCode = "home",
                    essential = true, action = RecurringAction.negotiable, cadence = DetectedCadence.monthly, source = RecurringSource.detected,
                    occurrences = 24, latestAmount = BigDecimal("63.76"), monthlyCost = BigDecimal("63.76"), annualCost = BigDecimal("765.12"),
                    priorYearAmount = BigDecimal("55.45"), yoyDeltaAbs = BigDecimal("8.31"), yoyDeltaPct = 14.99,
                    yoyChangeKind = YoyChangeKind.price_increase, potentialMonthlySaving = null, potentialAnnualSaving = null,
                    txFilter = link("home.utilities", "Odido"), explorerLink = expLink("home.utilities"),
                ),
                RecurringChargeCandidateDto(
                    id = "rec_tax", merchant = "BelastingDienst", mergedVariants = emptyList(),
                    payloadLabel = "BelastingDienst", anonymized = false, categoryCode = "home.taxes", groupCode = "home",
                    essential = true, action = RecurringAction.none, cadence = DetectedCadence.monthly, source = RecurringSource.detected,
                    occurrences = 20, latestAmount = BigDecimal("85.00"), monthlyCost = BigDecimal("85.00"), annualCost = BigDecimal("1020.00"),
                    priorYearAmount = BigDecimal("62.00"), yoyDeltaAbs = BigDecimal("23.00"), yoyDeltaPct = 37.10,
                    yoyChangeKind = YoyChangeKind.price_increase, potentialMonthlySaving = null, potentialAnnualSaving = null,
                    txFilter = link("home.taxes", "BelastingDienst"), explorerLink = expLink("home.taxes"),
                ),
            ),
            topNonEssentialMerchants = listOf(
                TopMerchantCandidateDto(
                    id = "merch_frost", merchant = "Frosthaven", payloadLabel = "Frosthaven", anonymized = false,
                    categoryCode = "outings.hobbies", groupCode = "outings", essential = false,
                    total = BigDecimal("1001.59"), purchases = 16, averageTicket = BigDecimal("62.60"),
                    shareOfCategoryPct = 28.39, monthlyEquivalent = BigDecimal("83.47"),
                    potentialMonthlySaving = null, potentialAnnualSaving = null,
                    txFilter = link("outings.hobbies", "Frosthaven"), explorerLink = expLink("outings.hobbies"),
                ),
                // Zero-median category merchant MUST be excluded from Savings even if passed in candidates
                TopMerchantCandidateDto(
                    id = "merch_japon", merchant = "Japón", payloadLabel = "Japón", anonymized = false,
                    categoryCode = "outings.travel", groupCode = "outings", essential = false,
                    total = BigDecimal("8956.34"), purchases = 3, averageTicket = BigDecimal("2985.45"),
                    shareOfCategoryPct = 99.0, monthlyEquivalent = BigDecimal("746.36"),
                    potentialMonthlySaving = null, potentialAnnualSaving = null,
                    txFilter = link("outings.travel", "Japón"), explorerLink = expLink("outings.travel"),
                ),
            ),
            otherMerchants = OtherMerchantsAggregateDto(BigDecimal("120.00"), 5, 4),
            feesAndInterest = FeesAndInterestDto(
                fees = listOf(
                    FeeCandidateDto(
                        id = "fee_financial_fees", categoryCode = "financial.fees", groupCode = "financial", essential = true,
                        total12m = BigDecimal("144.00"), monthlyAverage = BigDecimal("12.00"), annualCost = BigDecimal("144.00"),
                        occurrences = 12, potentialMonthlySaving = BigDecimal("12.00"), potentialAnnualSaving = BigDecimal("144.00"),
                        txFilter = link("financial.fees", null), explorerLink = expLink("financial.fees"),
                    ),
                ),
                loanInterest = null,
            ),
            lumpyCategories = listOf(
                LumpyCategoryCandidateDto(
                    id = "habitual_outings_restaurants", categoryCode = "outings.restaurants", groupCode = "outings", essential = false,
                    monthlyMean = BigDecimal("223.81"), monthlyMedian = BigDecimal("88.44"),
                    trimToMedianMonthly = BigDecimal("135.37"), trimToMedianAnnual = BigDecimal("1624.44"),
                    potentialMonthlySaving = BigDecimal("135.37"), potentialAnnualSaving = BigDecimal("1624.44"),
                    nonZeroMonths = 11, peakMonthAmount = BigDecimal("1386.45"),
                    txFilter = link("outings.restaurants", null), explorerLink = expLink("outings.restaurants"),
                ),
            ),
            oneOffCategories = listOf(
                LumpyCategoryCandidateDto(
                    id = "oneoff_outings_travel", categoryCode = "outings.travel", groupCode = "outings", essential = false,
                    monthlyMean = BigDecimal("746.36"), monthlyMedian = BigDecimal.ZERO,
                    trimToMedianMonthly = BigDecimal("746.36"), trimToMedianAnnual = BigDecimal("8956.32"),
                    potentialMonthlySaving = BigDecimal.ZERO, potentialAnnualSaving = BigDecimal.ZERO,
                    nonZeroMonths = 3, peakMonthAmount = BigDecimal("6144.60"),
                    txFilter = link("outings.travel", null), explorerLink = expLink("outings.travel"),
                ),
            ),
            summary = SavingsCandidatesSummaryDto(
                BigDecimal("21.00"), BigDecimal("252.00"), BigDecimal("12.00"), BigDecimal("144.00"),
                BigDecimal("135.37"), BigDecimal("1624.44"), BigDecimal("168.37"), BigDecimal("2020.44"),
            ),
            cutCombinations = emptyList(),
        )

        return SpendingReviewSelector.Inputs(
            currency = "EUR",
            mode = ReviewMode.FULL,
            candidatesResponse = SpendingReviewCandidatesResponse(
                period = SpendingReviewPeriodDto(
                    year = 2026, month = 5, reviewKind = "complete_month",
                    windowFrom = from, windowTo = to, daysElapsed = 31, daysInMonth = 31,
                    minDaysElapsed = 7, paceReviewEligible = true, staleSpendingSince = null,
                    periodHasNoExpenses = false, monthsAvailable = 12, lastMonthWithData = "2026-05",
                    reviewBlockedReason = null,
                ),
                candidates = candidates,
            ),
            monthDashboard = MonthDashboardResponse(
                year = 2026, month = 5,
                income = BigDecimal("4446.64"), expenses = BigDecimal("3832.48"),
                savings = BigDecimal("614.16"), savingsRate = 13.81,
                byGroup = emptyList(),
            ),
            trailing12 = TrailingResponse(
                points = emptyList(),
                summary = TrailingSummary(
                    avgIncome = BigDecimal("7668.21"), avgExpenses = BigDecimal("4700.52"),
                    avgNetSavings = BigDecimal("2967.69"), medianNetSavings = BigDecimal("2500.00"),
                ),
            ),
            moversVsTrailing6 = TopMoversResponse(
                year = 2026, month = 5, baseline = "trailing6_avg",
                increases = listOf(
                    MoverRow("outings.restaurants", "outings", BigDecimal("1386.45"), BigDecimal("131.44"), BigDecimal("1255.01"), 954.82),
                ),
                decreases = emptyList(),
                newActivity = emptyList(),
                totalIncrease = BigDecimal("1255.01"), totalDecrease = BigDecimal.ZERO,
            ),
            moversVsYearAgo = TopMoversResponse(
                year = 2026, month = 5, baseline = "year_ago",
                increases = listOf(
                    MoverRow("groceries.groceries", "groceries", BigDecimal("595.19"), BigDecimal("351.31"), BigDecimal("243.88"), 69.42),
                ),
                decreases = emptyList(),
                newActivity = emptyList(),
                totalIncrease = BigDecimal("243.88"), totalDecrease = BigDecimal.ZERO,
            ),
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
                "groceries.groceries" to MoverCategoryBreakdown(
                    categoryCode = "groceries.groceries",
                    periodTotal = BigDecimal("595.19"),
                    periodPurchases = 13,
                    topMerchants = listOf(
                        MoverMerchantDriver("AH", false, BigDecimal("318.01"), 8, 53.43),
                    ),
                    topMerchantsSummary = null,
                    otherMerchants = null,
                ),
            ),
            budgetSummary = MonthSummaryResponse(
                year = 2026, month = 5, daysElapsed = 31, daysInMonth = 31,
                rows = listOf(
                    MonthSummaryRow("transport.car_maintenance", BigDecimal("50.00"), BigDecimal("533.00"), BigDecimal("50.00"), BigDecimal("533.00"), 1066.0),
                    MonthSummaryRow("groceries.groceries", BigDecimal("700.00"), BigDecimal("595.19"), BigDecimal("700.00"), BigDecimal("595.19"), 85.0),
                ),
            ),
            paceProjection = null,
            categories = categories,
        )
    }

    @Test
    fun `selection is completely deterministic across consecutive runs and enforces priority, zero-median exclusion, actionability and delta scenarios`() {
        val inputs = sampleInputs()
        val first = SpendingReviewSelector.select(inputs)
        val second = SpendingReviewSelector.select(inputs)

        assertThat(first).isEqualTo(second)

        // Summary always carries all 4 pairs vs trailing-12m norm
        val summaryRoles = first.summaryFacts!!.figures.map { it.role }
        assertThat(summaryRoles).contains(
            "periodExpenses", "normExpenses",
            "periodIncome", "normIncome",
            "periodNetSavings", "normNetSavings",
            "periodSavingsRatePct", "normSavingsRatePct",
        )

        // Savings strict order: cancellable -> fee -> negotiable price_increase -> merchant (no zero-median!) -> habitual (median > 0)
        assertThat(first.savings.map { it.id }).containsExactly(
            "sav_rec_lot",
            "sav_fee_financial_fees",
            "sav_rec_odido",
            "sav_merch_frost",
            "sav_habitual_outings_restaurants",
        )
        // Zero-median category (outings.travel, including merchant Japón) NEVER appears in Savings
        assertThat(first.savings.mapNotNull { it.categoryCode }).doesNotContain("outings.travel")
        // Tax (action=none) NEVER appears in Savings
        assertThat(first.savings.mapNotNull { it.categoryCode }).doesNotContain("home.taxes")

        // Cancellable recurring scenario is a delta (-monthlyCost) on its category, NEVER freezing the whole category
        val lotScenario = first.savings.first { it.id == "sav_rec_lot" }.simulationScenario!!
        assertThat(lotScenario.freeze).isEmpty()
        assertThat(lotScenario.adjustments).hasSize(1)
        assertThat(lotScenario.adjustments[0].scope.type).isEqualTo(ScopeType.category)
        assertThat(lotScenario.adjustments[0].scope.code).isEqualTo("outings.lottery")
        assertThat(lotScenario.adjustments[0].kind).isEqualTo(AdjustmentKind.amount)
        assertThat(lotScenario.adjustments[0].value).isEqualByComparingTo(BigDecimal("-21.00"))

        // Negotiable essential utility carries NO cut scenario
        assertThat(first.savings.first { it.id == "sav_rec_odido" }.simulationScenario).isNull()

        // What changed: top increases (with merchant breakdown), action==none price changes, zero-median one-offs
        assertThat(first.whatChanged.map { it.id }).containsExactly(
            "chg_inc_outings_restaurants",
            "chg_inc_groceries_groceries",
            "chg_rec_tax",
            "chg_oneoff_outings_travel",
        )
        // Restaurant increase carries the €1,111 / 80.13% unlabelled purchase driver
        val restInc = first.whatChanged.first { it.id == "chg_inc_outings_restaurants" }
        assertThat(restInc.context["topDriverKind"]).isEqualTo("unlabelled")
        assertThat(restInc.figures.first { it.role == "topDriverTotal" }.value).isEqualByComparingTo(BigDecimal("1111.00"))

        // Budgets: ONLY overruns (car maintenance over; groceries at 85% excluded), single ratio expression (10.7x)
        assertThat(first.budgets.map { it.id }).containsExactly("bud_transport_car_maintenance")
        val bud = first.budgets.single()
        assertThat(bud.context["overrunFormat"]).isEqualTo("multiple")
        assertThat(bud.figures.first { it.key == "f4" }.kind).isEqualTo(FigureKind.MULTIPLE)
        assertThat(bud.figures.first { it.key == "f4" }.value).isEqualByComparingTo(BigDecimal("10.7"))
    }

    @Test
    fun `two different fake model outputs produce reports with identical findings, order and figures, falling back on omissions, raw digits and banned terms`() {
        val selected = SpendingReviewSelector.select(sampleInputs())

        val modelA = mapper.readTree(
            """
            {
              "summary": "May closed with {f1} spent ({f3} under your {f2} average) while income was {f4} ({f6} under {f5}). Net savings reached {f7} versus {f8}, or {f9} against {f10}. Because spending stayed below average, the savings shortfall comes from lower income.",
              "findings": [
                { "id": "sav_rec_lot", "title": "Lottery subscription", "detail": "Nat Postcode runs {f1} a month ({f2} a year), up {f4} from {f3}." },
                { "id": "sav_fee_financial_fees", "title": "Account fees", "detail": "Fees added up to {f1} across {f3} charges ({f2} monthly)." },
                { "id": "sav_rec_odido", "title": "Odido tariff rose", "detail": "Odido went up {f3} from {f2} to {f1} monthly ({f4} yearly); worth comparing." },
                { "id": "sav_merch_frost", "title": "Hobbies spend at Frosthaven", "detail": "Frosthaven took {f1} across {f2} purchases ({f3} monthly, {f4} of the category)." },
                { "id": "sav_habitual_outings_restaurants", "title": "Restaurants above typical month", "detail": "Restaurants averaged {f1} across {f5} active months vs a typical {f2}, leaving {f3} monthly ({f4} yearly), with {f7} from a single {f6} peak month." },
                { "id": "chg_inc_outings_restaurants", "title": "Restaurants jumped on one purchase", "detail": "Restaurants hit {f1} ({f2} above the six-month average of {f3}) across {f4} purchases, led by {f7} unlabelled purchase of {f5} ({f6})." },
                { "id": "chg_inc_groceries_groceries", "title": "Groceries up year on year", "detail": "Groceries reached {f1} ({f2} above last year's {f3}) across {f4} purchases, with {f7} at AH totalling {f5} ({f6})." },
                { "id": "chg_rec_tax", "title": "Tax bill increased", "detail": "BelastingDienst rose {f3} from {f2} to {f1} a month ({f4} yearly)." },
                { "id": "chg_oneoff_outings_travel", "title": "Occasional travel spending", "detail": "Travel totalled {f1} over {f2} active months, peaking at {f3}." },
                { "id": "bud_transport_car_maintenance", "title": "Car maintenance exceeded budget", "detail": "Car maintenance reached {f1} against a {f2} budget, {f3} over ({f4})." }
              ]
            }
            """.trimIndent(),
        )

        // Model B: different wording + raw digit in title + banned term "lumpy" + raw digit in detail + omits budget finding + reorders items + adds bogus extra id
        val modelB = mapper.readTree(
            """
            {
              "summary": "May 2026 had {f1} expenses vs {f2}, income {f4} vs {f5}, saved {f7} vs {f8} ({f9} vs {f10}).",
              "findings": [
                { "id": "bogus_extra_id", "title": "Invented finding", "detail": "Should never appear." },
                { "id": "chg_rec_tax", "title": "Tax rose 37%", "detail": "BelastingDienst rose {f3} from {f2} to {f1}." },
                { "id": "sav_habitual_outings_restaurants", "title": "Lumpy restaurant spend", "detail": "Restaurants are lumpy with {f3} trimming potential." },
                { "id": "sav_rec_lot", "title": "Lottery cost", "detail": "Nat Postcode costs €21 a month ({f2} yearly)." },
                { "id": "sav_rec_odido", "title": "Odido comparison", "detail": "Odido increased {f3}, from {f2} to {f1} monthly ({f4} a year); worth comparing options." }
              ]
            }
            """.trimIndent(),
        )

        val reportA = SpendingReviewRenderer.renderWithModel(selected, modelA, "en", catLabelsEn)
        val reportB = SpendingReviewRenderer.renderWithModel(selected, modelB, "en", catLabelsEn)

        // Model A: 100% model-written (summary + all 10 findings)
        assertThat(reportA.summaryUsedModel).isTrue()
        assertThat(reportA.templateFallbackCount).isEqualTo(0)
        assertThat(reportA.modelWrittenCount).isEqualTo(11)

        // Model B: summary fell back (raw "2026"), sav_rec_odido used model, others fell back with exact reasons
        assertThat(reportB.summaryUsedModel).isFalse()
        assertThat(reportB.summaryFallbackReason).isEqualTo("raw_number")
        assertThat(reportB.findings.first { it.findingId == "sav_rec_odido" }.usedModel).isTrue()
        assertThat(reportB.findings.first { it.findingId == "chg_rec_tax" }.fallbackReason).isEqualTo("raw_number_in_title")
        assertThat(reportB.findings.first { it.findingId == "sav_habitual_outings_restaurants" }.fallbackReason).startsWith("banned_term:")
        assertThat(reportB.findings.first { it.findingId == "sav_rec_lot" }.fallbackReason).isEqualTo("raw_number")
        assertThat(reportB.findings.first { it.findingId == "bud_transport_car_maintenance" }.fallbackReason).isEqualTo("omitted_by_model")

        // IDENTICAL findings, IDENTICAL order, and IDENTICAL formatted figures across both reports!
        assertThat(reportA.findings.map { it.findingId }).isEqualTo(reportB.findings.map { it.findingId })
        assertThat(reportB.findings.map { it.findingId }).doesNotContain("bogus_extra_id")

        // Every rendered finding in both reports contains its exact app-formatted core figures
        for (f in selected.allFindings) {
            val aText = reportA.findings.first { it.findingId == f.id }.detail
            val bText = reportB.findings.first { it.findingId == f.id }.detail
            val formattedF1 = SpendingReviewRenderer.formatFigure(f.figures.first { it.key == "f1" }, "EUR", "en")
            assertThat(aText).contains(formattedF1)
            assertThat(bText).contains(formattedF1)
        }
        // Summary in both reports contains income vs norm (€4,446.64 and €7,668.21) and expenses vs norm (€3,832.48 and €4,700.52)
        assertThat(reportA.summary).contains("€4,446.64").contains("€7,668.21").contains("€3,832.48").contains("€4,700.52")
        assertThat(reportB.summary).contains("€4,446.64").contains("€7,668.21").contains("€3,832.48").contains("€4,700.52")
    }

    @Test
    fun `every deterministic template in EN and ES passes banned-terms, no-raw-digits and Spanish tu-register checks`() {
        val selected = SpendingReviewSelector.select(sampleInputs())
        val forbiddenEsRegister = Regex("""(?i)\b(vosotros|vuestro|vuestra|vuestros|vuestras|tenéis|podéis|gastáis|ahorráis|nosotros|nuestro|nuestra|nuestros|nuestras)\b""")

        for ((locale, labelFn) in listOf("en" to catLabelsEn, "es" to catLabelsEs)) {
            val sf = selected.summaryFacts!!
            val sumTpl = SpendingReviewRenderer.summaryTemplate(sf, locale)
            val sumErr = SpendingReviewRenderer.validatePlaceholderProse(
                sumTpl,
                sf.figures.map { it.key }.toSet(),
                setOf("f1", "f2", "f4", "f5", "f7", "f8", "f9", "f10"),
            )
            assertThat(sumErr).describedAs("summary template ($locale): $sumTpl").isNull()
            if (locale == "es") {
                assertThat(forbiddenEsRegister.containsMatchIn(sumTpl)).describedAs("ES summary register: $sumTpl").isFalse()
            }

            for (f in selected.allFindings) {
                val (title, detail) = SpendingReviewRenderer.findingTemplate(f, locale, labelFn, useLocalName = true)
                assertThat(SpendingReviewRenderer.validateTitle(title))
                    .describedAs("title template ($locale/${f.kind}): $title")
                    .isNull()
                val detailErr = SpendingReviewRenderer.validatePlaceholderProse(
                    detail,
                    f.figures.map { it.key }.toSet(),
                    setOf("f1"),
                )
                assertThat(detailErr)
                    .describedAs("detail template ($locale/${f.kind}): $detail")
                    .isNull()
                if (locale == "es") {
                    assertThat(forbiddenEsRegister.containsMatchIn("$title $detail"))
                        .describedAs("ES finding register (${f.kind}): $title | $detail")
                        .isFalse()
                }
            }
        }
    }

    @Test
    fun `follow-up grounding verifier accepts displayed-precision rounding only and rejects invented numbers, and sanitizeScenario blocks essential cuts`() {
        val payload = mapper.readTree(
            """
            {
              "findings": [
                {
                  "id": "sav_rec_1",
                  "figures": {
                    "f1": { "role": "monthlyCost", "kind": "money", "value": 369.6 },
                    "f3": { "role": "yoyDeltaPct", "kind": "percent", "value": 3.56 }
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        val okCites = mapper.readTree(
            """[
              {"path": "findings.0.figures.f1.value", "value": 370},
              {"path": "findings.0.figures.f3.value", "value": 3.6}
            ]""",
        )
        val okRes = AnalyticsAiReviewVerifier.verifyFollowupCitations(payload, okCites)
        assertThat(okRes.total).isEqualTo(2)
        assertThat(okRes.ungrounded).isEqualTo(0)

        val badCites = mapper.readTree(
            """[
              {"path": "findings.0.figures.f1.value", "value": 999},
              {"path": "findings.0.figures.f3.value", "value": 3.5}
            ]""",
        )
        val badRes = AnalyticsAiReviewVerifier.verifyFollowupCitations(payload, badCites)
        assertThat(badRes.ungrounded).isEqualTo(2)

        val catalogue = categories.map {
            com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry(
                it.code, it.code, it.group, com.sephilabs.sharedledger.transaction.Direction.valueOf(it.kind), it.essential,
            )
        }
        val catByCode = categories.associateBy { it.code }
        val essentialCut = mapper.readTree(
            """{"freeze":[],"adjustments":[{"scope":{"type":"category","code":"home.utilities"},"kind":"amount","value":-50}],"level":"category"}""",
        )
        val feeCut = mapper.readTree(
            """{"freeze":[],"adjustments":[{"scope":{"type":"category","code":"financial.fees"},"kind":"amount","value":-12}],"level":"category"}""",
        )
        assertThat(AnalyticsAiReviewVerifier.sanitizeScenario(essentialCut, catalogue, catByCode)).isNull()
        assertThat(AnalyticsAiReviewVerifier.sanitizeScenario(feeCut, catalogue, catByCode)).isNotNull()
    }

    @Test
    fun `excludes cancellable charges and fees below SAVINGS_MIN_ANNUAL_EUR from top savings, counts them in savingsOmitted, and frees slots for merchants and habitual categories`() {
        val base = sampleInputs()
        val from = base.candidatesResponse.period.windowFrom
        val to = base.candidatesResponse.period.windowTo
        val link: (String, String?) -> TransactionLinkFilterDto = { cat, q ->
            TransactionLinkFilterDto(from, to, "expense", cat, null, q)
        }
        val expLink: (String) -> ExplorerLinkDto = { cat ->
            ExplorerLinkDto("category", cat, "custom", from, to)
        }

        val smallAmazon = RecurringChargeCandidateDto(
            id = "rec_amazon", merchant = "Amazon", mergedVariants = emptyList(),
            payloadLabel = "Amazon", anonymized = false, categoryCode = "outings.subscriptions", groupCode = "outings",
            essential = false, action = RecurringAction.cancellable, cadence = DetectedCadence.monthly, source = RecurringSource.detected,
            occurrences = 12, latestAmount = BigDecimal("4.99"), monthlyCost = BigDecimal("4.99"), annualCost = BigDecimal("59.88"),
            priorYearAmount = BigDecimal("4.99"), yoyDeltaAbs = BigDecimal.ZERO, yoyDeltaPct = 0.0,
            yoyChangeKind = null, potentialMonthlySaving = BigDecimal("4.99"),
            potentialAnnualSaving = BigDecimal("59.88"), txFilter = link("outings.subscriptions", "Amazon"), explorerLink = expLink("outings.subscriptions"),
        )
        val secondBigLottery = RecurringChargeCandidateDto(
            id = "rec_vrienden", merchant = "Vriendenloterij", mergedVariants = emptyList(),
            payloadLabel = "Vriendenloterij", anonymized = false, categoryCode = "outings.lottery", groupCode = "outings",
            essential = false, action = RecurringAction.cancellable, cadence = DetectedCadence.monthly, source = RecurringSource.detected,
            occurrences = 12, latestAmount = BigDecimal("16.00"), monthlyCost = BigDecimal("16.00"), annualCost = BigDecimal("192.00"),
            priorYearAmount = BigDecimal("15.50"), yoyDeltaAbs = BigDecimal("0.50"), yoyDeltaPct = 3.23,
            yoyChangeKind = YoyChangeKind.changed, potentialMonthlySaving = BigDecimal("16.00"),
            potentialAnnualSaving = BigDecimal("192.00"), txFilter = link("outings.lottery", "Vriendenloterij"), explorerLink = expLink("outings.lottery"),
        )
        val secondNegotiable = RecurringChargeCandidateDto(
            id = "rec_loonzorg", merchant = "Loonzorg", mergedVariants = emptyList(),
            payloadLabel = "Loonzorg", anonymized = false, categoryCode = "home.utilities", groupCode = "home",
            essential = true, action = RecurringAction.negotiable, cadence = DetectedCadence.monthly, source = RecurringSource.detected,
            occurrences = 24, latestAmount = BigDecimal("369.60"), monthlyCost = BigDecimal("369.60"), annualCost = BigDecimal("4435.20"),
            priorYearAmount = BigDecimal("356.90"), yoyDeltaAbs = BigDecimal("12.70"), yoyDeltaPct = 3.56,
            yoyChangeKind = YoyChangeKind.price_increase, potentialMonthlySaving = null, potentialAnnualSaving = null,
            txFilter = link("home.utilities", "Loonzorg"), explorerLink = expLink("home.utilities"),
        )
        val smallFee = FeeCandidateDto(
            id = "fee_financial_fees", categoryCode = "financial.fees", groupCode = "financial", essential = true,
            total12m = BigDecimal("25.00"), monthlyAverage = BigDecimal("2.08"), annualCost = BigDecimal("25.00"),
            occurrences = 12, potentialMonthlySaving = BigDecimal("2.08"), potentialAnnualSaving = BigDecimal("25.00"),
            txFilter = link("financial.fees", null), explorerLink = expLink("financial.fees"),
        )

        val tweaked = base.copy(
            candidatesResponse = base.candidatesResponse.copy(
                candidates = base.candidatesResponse.candidates.copy(
                    recurringCharges = base.candidatesResponse.candidates.recurringCharges + listOf(smallAmazon, secondBigLottery, secondNegotiable),
                    feesAndInterest = FeesAndInterestDto(fees = listOf(smallFee), loanInterest = null),
                ),
            ),
        )

        val selected = SpendingReviewSelector.select(tweaked)

        // Small Amazon (€59.88/yr) and small fee (€25/yr) do NOT take top slots -> Frosthaven + Restaurants fill slots 5 and 6
        assertThat(selected.savings.map { it.id }).containsExactly(
            "sav_rec_lot",
            "sav_rec_vrienden",
            "sav_rec_loonzorg",
            "sav_rec_odido",
            "sav_merch_frost",
            "sav_habitual_outings_restaurants",
        )
        // The 2 excluded small items remain counted in savingsOmitted ("and 2 more")
        assertThat(selected.savingsOmitted).isEqualTo(2)
    }

    @Test
    fun `summary diagnosis covers all four rules plus in-line thresholds and model summaries that drop or contradict the diagnosis fall back`() {
        val base = sampleInputs()
        val forbiddenEsRegister = Regex("""(?i)\b(vosotros|vuestro|vuestra|vuestros|vuestras|tenéis|podéis|gastáis|ahorráis|nosotros|nuestro|nuestra|nuestros|nuestras)\b""")

        fun selectWithNumbers(
            expenses: String,
            income: String,
            avgExpenses: String = "4000.00",
            avgIncome: String = "6000.00",
        ): SelectedReview {
            val exp = BigDecimal(expenses)
            val inc = BigDecimal(income)
            val sav = inc - exp
            val avgExp = BigDecimal(avgExpenses)
            val avgInc = BigDecimal(avgIncome)
            val avgSav = avgInc - avgExp
            val rate = if (inc.signum() > 0) sav.toDouble() / inc.toDouble() * 100.0 else 0.0
            return SpendingReviewSelector.select(
                base.copy(
                    monthDashboard = base.monthDashboard.copy(income = inc, expenses = exp, savings = sav, savingsRate = rate),
                    trailing12 = base.trailing12.copy(
                        summary = base.trailing12.summary.copy(avgIncome = avgInc, avgExpenses = avgExp, avgNetSavings = avgSav),
                    ),
                ),
            )
        }

        // Case 1: savings below norm, expenses at or below norm -> shortfall_from_income
        val fromIncome = selectWithNumbers(expenses = "3500.00", income = "4500.00")
        assertThat(fromIncome.summaryFacts!!.diagnosis).isEqualTo(SummaryDiagnosis.shortfall_from_income)
        assertThat(fromIncome.summaryFacts.diagnosisDetail).isEqualTo("expenses_below")

        // Case 2: savings below norm, expenses above norm, income at or above norm -> shortfall_from_spending
        val fromSpending = selectWithNumbers(expenses = "4800.00", income = "6050.00") // +50 on 6000 is 0.83% <= 3% -> "at"
        assertThat(fromSpending.summaryFacts!!.incomeDirection).isEqualTo("at")
        assertThat(fromSpending.summaryFacts.diagnosis).isEqualTo(SummaryDiagnosis.shortfall_from_spending)
        assertThat(fromSpending.summaryFacts.diagnosisDetail).isEqualTo("income_in_line")

        // Case 3a: both -> spending_dominant
        val fromBothSpend = selectWithNumbers(expenses = "4900.00", income = "5500.00") // exp +900 vs inc -500
        assertThat(fromBothSpend.summaryFacts!!.diagnosis).isEqualTo(SummaryDiagnosis.shortfall_from_both)
        assertThat(fromBothSpend.summaryFacts.diagnosisDetail).isEqualTo("spending_dominant")

        // Case 3b: both -> income_dominant
        val fromBothInc = selectWithNumbers(expenses = "4300.00", income = "4900.00") // exp +300 vs inc -1100
        assertThat(fromBothInc.summaryFacts!!.diagnosis).isEqualTo(SummaryDiagnosis.shortfall_from_both)
        assertThat(fromBothInc.summaryFacts.diagnosisDetail).isEqualTo("income_dominant")

        // Case 3c: both -> equal
        val fromBothEqual = selectWithNumbers(expenses = "4500.00", income = "5500.00") // exp +500 vs inc -500
        assertThat(fromBothEqual.summaryFacts!!.diagnosis).isEqualTo(SummaryDiagnosis.shortfall_from_both)
        assertThat(fromBothEqual.summaryFacts.diagnosisDetail).isEqualTo("equal")

        // Case 4: savings above norm -> on_track_or_above with reason (lower_spending, higher_income, both_favorable)
        val aboveLowerSpend = selectWithNumbers(expenses = "3200.00", income = "6010.00")
        assertThat(aboveLowerSpend.summaryFacts!!.diagnosis).isEqualTo(SummaryDiagnosis.on_track_or_above)
        assertThat(aboveLowerSpend.summaryFacts.diagnosisDetail).isEqualTo("lower_spending")

        val aboveHigherInc = selectWithNumbers(expenses = "4020.00", income = "7200.00")
        assertThat(aboveHigherInc.summaryFacts!!.diagnosis).isEqualTo(SummaryDiagnosis.on_track_or_above)
        assertThat(aboveHigherInc.summaryFacts.diagnosisDetail).isEqualTo("higher_income")

        val aboveBoth = selectWithNumbers(expenses = "3300.00", income = "6900.00")
        assertThat(aboveBoth.summaryFacts!!.diagnosis).isEqualTo(SummaryDiagnosis.on_track_or_above)
        assertThat(aboveBoth.summaryFacts.diagnosisDetail).isEqualTo("both_favorable")

        // Case 5: In-line threshold (small deviation <= 3% or <= €25 reads as "at" / "in line with")
        val inLine = selectWithNumbers(expenses = "4080.00", income = "6060.00") // +2% exp, +1% inc, -1% sav
        assertThat(inLine.summaryFacts!!.expensesDirection).isEqualTo("at")
        assertThat(inLine.summaryFacts.incomeDirection).isEqualTo("at")
        assertThat(inLine.summaryFacts.savingsDirection).isEqualTo("at")
        assertThat(inLine.summaryFacts.diagnosis).isEqualTo(SummaryDiagnosis.on_track_or_above)
        assertThat(inLine.summaryFacts.diagnosisDetail).isEqualTo("in_line")
        assertThat(SpendingReviewRenderer.summaryTemplate(inLine.summaryFacts, "en")).contains("in line with")
        assertThat(SpendingReviewRenderer.summaryTemplate(inLine.summaryFacts, "es")).contains("en línea con")

        // Every deterministic diagnosis template in EN & ES must pass placeholder validation, diagnosis validation, banned terms & ES register
        val allCases = listOf(fromIncome, fromSpending, fromBothSpend, fromBothInc, fromBothEqual, aboveLowerSpend, aboveHigherInc, aboveBoth, inLine)
        for (rev in allCases) {
            val sf = rev.summaryFacts!!
            for (loc in listOf("en", "es")) {
                val tpl = SpendingReviewRenderer.summaryTemplate(sf, loc)
                assertThat(
                    SpendingReviewRenderer.validatePlaceholderProse(
                        tpl,
                        sf.figures.map { it.key }.toSet(),
                        setOf("f1", "f2", "f4", "f5", "f7", "f8", "f9", "f10"),
                    ),
                ).describedAs("placeholder check (${sf.diagnosis}/${sf.diagnosisDetail}/$loc): $tpl").isNull()
                assertThat(SpendingReviewRenderer.validateSummaryDiagnosis(tpl, sf))
                    .describedAs("diagnosis check (${sf.diagnosis}/${sf.diagnosisDetail}/$loc): $tpl")
                    .isNull()
                if (loc == "es") {
                    assertThat(forbiddenEsRegister.containsMatchIn(tpl)).describedAs("ES register: $tpl").isFalse()
                }
            }
        }

        // Model summary omitting diagnosis falls back to deterministic template
        val noDiagModel = mapper.readTree(
            """{"summary":"May closed with {f1} spent ({f3} below {f2}) and {f4} income ({f6} below {f5}). Net savings reached {f7} vs {f8} ({f9} vs {f10}).","findings":[]}""",
        )
        val noDiagRendered = SpendingReviewRenderer.renderWithModel(fromIncome, noDiagModel, "en", catLabelsEn)
        assertThat(noDiagRendered.summaryUsedModel).isFalse()
        assertThat(noDiagRendered.summaryFallbackReason).isEqualTo("diagnosis_mismatch")
        assertThat(noDiagRendered.summary).contains("shortfall comes from lower income")

        // Model summary contradicting diagnosis (blaming spending when shortfall is from income) falls back
        val wrongCauseModel = mapper.readTree(
            """{"summary":"May closed with {f1} vs {f2} and {f4} vs {f5}, leaving {f7} vs {f8} ({f9} vs {f10}). The shortfall comes from higher spending.","findings":[]}""",
        )
        val wrongCauseRendered = SpendingReviewRenderer.renderWithModel(fromIncome, wrongCauseModel, "en", catLabelsEn)
        assertThat(wrongCauseRendered.summaryUsedModel).isFalse()
        assertThat(wrongCauseRendered.summaryFallbackReason).isEqualTo("diagnosis_mismatch")

        // Model summary swapping dominance in shortfall_from_both (claiming income dominated when spending dominated) falls back
        val wrongDomModel = mapper.readTree(
            """{"summary":"May closed with {f1} vs {f2} ({f3} above) and {f4} vs {f5} ({f6} below), leaving {f7} vs {f8} ({f9} vs {f10}). Both higher spending and lower income weighed on savings, with income contributing more.","findings":[]}""",
        )
        val wrongDomRendered = SpendingReviewRenderer.renderWithModel(fromBothSpend, wrongDomModel, "en", catLabelsEn)
        assertThat(wrongDomRendered.summaryUsedModel).isFalse()
        assertThat(wrongDomRendered.summaryFallbackReason).isEqualTo("diagnosis_mismatch")
    }

    @Test
    fun `Spanish copy uses plain recurring titles, article in period_increase share, and plain amount-change phrasing`() {
        val selected = SpendingReviewSelector.select(sampleInputs())

        // 1. Lottery title: "Lotería: Nat Postcode" (not "Suscripción o cargo en")
        val lotFinding = selected.savings.first { it.id == "sav_rec_lot" }
        val (lotTitle, _) = SpendingReviewRenderer.findingTemplate(lotFinding, "es", catLabelsEs, useLocalName = true)
        assertThat(lotTitle).isEqualTo("Lotería: Nat Postcode")
        assertThat(lotTitle).doesNotContain("Suscripción o cargo en")

        // 2. Subscription title: "Suscripción: Amazon" + changed detail replaces "variación de cobros" with old -> new amount
        val amazonChanged = lotFinding.copy(
            id = "sav_rec_amazon",
            payloadSubject = "Amazon",
            localSubject = "Amazon",
            categoryCode = "outings.subscriptions",
            context = mapOf("cadence" to "monthly", "yoyChangeKind" to YoyChangeKind.changed.name),
        )
        val (amzTitle, amzDetail) = SpendingReviewRenderer.findingTemplate(amazonChanged, "es", catLabelsEs, useLocalName = true)
        assertThat(amzTitle).isEqualTo("Suscripción: Amazon")
        assertThat(amzDetail).doesNotContain("variación de cobros")
        assertThat(amzDetail).contains("el importe cambió de {f3} a {f1} respecto al año pasado")

        // 3. period_increase ES unlabelled branch includes "el": "supone el {f6} de la categoría"
        val restInc = selected.whatChanged.first { it.id == "chg_inc_outings_restaurants" }
        val (_, restDetail) = SpendingReviewRenderer.findingTemplate(restInc, "es", catLabelsEs, useLocalName = true)
        assertThat(restDetail).contains("que supone el {f6} de la categoría.")
    }

    @Test
    fun `reserves one savings slot from the merchants ceiling for the largest habitual-uneven category when one qualifies, and shows both merchants when none qualifies`() {
        val base = sampleInputs()
        val from = base.candidatesResponse.period.windowFrom
        val to = base.candidatesResponse.period.windowTo
        val link: (String, String?) -> TransactionLinkFilterDto = { cat, q ->
            TransactionLinkFilterDto(from, to, "expense", cat, null, q)
        }
        val expLink: (String) -> ExplorerLinkDto = { cat ->
            ExplorerLinkDto("category", cat, "custom", from, to)
        }

        val secondLottery = RecurringChargeCandidateDto(
            id = "rec_vrienden", merchant = "Vriendenloterij", mergedVariants = emptyList(),
            payloadLabel = "Vriendenloterij", anonymized = false, categoryCode = "outings.lottery", groupCode = "outings",
            essential = false, action = RecurringAction.cancellable, cadence = DetectedCadence.monthly, source = RecurringSource.detected,
            occurrences = 12, latestAmount = BigDecimal("16.00"), monthlyCost = BigDecimal("16.00"), annualCost = BigDecimal("192.00"),
            priorYearAmount = BigDecimal("15.50"), yoyDeltaAbs = BigDecimal("0.50"), yoyDeltaPct = 3.23,
            yoyChangeKind = YoyChangeKind.changed, potentialMonthlySaving = BigDecimal("16.00"),
            potentialAnnualSaving = BigDecimal("192.00"), txFilter = link("outings.lottery", "Vriendenloterij"), explorerLink = expLink("outings.lottery"),
        )
        val secondNegotiable = RecurringChargeCandidateDto(
            id = "rec_loonzorg", merchant = "Loonzorg", mergedVariants = emptyList(),
            payloadLabel = "Loonzorg", anonymized = false, categoryCode = "home.utilities", groupCode = "home",
            essential = true, action = RecurringAction.negotiable, cadence = DetectedCadence.monthly, source = RecurringSource.detected,
            occurrences = 24, latestAmount = BigDecimal("369.60"), monthlyCost = BigDecimal("369.60"), annualCost = BigDecimal("4435.20"),
            priorYearAmount = BigDecimal("356.90"), yoyDeltaAbs = BigDecimal("12.70"), yoyDeltaPct = 3.56,
            yoyChangeKind = YoyChangeKind.price_increase, potentialMonthlySaving = null, potentialAnnualSaving = null,
            txFilter = link("home.utilities", "Loonzorg"), explorerLink = expLink("home.utilities"),
        )
        val secondMerchant = TopMerchantCandidateDto(
            id = "merch_ae", merchant = "AE filamentos", payloadLabel = "AE filamentos", anonymized = false,
            categoryCode = "outings.hobbies", groupCode = "outings", essential = false,
            total = BigDecimal("204.59"), purchases = 4, averageTicket = BigDecimal("51.15"),
            shareOfCategoryPct = 22.2, monthlyEquivalent = BigDecimal("17.05"),
            potentialMonthlySaving = null, potentialAnnualSaving = null,
            txFilter = link("outings.hobbies", "AE filamentos"), explorerLink = expLink("outings.hobbies"),
        )
        val smallerHabitual = LumpyCategoryCandidateDto(
            id = "habitual_outings_hobbies", categoryCode = "outings.hobbies", groupCode = "outings", essential = false,
            monthlyMean = BigDecimal("90.00"), monthlyMedian = BigDecimal("50.00"),
            trimToMedianMonthly = BigDecimal("40.00"), trimToMedianAnnual = BigDecimal("480.00"),
            potentialMonthlySaving = BigDecimal("40.00"), potentialAnnualSaving = BigDecimal("480.00"),
            nonZeroMonths = 9, peakMonthAmount = BigDecimal("250.00"),
            txFilter = link("outings.hobbies", null), explorerLink = expLink("outings.hobbies"),
        )

        val withHabitual = base.copy(
            candidatesResponse = base.candidatesResponse.copy(
                candidates = base.candidatesResponse.candidates.copy(
                    recurringCharges = base.candidatesResponse.candidates.recurringCharges + listOf(secondLottery, secondNegotiable),
                    topNonEssentialMerchants = base.candidatesResponse.candidates.topNonEssentialMerchants + secondMerchant,
                    feesAndInterest = FeesAndInterestDto(fees = emptyList(), loanInterest = null),
                    lumpyCategories = base.candidatesResponse.candidates.lumpyCategories + smallerHabitual,
                ),
            ),
        )

        // With qualifying habitual-uneven categories: 1 slot is reserved from merchants ceiling for the largest annual habitual category
        val selectedWith = SpendingReviewSelector.select(withHabitual)
        assertThat(selectedWith.savings.map { it.id }).containsExactly(
            "sav_rec_lot",
            "sav_rec_vrienden",
            "sav_rec_loonzorg",
            "sav_rec_odido",
            "sav_merch_frost",
            "sav_habitual_outings_restaurants",
        )
        assertThat(selectedWith.savings.map { it.id }).doesNotContain("sav_merch_ae", "sav_habitual_outings_hobbies")
        assertThat(selectedWith.savingsOmitted).isEqualTo(2)

        // Without any qualifying habitual-uneven category: merchants keep full ceiling of 2
        val withoutHabitual = withHabitual.copy(
            candidatesResponse = withHabitual.candidatesResponse.copy(
                candidates = withHabitual.candidatesResponse.candidates.copy(lumpyCategories = emptyList()),
            ),
        )
        val selectedWithout = SpendingReviewSelector.select(withoutHabitual)
        assertThat(selectedWithout.savings.map { it.id }).containsExactly(
            "sav_rec_lot",
            "sav_rec_vrienden",
            "sav_rec_loonzorg",
            "sav_rec_odido",
            "sav_merch_frost",
            "sav_merch_ae",
        )
        assertThat(selectedWithout.savingsOmitted).isEqualTo(0)
    }

    @Test
    fun `explains income shortfall against median income, adds top income mover to What changed, and distinguishes under-sized budgets from genuine overspends`() {
        val base = sampleInputs()
        val forbiddenEsRegister = Regex("""(?i)\b(vosotros|vuestro|vuestra|vuestros|vuestras|tenéis|podéis|gastáis|ahorráis|nosotros|nuestro|nuestra|nuestros|nuestras)\b""")

        // 12m series where 3 bonus months pull the mean (€7,668.21) far above the median (€6,246.89)
        val skewedPoints = listOf(
            "6593.00", "7361.70", "6150.00", "6150.00", "6150.00", "6307.36",
            "6186.41", "12713.53", "12965.22", "6085.84", "10908.86", "4446.64",
        ).mapIndexed { i, inc ->
            TrailingPoint(2025 + (5 + i) / 12, ((5 + i) % 12) + 1, BigDecimal(inc), BigDecimal("4700.52"), BigDecimal(inc) - BigDecimal("4700.52"), 30.0)
        }

        // Case A: May 2026 genuinely below typical month (€4,446.64 vs median €6,246.88 & mean €7,668.21)
        val mayBelowTypical = SpendingReviewSelector.select(
            base.copy(
                trailing12 = base.trailing12.copy(points = skewedPoints),
                incomeMovers = listOf(
                    IncomeCategoryMover("income.salary", "income", BigDecimal("4446.64"), BigDecimal("7473.62"), BigDecimal("6150.00")),
                ),
                budgetSummary = MonthSummaryResponse(
                    year = 2026, month = 5, daysElapsed = 31, daysInMonth = 31,
                    rows = listOf(
                        MonthSummaryRow("transport.car_maintenance", BigDecimal("50.00"), BigDecimal("533.00"), BigDecimal("50.00"), BigDecimal("533.00"), 1066.0),
                        MonthSummaryRow("groceries.groceries", BigDecimal("400.00"), BigDecimal("595.00"), BigDecimal("400.00"), BigDecimal("595.00"), 148.75),
                        MonthSummaryRow("outings.restaurants", BigDecimal("200.00"), BigDecimal("280.00"), BigDecimal("200.00"), BigDecimal("280.00"), 140.0),
                    ),
                ),
                budgetHistoryStats = mapOf(
                    // Near-zero median (€1 <= €5), concentrated in 4 of 12 months (99.4% of total) -> annual_better
                    "transport.car_maintenance" to BudgetHistoryStats(
                        trailing6mAvg = BigDecimal("230.98"),
                        median12m = BigDecimal("1.00"),
                        total12m = BigDecimal("2344.85"),
                        exceededMonths12m = 4,
                        exceededMonthsTotal12m = BigDecimal("2331.85"),
                        windowMonths = 12,
                    ),
                    // Regular monthly spend well above budget (10 of 12 months) -> under_sized
                    "groceries.groceries" to BudgetHistoryStats(
                        trailing6mAvg = BigDecimal("540.00"),
                        median12m = BigDecimal("520.00"),
                        total12m = BigDecimal("6300.00"),
                        exceededMonths12m = 10,
                        exceededMonthsTotal12m = BigDecimal("5500.00"),
                        windowMonths = 12,
                    ),
                    // Usually under budget (2 of 12 months) -> genuine_overspend
                    "outings.restaurants" to BudgetHistoryStats(
                        trailing6mAvg = BigDecimal("170.00"),
                        median12m = BigDecimal("150.00"),
                        total12m = BigDecimal("2000.00"),
                        exceededMonths12m = 2,
                        exceededMonthsTotal12m = BigDecimal("560.00"),
                        windowMonths = 12,
                    ),
                ),
            ),
        )

        val sfA = mayBelowTypical.summaryFacts!!
        assertThat(sfA.diagnosis).isEqualTo(SummaryDiagnosis.shortfall_from_income)
        assertThat(sfA.incomeVsMedianContext).isEqualTo("below_typical")
        assertThat(sfA.figures.first { it.key == "f15" }.value).isEqualByComparingTo(BigDecimal("6246.88"))
        assertThat(sfA.figures.first { it.key == "f16" }.value).isEqualByComparingTo(BigDecimal("1800.24"))

        // Top finding in What changed is the income.salary drop, clickable to income transactions (no Explorer link)
        val incFinding = mayBelowTypical.whatChanged.first()
        assertThat(incFinding.kind).isEqualTo(FindingKind.income_change)
        assertThat(incFinding.categoryCode).isEqualTo("income.salary")
        assertThat(incFinding.changeKind).isEqualTo("drop")
        assertThat(incFinding.txFilter!!.direction).isEqualTo("income")
        assertThat(incFinding.explorerLink).isNull()

        // Budgets distinguish annual_better (car maintenance), under_sized (groceries), and genuine_overspend (restaurants)
        val carBudget = mayBelowTypical.budgets.first { it.categoryCode == "transport.car_maintenance" }
        val grocBudget = mayBelowTypical.budgets.first { it.categoryCode == "groceries.groceries" }
        val restBudget = mayBelowTypical.budgets.first { it.categoryCode == "outings.restaurants" }
        assertThat(carBudget.context["budgetFit"]).isEqualTo("annual_better")
        assertThat(carBudget.figures.first { it.key == "f5" }.value).isEqualByComparingTo(BigDecimal("230.98"))
        assertThat(carBudget.figures.first { it.key == "f6" }.value).isEqualByComparingTo(BigDecimal("4"))
        assertThat(carBudget.figures.first { it.key == "f7" }.value).isEqualByComparingTo(BigDecimal("12"))
        assertThat(grocBudget.context["budgetFit"]).isEqualTo("under_sized")
        assertThat(restBudget.context["budgetFit"]).isEqualTo("genuine_overspend")

        // Case B: Normal salary month (€6,150) where 12m mean (€7,668.21) is lifted by bonus months.
        // Diagnosis is decided against median income (€6,246.88) and median savings (€1,546.36):
        // NEVER flagged as "lower income" / shortfall_from_income, and NO spurious income_change finding!
        val normalMonthSkewed = SpendingReviewSelector.select(
            base.copy(
                monthDashboard = base.monthDashboard.copy(
                    income = BigDecimal("6150.00"),
                    expenses = BigDecimal("3832.48"),
                    savings = BigDecimal("2317.52"),
                    savingsRate = 37.68,
                ),
                trailing12 = base.trailing12.copy(points = skewedPoints),
                incomeMovers = listOf(
                    IncomeCategoryMover("income.salary", "income", BigDecimal("6150.00"), BigDecimal("7473.62"), BigDecimal("6150.00")),
                ),
            ),
        )
        assertThat(normalMonthSkewed.summaryFacts!!.diagnosis).isEqualTo(SummaryDiagnosis.on_track_or_above)
        assertThat(normalMonthSkewed.summaryFacts.incomeDirection).isEqualTo("at")
        assertThat(normalMonthSkewed.summaryFacts.incomeVsMedianContext).isEqualTo("normal_month_mean_skewed")
        assertThat(normalMonthSkewed.whatChanged.none { it.kind == FindingKind.income_change }).isTrue()
        val normalSumEn = SpendingReviewRenderer.substitute(
            SpendingReviewRenderer.summaryTemplate(normalMonthSkewed.summaryFacts, "en"),
            normalMonthSkewed.summaryFacts.figures,
            "EUR",
            "en",
        )
        assertThat(normalSumEn).doesNotContain("lower income").contains("typical €6,246.88 month")

        // Verify EN & ES templates pass placeholder, diagnosis, banned-terms, and Spanish tu-register checks
        for (rev in listOf(mayBelowTypical, normalMonthSkewed)) {
            val sf = rev.summaryFacts!!
            for ((loc, labels) in listOf("en" to catLabelsEn, "es" to catLabelsEs)) {
                val sumTpl = SpendingReviewRenderer.summaryTemplate(sf, loc)
                assertThat(
                    SpendingReviewRenderer.validatePlaceholderProse(
                        sumTpl,
                        sf.figures.map { it.key }.toSet(),
                        setOf("f1", "f2", "f4", "f5", "f7", "f8", "f9", "f10", "f15"),
                    ),
                ).describedAs("summary ($loc): $sumTpl").isNull()
                assertThat(SpendingReviewRenderer.validateSummaryDiagnosis(sumTpl, sf))
                    .describedAs("summary diagnosis ($loc): $sumTpl")
                    .isNull()
                if (loc == "es") {
                    assertThat(forbiddenEsRegister.containsMatchIn(sumTpl)).isFalse()
                }
                for (f in rev.whatChanged.filter { it.kind == FindingKind.income_change } + rev.budgets) {
                    val (title, detail) = SpendingReviewRenderer.findingTemplate(f, loc, labels, useLocalName = true)
                    assertThat(SpendingReviewRenderer.validateTitle(title)).describedAs("title ($loc/${f.kind}): $title").isNull()
                    assertThat(
                        SpendingReviewRenderer.validatePlaceholderProse(detail, f.figures.map { it.key }.toSet(), setOf("f1")),
                    ).describedAs("detail ($loc/${f.kind}): $detail").isNull()
                    if (loc == "es") {
                        assertThat(forbiddenEsRegister.containsMatchIn("$title $detail")).isFalse()
                    }
                }
            }
        }

        // Model that omits {f5} or {f6}/{f7} on an annual_better budget falls back to the deterministic template
        val omitAvgModel = mapper.readTree(
            """{"summary":null,"findings":[{"id":"bud_transport_car_maintenance","title":"Car maintenance over budget","detail":"Car maintenance reached {f1} against {f2}, {f3} over ({f4})."}]}""",
        )
        val renderedCar = SpendingReviewRenderer.renderWithModel(mayBelowTypical, omitAvgModel, "en", catLabelsEn)
            .findings.first { it.findingId == "bud_transport_car_maintenance" }
        assertThat(renderedCar.usedModel).isFalse()
        assertThat(renderedCar.fallbackReason).isEqualTo("missing_required_placeholder:f5")
        assertThat(renderedCar.detail).contains("4 of the last 12 months").contains("€230.98").contains("annual budget fits")
    }

    @Test
    fun `handles single-purchase movers without plural bugs, applies median-expenses guard in summary, and flags peak-dominated habitual categories`() {
        val base = sampleInputs()
        val forbiddenEsRegister = Regex("""(?i)\b(vosotros|vuestro|vuestra|vuestros|vuestras|tenéis|podéis|gastáis|ahorráis|nosotros|nuestro|nuestra|nuestros|nuestras)\b""")

        // 1. Single-purchase period_increase (f4 = 1, f7 = 1): no "1 compras" / "1 purchases" or redundant 100% repetition
        val singlePurchaseInputs = base.copy(
            moversVsTrailing6 = TopMoversResponse(
                year = 2026, month = 3, baseline = "trailing6_avg",
                increases = listOf(
                    MoverRow("outings.travel", "outings", BigDecimal("6144.60"), BigDecimal("29.10"), BigDecimal("6115.50"), 21015.46),
                ),
                decreases = emptyList(), newActivity = emptyList(),
                totalIncrease = BigDecimal("6115.50"), totalDecrease = BigDecimal.ZERO,
            ),
            moverBreakdowns = mapOf(
                "outings.travel" to MoverCategoryBreakdown(
                    categoryCode = "outings.travel",
                    periodTotal = BigDecimal("6144.60"),
                    periodPurchases = 1,
                    topMerchants = listOf(
                        MoverMerchantDriver("Frequent counterparty in outings.travel", true, BigDecimal("6144.60"), 1, 100.0),
                    ),
                    topMerchantsSummary = null,
                    otherMerchants = null,
                ),
            ),
        )
        val selectedSingle = SpendingReviewSelector.select(singlePurchaseInputs)
        val travelInc = selectedSingle.whatChanged.first { it.categoryCode == "outings.travel" }
        val (_, detailEs) = SpendingReviewRenderer.findingTemplate(travelInc, "es", catLabelsEs, useLocalName = true)
        val (_, detailEn) = SpendingReviewRenderer.findingTemplate(travelInc, "en", catLabelsEn, useLocalName = true)
        assertThat(detailEs).contains("en una sola compra en un mismo comercio.").doesNotContain("compras")
        assertThat(detailEn).contains("from a single purchase at one payee.").doesNotContain("purchases")
        assertThat(SpendingReviewRenderer.validatePlaceholderProse(detailEs, travelInc.figures.map { it.key }.toSet(), setOf("f1", "f2"))).isNull()
        assertThat(SpendingReviewRenderer.validatePlaceholderProse(detailEn, travelInc.figures.map { it.key }.toSet(), setOf("f1", "f2"))).isNull()
        assertThat(forbiddenEsRegister.containsMatchIn(detailEs)).isFalse()

        // 2. Median expenses guard: travel-inflated mean expenses (€4,700.52) vs median (€4,043.64).
        // A normal €4,050 spending month must read as expensesDirection == "at" ("normal_month_mean_skewed"), NOT "below".
        val travelSkewedExpenses = listOf(
            "4063.50", "3864.26", "4356.67", "4025.58", "3047.73", "4061.69",
            "3759.39", "5318.87", "3391.29", "10070.38", "6614.45", "3832.48",
        ).mapIndexed { i, exp ->
            val e = BigDecimal(exp)
            val inc = BigDecimal("6200.00")
            TrailingPoint(2025 + (5 + i) / 12, ((5 + i) % 12) + 1, inc, e, inc - e, 30.0)
        }
        val ordinarySpend = SpendingReviewSelector.select(
            base.copy(
                monthDashboard = base.monthDashboard.copy(
                    income = BigDecimal("4446.64"),
                    expenses = BigDecimal("4050.00"),
                    savings = BigDecimal("396.64"),
                    savingsRate = 8.92,
                ),
                trailing12 = TrailingResponse(
                    points = travelSkewedExpenses,
                    summary = TrailingSummary(
                        avgIncome = BigDecimal("6200.00"),
                        avgExpenses = BigDecimal("4700.52"),
                        avgNetSavings = BigDecimal("1499.48"),
                        medianNetSavings = BigDecimal("2156.36"),
                    ),
                ),
            ),
        )
        val sfExp = ordinarySpend.summaryFacts!!
        assertThat(sfExp.expensesDirection).isEqualTo("at")
        assertThat(sfExp.expensesVsMedianContext).isEqualTo("normal_month_mean_skewed")
        assertThat(sfExp.figures.first { it.key == "f18" }.value).isEqualByComparingTo(BigDecimal("4043.64"))
        val sumEn = SpendingReviewRenderer.substitute(SpendingReviewRenderer.summaryTemplate(sfExp, "en"), sfExp.figures, "EUR", "en")
        val sumEs = SpendingReviewRenderer.substitute(SpendingReviewRenderer.summaryTemplate(sfExp, "es"), sfExp.figures, "EUR", "es")
        assertThat(sumEn).contains("in line with a typical month of €4,043.64")
        assertThat(sumEs).contains("en línea con un mes habitual de 4.043,64 €")
        assertThat(forbiddenEsRegister.containsMatchIn(sumEs)).isFalse()

        // 3. Peak-month outlier attribution on habitual_uneven (Restaurants & bars: €1,386.45 peak is 79.9% of 12m excess)
        val restHab = selectedSingle.savings.first { it.kind == FindingKind.habitual_uneven }
        assertThat(restHab.context["peakDominated"]).isEqualTo("true")
        assertThat(restHab.figures.first { it.key == "f6" }.value).isEqualByComparingTo(BigDecimal("1386.45"))
        assertThat(restHab.figures.first { it.key == "f7" }.value).isEqualByComparingTo(BigDecimal("79.91"))
        val habEn = SpendingReviewRenderer.substitute(
            SpendingReviewRenderer.findingTemplate(restHab, "en", catLabelsEn).second,
            restHab.figures, "EUR", "en",
        )
        val habEs = SpendingReviewRenderer.substitute(
            SpendingReviewRenderer.findingTemplate(restHab, "es", catLabelsEs).second,
            restHab.figures, "EUR", "es",
        )
        assertThat(habEn).contains("79.9%").contains("€1,386.45 peak month")
        assertThat(habEs).contains("79,9 %").contains("pico mensual de 1.386,45 €")
        assertThat(forbiddenEsRegister.containsMatchIn(habEs)).isFalse()

        // Model omitting {f6} on a peak-dominated habitual finding falls back to the deterministic template
        val omitPeakModel = mapper.readTree(
            """{"summary":null,"findings":[{"id":"sav_habitual_outings_restaurants","title":"Restaurants above typical","detail":"Restaurants averaged {f1} across {f5} months vs {f2}, leaving {f3} monthly ({f4} yearly)."}]}""",
        )
        val renderedHab = SpendingReviewRenderer.renderWithModel(selectedSingle, omitPeakModel, "en", catLabelsEn)
            .findings.first { it.findingId == "sav_habitual_outings_restaurants" }
        assertThat(renderedHab.usedModel).isFalse()
        assertThat(renderedHab.fallbackReason).isEqualTo("missing_required_placeholder:f6")
        assertThat(renderedHab.detail).contains("€1,386.45")
    }
}
