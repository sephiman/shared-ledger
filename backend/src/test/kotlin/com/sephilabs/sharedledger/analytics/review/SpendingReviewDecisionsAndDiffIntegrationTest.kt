package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.household.admin.HouseholdDataWipeService
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

class SpendingReviewDecisionsAndDiffIntegrationTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val reviewCache: SpendingReviewCache,
    private val aiService: AnalyticsAiReviewService,
    private val decisionRepo: SpendingReviewDecisionRepository,
    private val wipeService: HouseholdDataWipeService,
    private val transactions: com.sephilabs.sharedledger.transaction.TransactionRepository,
    private val categoryService: com.sephilabs.sharedledger.catalog.CategoryService,
    private val recurringTemplates: com.sephilabs.sharedledger.recurring.RecurringTemplateRepository,
    private val dataQueries: SpendingReviewDataQueries,
    private val decisionService: SpendingReviewDecisionService,
) : AuthHttpTestBase() {

    @BeforeEach
    fun clearBefore() = reviewCache.clear()

    @AfterEach
    fun clearAfter() = reviewCache.clear()

    private fun setupHousehold(tag: String): Triple<Cookie, UUID, UUID> {
        val email = newEmail(tag)
        val owner = sessionOf(register(email))
        val meJson = objectMapper.readTree(me(owner).andReturn().response.contentAsString)
        val hid = UUID.fromString(meJson["households"][0]["householdId"].asString())
        val uid = UUID.fromString(meJson["id"].asString())
        return Triple(owner, hid, uid)
    }

    private fun postTx(
        session: Cookie,
        household: UUID,
        date: LocalDate,
        direction: String,
        category: String,
        amount: String,
        description: String?,
    ) {
        val descPart = if (description == null) "null" else "\"$description\""
        mockMvc.perform(
            post("/api/households/$household/transactions")
                .cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"occurrenceDate":"$date","direction":"$direction","categoryCode":"$category","amount":"$amount","description":$descPart}""",
                ),
        ).andExpect(status().isCreated)
    }

    @Test
    fun `decision CRUD invalidates cache, refills freed Savings slot, routes kept hike to What changed, marks unmatched inactive, and wipes on household reset`() {
        val (owner, hid, uid) = setupHousehold("dec-crud")

        // Seed 13 months (May 2025 .. May 2026) of 4 cancellable subscriptions + 1 negotiable utility hike
        val base = YearMonth.of(2025, 5)
        for (i in 0 until 13) {
            val ym = base.plusMonths(i.toLong())
            postTx(owner, hid, ym.atDay(5), "income", "income.salary", "4000.00", "Salary")
            postTx(owner, hid, ym.atDay(6), "outings.subscriptions".let { "expense" }, "outings.subscriptions", "30.00", "SubOne")
            postTx(owner, hid, ym.atDay(7), "expense", "outings.subscriptions", "25.00", "SubTwo")
            postTx(owner, hid, ym.atDay(8), "expense", "outings.subscriptions", "20.00", "SubThree")
            postTx(owner, hid, ym.atDay(9), "expense", "outings.subscriptions", "15.00", "SubFour")
            val utilAmt = if (i < 12) "50.00" else "60.00"
            postTx(owner, hid, ym.atDay(10), "expense", "home.utilities", utilAmt, "PowerCo")
        }

        // Before decisions: top 3 cancellable are SubOne, SubTwo, SubThree (SubFour waits in queue)
        val beforeRes = objectMapper.readTree(
            mockMvc.perform(get("/api/households/$hid/analytics/spending-review/candidates?year=2026&month=5").cookie(owner))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString,
        )
        val beforeSavings = beforeRes.path("skeleton").path("savingsOpportunities").toList().map { it.path("localName").asString("") }
        assertThat(beforeSavings).contains("SubOne", "SubTwo", "SubThree", "PowerCo")
        assertThat(beforeSavings).doesNotContain("SubFour")

        // Record KEEP on SubOne -> cache MUST invalidate, SubOne disappears from Savings, SubFour refills!
        val keepSubOne = objectMapper.readTree(
            mockMvc.perform(
                post("/api/households/$hid/analytics/spending-review/decisions")
                    .cookie(owner).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"subjectKind":"recurring","categoryCode":"outings.subscriptions","decisionType":"keep","displayLabel":"SubOne","memberKeys":["subone"],"amountAtDecision":"30.00"}""",
                    ),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )
        val keepSubOneId = keepSubOne["id"].asString()

        // Record KEEP on PowerCo (negotiable with YoY price hike) -> moves from Savings to What changed as plain fact!
        mockMvc.perform(
            post("/api/households/$hid/analytics/spending-review/decisions")
                .cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"subjectKind":"recurring","categoryCode":"home.utilities","decisionType":"keep","displayLabel":"PowerCo","memberKeys":["powerco"],"amountAtDecision":"60.00"}""",
                ),
        ).andExpect(status().isOk)

        // Record a decision for a subject that no longer exists -> MUST appear as inactive, not silently dropped!
        mockMvc.perform(
            post("/api/households/$hid/analytics/spending-review/decisions")
                .cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"subjectKind":"recurring","categoryCode":"outings.subscriptions","decisionType":"keep","displayLabel":"OldClub","memberKeys":["oldclub"],"amountAtDecision":"19.00"}""",
                ),
        ).andExpect(status().isOk)

        val afterRes = objectMapper.readTree(
            mockMvc.perform(get("/api/households/$hid/analytics/spending-review/candidates?year=2026&month=5").cookie(owner))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString,
        )
        val afterSavings = afterRes.path("skeleton").path("savingsOpportunities").toList().map { it.path("localName").asString("") }
        assertThat(afterSavings).doesNotContain("SubOne", "PowerCo")
        assertThat(afterSavings).contains("SubTwo", "SubThree", "SubFour")

        val afterChanged = afterRes.path("skeleton").path("whatChanged").toList().map { it.path("localName").asString("") }
        assertThat(afterChanged).contains("PowerCo")

        val decisionsList = afterRes.path("decisions").toList()
        assertThat(decisionsList.any { it.path("displayLabel").asString("") == "OldClub" && it.path("status").asString("") == "inactive" }).isTrue()
        assertThat(decisionsList.any { it.path("displayLabel").asString("") == "SubOne" && it.path("status").asString("") == "active" }).isTrue()

        // Undo SubOne decision -> SubOne returns to Savings!
        mockMvc.perform(
            delete("/api/households/$hid/analytics/spending-review/decisions/$keepSubOneId")
                .cookie(owner).with(csrf()),
        ).andExpect(status().isNoContent)

        val afterUndo = objectMapper.readTree(
            mockMvc.perform(get("/api/households/$hid/analytics/spending-review/candidates?year=2026&month=5").cookie(owner))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString,
        )
        assertThat(afterUndo.path("skeleton").path("savingsOpportunities").toList().map { it.path("localName").asString("") }).contains("SubOne")

        // Household data wipe MUST delete all decisions.
        assertThat(decisionRepo.findAllByHouseholdIdOrderByDecidedAtDesc(hid)).isNotEmpty()
        wipeService.wipe(hid, uid)
        assertThat(decisionRepo.findAllByHouseholdIdOrderByDecidedAtDesc(hid)).isEmpty()
    }

    @Test
    fun `unlabelled filter returns only blank or null description transactions on both list and totals`() {
        val (owner, hid, _) = setupHousehold("unlab-filter")
        postTx(owner, hid, LocalDate.of(2026, 5, 10), "expense", "outings.restaurants", "1111.00", null)
        postTx(owner, hid, LocalDate.of(2026, 5, 12), "expense", "outings.restaurants", "44.00", "   ")
        postTx(owner, hid, LocalDate.of(2026, 5, 15), "expense", "outings.restaurants", "80.00", "Bistro Central")

        val listJson = objectMapper.readTree(
            mockMvc.perform(
                get("/api/households/$hid/transactions?from=2026-05-01&to=2026-05-31&direction=expense&categoryCode=outings.restaurants&unlabelled=true")
                    .cookie(owner),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )
        assertThat(listJson["total"].asInt()).isEqualTo(2)
        val amounts = listJson["items"].toList().map { BigDecimal(it["amount"].asString()).stripTrailingZeros().toPlainString() }
        assertThat(amounts).containsExactlyInAnyOrder("1111", "44")

        val totalsJson = objectMapper.readTree(
            mockMvc.perform(
                get("/api/households/$hid/transactions/totals?from=2026-05-01&to=2026-05-31&direction=expense&categoryCode=outings.restaurants&unlabelled=true")
                    .cookie(owner),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )
        assertThat(totalsJson["count"].asInt()).isEqualTo(2)
        assertThat(BigDecimal(totalsJson["expenses"].asString())).isEqualByComparingTo("1155.00")
    }

    @Test
    fun `since last month past-month recomputation matches clock injected at end of previous month and payload keeps privacy guarantees`() {
        val (owner, hid, _) = setupHousehold("clock-eq")
        // Seed monthly charges from Oct 2025 .. Apr 2026 (including a personal P2P payee that stops, and a yearly insurance due soon!)
        val months = (0..6).map { YearMonth.of(2025, 10).plusMonths(it.toLong()) }
        for (ym in months) {
            postTx(owner, hid, ym.atDay(3), "income", "income.salary", "4000.00", "Salary")
            postTx(owner, hid, ym.atDay(8), "expense", "outings.subscriptions", "20.00", "StreamBox")
            // Stops after Feb 2026 -> active in Apr 2026 (gap ~50d <= 105d), stale/stopped in Jun 2026 (gap > 105d)!
            if (!ym.isAfter(YearMonth.of(2026, 2))) {
                postTx(owner, hid, ym.atDay(10), "expense", "outings.hobbies", "35.00", "Juan Perez Bizum NL91ABNA0417164300")
            }
        }
        // Add May and Jun 2026 expenses (StreamBox continues, new GymClub starts Apr-Jun, yearly insurance in Jul 2024 & Jul 2025)
        postTx(owner, hid, LocalDate.of(2024, 7, 15), "expense", "home.insurance_fees", "450.00", "AllianzDirect BV")
        postTx(owner, hid, LocalDate.of(2025, 7, 15), "expense", "home.insurance_fees", "470.00", "AllianzDirect BV")
        postTx(owner, hid, LocalDate.of(2026, 4, 12), "expense", "outings.subscriptions", "49.00", "GymClub")
        postTx(owner, hid, LocalDate.of(2026, 5, 3), "income", "income.salary", "4000.00", "Salary")
        postTx(owner, hid, LocalDate.of(2026, 5, 8), "expense", "outings.subscriptions", "20.00", "StreamBox")
        postTx(owner, hid, LocalDate.of(2026, 5, 12), "expense", "outings.subscriptions", "49.00", "GymClub")
        postTx(owner, hid, LocalDate.of(2026, 6, 3), "income", "income.salary", "4000.00", "Salary")
        postTx(owner, hid, LocalDate.of(2026, 6, 8), "expense", "outings.subscriptions", "24.00", "StreamBox") // price changed 20 -> 24
        postTx(owner, hid, LocalDate.of(2026, 6, 12), "expense", "outings.subscriptions", "49.00", "GymClub") // now 3rd occurrence -> newly detected!

        // 1. Clock-equivalence proof: compute May 2026 with clock pinned at 2026-05-31 vs recomputed asOf=2026-05-31 from Jun 2026 clock!
        val clockMayEnd = Clock.fixed(LocalDate.of(2026, 5, 31).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        val clockJunEnd = Clock.fixed(LocalDate.of(2026, 6, 30).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        val svcAtMayEnd = SavingsCandidatesService(transactions, categoryService, recurringTemplates, dataQueries, decisionService, reviewCache, clockMayEnd)
        val svcAtJunEnd = SavingsCandidatesService(transactions, categoryService, recurringTemplates, dataQueries, decisionService, reviewCache, clockJunEnd)

        val liveMay = svcAtMayEnd.candidates(hid, 2026, 5)
        reviewCache.clear()
        val recomputedMay = svcAtJunEnd.computeForAsOf(hid, YearMonth.of(2026, 5), LocalDate.of(2026, 5, 31))
        assertThat(recomputedMay.candidates).isEqualTo(liveMay.candidates)

        // 2. Verify Since last month + Coming up + Payload privacy on June 2026 review
        val junRes = objectMapper.readTree(
            mockMvc.perform(get("/api/households/$hid/analytics/spending-review/candidates?year=2026&month=6").cookie(owner))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString,
        )
        val slm = junRes["skeleton"]["sinceLastMonth"].toList()
        assertThat(slm.map { it["changeType"].asString() }).contains("stopped_recurring", "new_recurring", "amount_changed")
        val upc = junRes["skeleton"]["upcomingCharges"].toList()
        assertThat(upc.map { it["merchant"].asString() }).containsExactly("AllianzDirect BV")
        assertThat(upc[0]["compareBeforeRenewal"].asBoolean()).isTrue()

        // 3. Extended privacy check: payload JSON for June 2026 MUST NEVER leak "Juan Perez", IBAN, or member email!
        val built = aiService.buildPayloadForPeriod(hid, 2026, 6, ReviewMode.FULL, "en")
        assertThat(built.json).doesNotContain("Juan Perez")
        assertThat(built.json).doesNotContain("NL91ABNA0417164300")
        assertThat(built.json).doesNotContain("clock-eq")
        assertThat(built.json).contains("\"upcomingCharges\"")
        assertThat(built.json).contains("\"sinceLastMonth\"")
    }
}
