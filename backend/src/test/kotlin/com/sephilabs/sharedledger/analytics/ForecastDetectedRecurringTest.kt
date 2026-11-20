package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.analytics.recurring.DetectedRecurringCache
import com.sephilabs.sharedledger.analytics.review.AnalyticsAiPayloadBuilder
import com.sephilabs.sharedledger.analytics.review.DecisionSubjectKind
import com.sephilabs.sharedledger.analytics.review.RecordSpendingReviewDecisionRequest
import com.sephilabs.sharedledger.analytics.review.ReviewMode
import com.sephilabs.sharedledger.analytics.review.SavingsCandidatesService
import com.sephilabs.sharedledger.analytics.review.SpendingReviewDecisionService
import com.sephilabs.sharedledger.analytics.review.SpendingReviewDecisionType
import com.sephilabs.sharedledger.analytics.review.SpendingReviewSkeletonService
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.recurring.Cadence
import com.sephilabs.sharedledger.recurring.RecurringService
import com.sephilabs.sharedledger.recurring.RecurringTemplate
import com.sephilabs.sharedledger.recurring.RecurringTemplateRepository
import com.sephilabs.sharedledger.recurring.RecurringTemplateRequest
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.Transaction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class ForecastDetectedRecurringTest @Autowired constructor(
    private val service: AnalyticsService,
    private val estimator: ForecastEstimator,
    private val transactions: TransactionRepository,
    private val recurring: RecurringService,
    private val templates: RecurringTemplateRepository,
    private val savingsCandidates: SavingsCandidatesService,
    private val skeletonService: SpendingReviewSkeletonService,
    private val decisions: SpendingReviewDecisionService,
    private val detectedCache: DetectedRecurringCache,
    private val categoryService: CategoryService,
    private val users: UserRepository,
    private val households: HouseholdRepository,
) : IntegrationTestBase() {

    @Test
    fun `gap-month same-amount attribution keeps Florius plus Mortgage forecast at 12x, exposes it locally, and never leaks it to AI`() {
        val (user, household) = seed()
        // 12-month window May 2025..Apr 2026: 11 months labelled "Florius", June 2025 labelled "Mortgage" (same €1,243.88).
        val months = (0L..11L).map { YearMonth.of(2025, 5).plusMonths(it) }
        for (ym in months) {
            val desc = if (ym == YearMonth.of(2025, 6)) "Mortgage" else "Florius"
            tx(household, user, ym.atDay(28), Direction.expense, "home.mortgage", "1243.88", description = desc)
        }

        val asOfMonth = YearMonth.of(2026, 5)
        val basis = estimator.basis(household.id, asOfMonth, windowMonths = 12, asOfDate = LocalDate.of(2026, 5, 15))
        // "Mortgage" is attributed to Florius's footprint -> discretionary average is strictly zero.
        assertThat(basis.expectedDiscretionary(FlowKey(Direction.expense, "home.mortgage"))).isEqualByComparingTo("0.00")

        val forecast = service.forecast(household.id, horizonMonths = 12, windowMonths = 12, asOfMonth = asOfMonth)
        val mortgage = forecast.categories.first { it.categoryCode == "home.mortgage" }
        val annualTotal = mortgage.projection.fold(BigDecimal.ZERO) { acc, pt -> acc + pt.projectedExpense }
        // Exactly 12 × €1,243.88 = €14,926.56 (NOT 13 × €1,243.88).
        assertThat(annualTotal).isEqualByComparingTo("14926.56")
        assertThat(mortgage.projection).allSatisfy { pt ->
            assertThat(pt.projectedExpense).isEqualByComparingTo("1243.88")
            assertThat(pt.source).isEqualTo("recurring")
        }

        // Local Spending review panel exposes "Mortgage" as an attributed entry on Florius, never in the AI payload.
        val reviewResp = savingsCandidates.candidates(household.id, year = 2026, month = 4)
        val florius = reviewResp.candidates.recurringCharges.first { it.merchant == "Florius" }
        assertThat(florius.attributedEntries).containsExactly("Mortgage")
        assertThat(florius.occurrences).isEqualTo(12)

        val selected = skeletonService.selectForPeriod(
            householdId = household.id,
            period = YearMonth.of(2026, 4),
            mode = ReviewMode.FULL,
            today = LocalDate.of(2026, 5, 15),
            candidatesResp = reviewResp,
            categories = categoryService.listForHousehold(household.id),
            locale = "en",
        )
        val payloadJson = AnalyticsAiPayloadBuilder.build(selected, "en") { it }.json
        assertThat(payloadJson).doesNotContain("attributedEntries")
        assertThat(payloadJson).doesNotContain("Mortgage")

        // An independent ended repeat merchant (>= 3 months in its own right) in the same category/amount
        // must NOT be falsely attributed to another charge's gap month.
        val (u2, h2) = seed()
        val m24 = (0L..11L).map { YearMonth.of(2025, 5).plusMonths(it) }
        for (ym in m24) {
            if (ym != YearMonth.of(2025, 7)) {
                tx(h2, u2, ym.atDay(10), Direction.expense, "home.services", "4.99", description = "Amazon")
            }
        }
        // HBO ran for 3 months and ended in July 2025 at the exact same €4.99 amount.
        for (ym in listOf(YearMonth.of(2025, 5), YearMonth.of(2025, 6), YearMonth.of(2025, 7))) {
            tx(h2, u2, ym.atDay(20), Direction.expense, "home.services", "4.99", description = "HBO")
        }
        val review2 = savingsCandidates.candidates(h2.id, year = 2026, month = 4)
        val amazon = review2.candidates.recurringCharges.first { it.merchant == "Amazon" }
        assertThat(amazon.attributedEntries).isEmpty()
    }

    @Test
    fun `conservative skipped-month mask skips 2 absent months for a 10-installment tax with 24m history but keeps monthly for a single-year gap`() {
        val (user, household) = seed()
        // 24 complete months: May 2024..Apr 2026.
        val all24 = (0L..23L).map { YearMonth.of(2024, 5).plusMonths(it) }
        for (ym in all24) {
            // 10-installment municipal tax (GBLT): absent in May and June in BOTH years.
            if (ym.monthValue !in setOf(5, 6)) {
                tx(household, user, ym.atDay(26), Direction.expense, "home.taxes", "120.00", description = "GBLT")
            }
            // Charge missing 1 month in ONLY the first year (June 2024), present all 12 months in year 2.
            if (ym != YearMonth.of(2024, 6)) {
                tx(household, user, ym.atDay(20), Direction.expense, "home.services", "50.00", description = "WaterNet")
            }
        }

        val asOfMonth = YearMonth.of(2026, 5)
        val forecast = service.forecast(household.id, horizonMonths = 12, windowMonths = 12, asOfMonth = asOfMonth)

        // Forecast covers Jun 2026..May 2027 (12 months). GBLT skips Jun 2026 & May 2027 -> exactly 10 × €120 = €1,200.
        val taxes = forecast.categories.first { it.categoryCode == "home.taxes" }
        val taxesByMonth = taxes.projection.associate { it.month to it.projectedExpense }
        assertThat(taxesByMonth[5]).isEqualByComparingTo("0.00")
        assertThat(taxesByMonth[6]).isEqualByComparingTo("0.00")
        for (m in listOf(1, 2, 3, 4, 7, 8, 9, 10, 11, 12)) {
            assertThat(taxesByMonth[m]).isEqualByComparingTo("120.00")
        }
        assertThat(taxes.projection.fold(BigDecimal.ZERO) { a, b -> a + b.projectedExpense }).isEqualByComparingTo("1200.00")

        // WaterNet missed only June 2024 (1 year) -> still scheduled every single month (12 × €50 = €600).
        val services = forecast.categories.first { it.categoryCode == "home.services" }
        assertThat(services.projection).allSatisfy { assertThat(it.projectedExpense).isEqualByComparingTo("50.00") }
        assertThat(services.projection.fold(BigDecimal.ZERO) { a, b -> a + b.projectedExpense }).isEqualByComparingTo("600.00")

        // Month projection in May 2026: same mask applies to recurringStillDue!
        // GBLT is skipped in May -> recurringDue == €0.00; WaterNet is NOT skipped -> recurringDue == €50.00.
        val mayProj = service.moneyFlowProjection(household.id, LocalDate.of(2026, 5, 15), "category", 12)
        assertThat(mayProj.components.firstOrNull { it.side == "expense" && it.id == "home.taxes" }?.recurringDue ?: BigDecimal.ZERO)
            .isEqualByComparingTo("0.00")
        assertThat(mayProj.components.first { it.side == "expense" && it.id == "home.services" }.recurringDue)
            .isEqualByComparingTo("50.00")

        // A second household with only 12 months of history (even if absent in May & Jun) must NOT skip months.
        val (uShort, hShort) = seed()
        val only12 = (0L..11L).map { YearMonth.of(2025, 5).plusMonths(it) }
        for (ym in only12) {
            if (ym.monthValue !in setOf(5, 6)) {
                tx(hShort, uShort, ym.atDay(26), Direction.expense, "home.taxes", "120.00", description = "GBLT")
            }
        }
        val shortForecast = service.forecast(hShort.id, horizonMonths = 12, windowMonths = 12, asOfMonth = asOfMonth)
        val shortTaxes = shortForecast.categories.first { it.categoryCode == "home.taxes" }
        assertThat(shortTaxes.projection).allSatisfy { assertThat(it.projectedExpense).isEqualByComparingTo("120.00") }
    }

    @Test
    fun `constant-amount monthly charge present every month yields identical Forecast and month projection before and after to the cent`() {
        val (userA, detectedHousehold) = seed()
        val (userB, unlabelledHousehold) = seed()
        val months = (0L..11L).map { YearMonth.of(2025, 5).plusMonths(it) }
        for (ym in months) {
            // Household A: labelled -> detected as a monthly recurring charge (Phase 4 path).
            tx(detectedHousehold, userA, ym.atDay(5), Direction.expense, "home.rent", "600.00", description = "Landlord BV")
            // Household B: unlabelled -> purely discretionary average (pre-Phase 4 path).
            tx(unlabelledHousehold, userB, ym.atDay(5), Direction.expense, "home.rent", "600.00", description = null)
        }

        val asOfMonth = YearMonth.of(2026, 5)
        val today = LocalDate.of(2026, 5, 15)

        // 1. Forecast: identical to the cent for every projected month.
        val fDetected = service.forecast(detectedHousehold.id, 12, 12, asOfMonth)
        val fUnlabelled = service.forecast(unlabelledHousehold.id, 12, 12, asOfMonth)
        val pDetected = fDetected.categories.first { it.categoryCode == "home.rent" }.projection
        val pUnlabelled = fUnlabelled.categories.first { it.categoryCode == "home.rent" }.projection
        assertThat(pDetected.map { it.projectedExpense }).isEqualTo(pUnlabelled.map { it.projectedExpense })
        assertThat(pDetected.all { it.source == "recurring" }).isTrue()
        assertThat(pUnlabelled.all { it.source == "history" }).isTrue()

        // 2. Month projection BEFORE this month's payment arrives: total projected identical to the cent.
        val mDetectedBefore = service.moneyFlowProjection(detectedHousehold.id, today, "category", 12)
        val mUnlabelledBefore = service.moneyFlowProjection(unlabelledHousehold.id, today, "category", 12)
        val cDetBefore = mDetectedBefore.components.first { it.side == "expense" && it.id == "home.rent" }
        val cUnlBefore = mUnlabelledBefore.components.first { it.side == "expense" && it.id == "home.rent" }
        assertThat(cDetBefore.recurringDue).isEqualByComparingTo("600.00")
        assertThat(cDetBefore.discretionaryEstimate).isEqualByComparingTo("0.00")
        assertThat(cUnlBefore.recurringDue).isEqualByComparingTo("0.00")
        assertThat(cUnlBefore.discretionaryEstimate).isEqualByComparingTo("600.00")
        assertThat(cDetBefore.projected).isEqualByComparingTo(cUnlBefore.projected)
        assertThat(mDetectedBefore.expenses).isEqualByComparingTo(mUnlabelledBefore.expenses)

        // 3. Month projection AFTER this month's payment arrives: still identical to the cent.
        tx(detectedHousehold, userA, LocalDate.of(2026, 5, 5), Direction.expense, "home.rent", "600.00", description = "Landlord BV")
        tx(unlabelledHousehold, userB, LocalDate.of(2026, 5, 5), Direction.expense, "home.rent", "600.00", description = null)
        val mDetectedAfter = service.moneyFlowProjection(detectedHousehold.id, today, "category", 12)
        val mUnlabelledAfter = service.moneyFlowProjection(unlabelledHousehold.id, today, "category", 12)
        val cDetAfter = mDetectedAfter.components.first { it.side == "expense" && it.id == "home.rent" }
        val cUnlAfter = mUnlabelledAfter.components.first { it.side == "expense" && it.id == "home.rent" }
        assertThat(cDetAfter.actual).isEqualByComparingTo("600.00")
        assertThat(cDetAfter.recurringDue).isEqualByComparingTo("0.00")
        assertThat(cDetAfter.discretionaryEstimate).isEqualByComparingTo("0.00")
        assertThat(cDetAfter.projected).isEqualByComparingTo(cUnlAfter.projected)
        assertThat(mDetectedAfter.expenses).isEqualByComparingTo(mUnlabelledAfter.expenses)
    }

    @Test
    fun `templates-only household is unchanged and never double-counted with detected recurring`() {
        val (user, household) = seed()
        val start = YearMonth.of(2025, 7)
        val today = LocalDate.of(2025, 10, 18)
        val rent = template(household, user, Direction.expense, "home.rent", "750.00", dayOfMonth = 25, description = "Rent", startDate = start.atDay(1), watermark = today.minusDays(1))
        for (ym in listOf(YearMonth.of(2025, 7), YearMonth.of(2025, 8), YearMonth.of(2025, 9))) {
            tx(household, user, ym.atDay(25), Direction.expense, "home.rent", "750.00", description = "Rent", templateId = rent.id)
        }

        val forecast = service.forecast(household.id, 3, 3, YearMonth.of(2025, 10))
        val rentForecast = forecast.categories.first { it.categoryCode == "home.rent" }
        assertThat(rentForecast.projection).allSatisfy { assertThat(it.projectedExpense).isEqualByComparingTo("750.00") }

        val proj = service.moneyFlowProjection(household.id, today, "category", 3)
        val comp = proj.components.first { it.side == "expense" && it.id == "home.rent" }
        assertThat(comp.actual).isEqualByComparingTo("0.00")
        assertThat(comp.recurringDue).isEqualByComparingTo("750.00")
        assertThat(comp.discretionaryEstimate).isEqualByComparingTo("0.00")
        assertThat(comp.projected).isEqualByComparingTo("750.00")
    }

    @Test
    fun `no charge is counted in both recurring and discretionary terms, and not_recurring decision updates Forecast via fingerprint cache`() {
        val (user, household) = seed()
        val months = listOf(YearMonth.of(2025, 7), YearMonth.of(2025, 8), YearMonth.of(2025, 9))
        for (ym in months) {
            // Detected monthly recurring (€100) + discretionary variable spend (€50) in the same category.
            tx(household, user, ym.atDay(10), Direction.expense, "home.services", "100.00", description = "Vodafone")
            tx(household, user, ym.atDay(18), Direction.expense, "home.services", "50.00", description = null)
        }
        val today = LocalDate.of(2025, 10, 15)
        // In October, Vodafone (€100) already posted; discretionary €50 has NOT happened yet.
        tx(household, user, LocalDate.of(2025, 10, 10), Direction.expense, "home.services", "100.00", description = "Vodafone")

        val proj = service.moneyFlowProjection(household.id, today, "category", 3)
        val comp = proj.components.first { it.side == "expense" && it.id == "home.services" }
        // Actual = 100, recurringDue = 0 (Vodafone already seen), discretionaryEstimate = 50 (Vodafone excluded from both avg & current discretionarySpent!).
        assertThat(comp.actual).isEqualByComparingTo("100.00")
        assertThat(comp.recurringDue).isEqualByComparingTo("0.00")
        assertThat(comp.discretionaryEstimate).isEqualByComparingTo("50.00")
        assertThat(comp.projected).isEqualByComparingTo("150.00")

        // Forecast shows source="recurring" at €150.00 (100 recurring + 50 discretionary).
        val beforeDecision = service.forecast(household.id, 1, 3, YearMonth.of(2025, 10))
        val beforePt = beforeDecision.categories.first { it.categoryCode == "home.services" }.projection.first()
        assertThat(beforePt.projectedExpense).isEqualByComparingTo("150.00")
        assertThat(beforePt.source).isEqualTo("recurring")

        // Record "not_recurring" decision for Vodafone -> fingerprint cache automatically invalidates!
        decisions.upsert(
            household.id,
            user,
            RecordSpendingReviewDecisionRequest(
                subjectKind = DecisionSubjectKind.recurring,
                categoryCode = "home.services",
                decisionType = SpendingReviewDecisionType.not_recurring,
                displayLabel = "Vodafone",
                memberKeys = listOf("vodafone"),
                amountAtDecision = BigDecimal("100.00"),
            ),
        )
        val afterDecision = service.forecast(household.id, 1, 3, YearMonth.of(2025, 10))
        val afterPt = afterDecision.categories.first { it.categoryCode == "home.services" }.projection.first()
        assertThat(afterPt.projectedExpense).isEqualByComparingTo("150.00")
        assertThat(afterPt.source).isEqualTo("history")
        assertThat(detectedCache).isNotNull
    }

    private fun template(
        h: Household,
        u: User,
        direction: Direction,
        category: String,
        amount: String,
        dayOfMonth: Short,
        description: String?,
        startDate: LocalDate,
        watermark: LocalDate,
    ): RecurringTemplate {
        val created = recurring.create(
            h.id,
            RecurringTemplateRequest(
                direction = direction,
                categoryCode = category,
                amount = BigDecimal(amount),
                description = description,
                cadence = Cadence.monthly,
                dayOfMonth = dayOfMonth,
                dayOfWeek = null,
                startDate = startDate,
            ),
            u,
        )
        return templates.save(created.apply { lastMaterializedThrough = watermark })
    }

    private fun tx(
        h: Household,
        u: User,
        date: LocalDate,
        direction: Direction,
        category: String,
        amount: String,
        description: String? = null,
        templateId: UUID? = null,
    ) {
        transactions.save(
            Transaction(
                householdId = h.id,
                occurrenceDate = date,
                direction = direction,
                categoryCode = category,
                amount = BigDecimal(amount),
                description = description,
                recurringTemplateId = templateId,
                createdByUserId = u.id,
                updatedByUserId = u.id,
            ),
        )
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "fdr${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
