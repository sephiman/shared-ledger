package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.bank.aisuggestion.AI_FEATURES_DISABLED
import com.sephilabs.sharedledger.bank.aisuggestion.ScriptedSuggestionModel
import com.sephilabs.sharedledger.bank.aisuggestion.TogglableAiAvailability
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@ResourceLock("ai-doubles")
class AnalyticsAiReviewEndpointTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val availability: TogglableAiAvailability,
    private val model: ScriptedSuggestionModel,
    private val aiService: AnalyticsAiReviewService,
    private val decisionService: SpendingReviewDecisionService,
    private val reviewCache: SpendingReviewCache,
) : AuthHttpTestBase() {

    @BeforeEach
    fun reset() {
        availability.forced = null
        model.reset()
        reviewCache.clear()
    }

    @AfterEach
    fun restore() {
        availability.forced = null
        model.reset()
        reviewCache.clear()
    }

    private fun createHousehold(tag: String, enableAi: Boolean): Pair<Cookie, UUID> {
        val owner = sessionOf(register(newEmail(tag)))
        val household = UUID.fromString(
            objectMapper.readTree(me(owner).andReturn().response.contentAsString)["households"][0]["householdId"].asString(),
        )
        if (enableAi) {
            mockMvc.perform(
                put("/api/households/$household/ai-suggestions/settings")
                    .cookie(owner).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"enabled":true}"""),
            ).andExpect(status().isOk)
        }
        return owner to household
    }

    private fun postTx(
        owner: Cookie,
        household: UUID,
        date: LocalDate,
        direction: String,
        categoryCode: String,
        amount: String,
        description: String,
        isRefund: Boolean = false,
    ): String {
        val body = """{"occurrenceDate":"$date","direction":"$direction","categoryCode":"$categoryCode","amount":"$amount","description":"$description","isRefund":$isRefund}"""
        val res = mockMvc.perform(
            post("/api/households/$household/transactions")
                .cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return objectMapper.readTree(res)["id"].asString()
    }

    @Test
    fun `deterministic candidates endpoint works with AI off and without key, while AI endpoints enforce 409 and 404`() {
        val (owner, household) = createHousehold("review-gate", enableAi = false)
        val lastComplete = YearMonth.now().minusMonths(1)
        // Seed 4 monthly HBO charges and 3 restaurant visits across 3 months (median > 0)
        for (m in 0L until 4L) {
            postTx(owner, household, lastComplete.minusMonths(m).atDay(10), "expense", "outings.subscriptions", "9.99", "HBO")
        }
        postTx(owner, household, lastComplete.atDay(12), "expense", "outings.restaurants", "45.00", "Carlos Ruiz Bizum")
        postTx(owner, household, lastComplete.minusMonths(1).atDay(12), "expense", "outings.restaurants", "45.00", "Carlos Ruiz Bizum")
        postTx(owner, household, lastComplete.minusMonths(2).atDay(12), "expense", "outings.restaurants", "45.00", "Carlos Ruiz Bizum")

        // With AI toggle OFF: deterministic endpoint is 200 and returns BOTH skeleton and underlying real local names
        val detOff = mockMvc.perform(
            get("/api/households/$household/analytics/spending-review/candidates")
                .param("year", lastComplete.year.toString())
                .param("month", lastComplete.monthValue.toString())
                .cookie(owner),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val detNode = objectMapper.readTree(detOff)
        assertThat(detNode["candidates"]["recurringCharges"].toString()).contains("HBO")
        assertThat(detNode["candidates"]["topNonEssentialMerchants"].toString()).contains("Carlos Ruiz Bizum")
        assertThat(detNode["skeleton"]["mode"].asString()).isEqualTo("full")
        assertThat(detNode["skeleton"]["savingsOpportunities"].toString()).contains("HBO")

        // AI endpoints answer 409 AI_FEATURES_DISABLED when toggle is off
        mockMvc.perform(get("/api/households/$household/analytics/ai-review").cookie(owner))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(AI_FEATURES_DISABLED))
        mockMvc.perform(post("/api/households/$household/analytics/ai-review").cookie(owner).with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(AI_FEATURES_DISABLED))

        // Without AI_API_KEY: deterministic endpoint STILL returns 200; AI endpoints answer 404
        availability.forced = false
        mockMvc.perform(get("/api/households/$household/analytics/spending-review/candidates").cookie(owner))
            .andExpect(status().isOk)
        mockMvc.perform(get("/api/households/$household/analytics/ai-review").cookie(owner))
            .andExpect(status().isNotFound)
        mockMvc.perform(post("/api/households/$household/analytics/ai-review").cookie(owner).with(csrf()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `payload privacy test strips IBANs, excluded counterparties, transaction IDs, purchase dates, raw descriptions and household ID`() {
        val (owner, household) = createHousehold("review-privacy", enableAi = true)
        val lastComplete = YearMonth.now().minusMonths(1)
        val secretIban = "ES9121000418450200051332"
        val txIds = mutableListOf<String>()

        // Recurring merchant that MUST pass + Postcode Loterij merged variants (local only, never leaked to payload)
        for (m in 0L until 4L) {
            txIds += postTx(owner, household, lastComplete.minusMonths(m).atDay(14), "expense", "outings.subscriptions", "11.99", "Spotify")
        }
        val postcodeLabels = listOf("Nat Postcode", "Nat Postcode", "Nat Postcode", "Postcode Loterij", "Nat Postcode Loterij", "National Postcode")
        postcodeLabels.forEachIndexed { idx, label ->
            txIds += postTx(owner, household, lastComplete.minusMonths(idx.toLong()).atDay(21), "expense", "outings.hobbies", "21.00", label)
        }
        // Essential recurring charges: unchanged Florius (summarized in unchangedEssentialRecurring) vs price-hiked Vattenfall (in full)
        for (m in 0L until 14L) {
            txIds += postTx(owner, household, lastComplete.minusMonths(m).atDay(2), "expense", "home.mortgage", "1200.00", "Florius")
            val vatAmt = if (m < 6L) "165.00" else "150.00"
            txIds += postTx(owner, household, lastComplete.minusMonths(m).atDay(5), "expense", "home.utilities", vatAmt, "Vattenfall")
        }
        // Excluded P2P counterparties (Bizum, Tikkie, IBAN, person name) that MUST be anonymized in payload
        for (m in 0L until 4L) {
            txIds += postTx(owner, household, lastComplete.minusMonths(m).atDay(3), "expense", "home.rent", "650.00", "Maria Garcia Lopez Bizum")
            txIds += postTx(owner, household, lastComplete.minusMonths(m).atDay(7), "expense", "outings.restaurants", "32.00", "Tikkie J. van den Berg")
            txIds += postTx(owner, household, lastComplete.minusMonths(m).atDay(11), "expense", "outings.hobbies", "40.00", "Transfer $secretIban")
        }
        val secretNoteDate = lastComplete.atDay(19)
        txIds += postTx(owner, household, secretNoteDate, "expense", "shopping.other", "19.50", "Unique secret raw note xyz99")

        // Income transactions with sensitive employer & person names: must trigger income_change in payload by category ONLY
        for (m in 1L until 12L) {
            txIds += postTx(owner, household, lastComplete.minusMonths(m).atDay(1), "income", "income.salary", "6000.00", "Acme Holdings BV Payroll")
            txIds += postTx(owner, household, lastComplete.minusMonths(m).atDay(8), "income", "income.reimbursements", "150.00", "Jan de Vries Reimbursement")
        }
        txIds += postTx(owner, household, lastComplete.atDay(1), "income", "income.salary", "3800.00", "Acme Holdings BV Payroll")

        // Deterministic candidates expose mergedVariants locally for the Postcode Loterij cluster
        val candJson = mockMvc.perform(
            get("/api/households/$household/analytics/spending-review/candidates")
                .param("year", lastComplete.year.toString())
                .param("month", lastComplete.monthValue.toString())
                .cookie(owner),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val candNode = objectMapper.readTree(candJson)
        val postcodeRec = candNode["candidates"]["recurringCharges"].toList()
            .first { it["merchant"].asString() == "Nat Postcode" }
        assertThat(postcodeRec["mergedVariants"].toList().map { it.asString() })
            .containsExactlyInAnyOrder("Nat Postcode", "Postcode Loterij", "Nat Postcode Loterij", "National Postcode")

        val built = aiService.buildPayloadForPeriod(household, lastComplete.year, lastComplete.monthValue)
        val json = built.json
        val tree = built.tree

        // Compact deterministic writer payload: scope, summaryFacts, findings, unchangedEssentialRecurring
        assertThat(tree.has("forecast12m")).isFalse()
        assertThat(tree.has("concentrationAndVolatility")).isFalse()
        assertThat(tree["summaryFacts"]["figures"].has("f1")).isTrue()
        assertThat(tree["summaryFacts"]["figures"].has("f4")).isTrue()

        val findingsList = tree["findings"].toList()
        val savingsFindings = findingsList.filter { it["section"].asString() == "savings" }
        val subjects = savingsFindings.mapNotNull { it["subject"]?.takeIf { s -> s.isString }?.asString() }
        // Cancellable recurring (Nat Postcode, Spotify) and price-increased negotiable (Vattenfall) appear in Savings
        assertThat(subjects).contains("Nat Postcode", "Vattenfall")
        assertThat(savingsFindings.first { it["subject"]?.asString() == "Nat Postcode" }["action"].asString()).isEqualTo("cancellable")
        assertThat(savingsFindings.first { it["subject"]?.asString() == "Vattenfall" }["action"].asString()).isEqualTo("negotiable")

        // Unchanged essential recurring charges (Florius, Maria Garcia Lopez Bizum rent) are summarized in unchangedEssentialRecurring
        assertThat(subjects).doesNotContain("Florius", "Recurring charge in home.rent")
        val unchangedEss = tree["unchangedEssentialRecurring"]
        assertThat(unchangedEss["count"].asInt()).isEqualTo(2)
        assertThat(unchangedEss["monthlyTotal"].asDouble()).isEqualTo(1850.0)

        // Nothing about merged variants ever enters the outbound payload
        assertThat(json).doesNotContain("mergedVariants")
        assertThat(json).doesNotContain("National Postcode")
        assertThat(json).doesNotContain("Postcode Loterij")
        assertThat(json).doesNotContain("Nat Postcode Loterij")

        // Excluded P2P, employer/payer income names & PII NEVER appear in outbound payload
        val incomeFinding = findingsList.firstOrNull { it["kind"].asString() == "income_change" }
        assertThat(incomeFinding).isNotNull()
        assertThat(incomeFinding!!["categoryCode"].asString()).isEqualTo("income.salary")
        assertThat(incomeFinding.has("subject")).isFalse()
        assertThat(json).doesNotContain("Acme Holdings")
        assertThat(json).doesNotContain("Payroll")
        assertThat(json).doesNotContain("Jan de Vries")
        assertThat(json).doesNotContain(secretIban)
        assertThat(json).doesNotContain("Maria Garcia Lopez")
        assertThat(json).doesNotContain("van den Berg")
        assertThat(json).doesNotContain("Bizum")
        assertThat(json).doesNotContain("Tikkie")
        assertThat(json).doesNotContain("Unique secret raw note xyz99")
        assertThat(json).doesNotContain(household.toString())
        assertThat(json).doesNotContain(secretNoteDate.toString())
        for (txId in txIds) {
            assertThat(json).doesNotContain(txId)
        }
        // Anonymized non-essential recurring charges stay as candidates (not dropped!) and map locally
        assertThat(json).contains("Recurring charge in outings.restaurants")
        assertThat(json).contains("Recurring charge in outings.hobbies")
        assertThat(built.localLabelsJson).contains("Tikkie J. van den Berg")
    }

    @Test
    fun `runs deterministic writer review, falls back cleanly on invalid model prose, preserves delta simulation scenario, supports followups and delete`() {
        val (owner, household) = createHousehold("review-e2e", enableAi = true)
        val lastComplete = YearMonth.now().minusMonths(1)

        // Seed income + non-essential recurring HBO + financial fees + 4m history
        for (m in 0L until 4L) {
            postTx(owner, household, lastComplete.minusMonths(m).atDay(1), "income", "income.salary", "4000.00", "Salary")
            postTx(owner, household, lastComplete.minusMonths(m).atDay(10), "expense", "outings.subscriptions", "14.99", "HBO")
            postTx(owner, household, lastComplete.minusMonths(m).atDay(15), "expense", "financial.fees", "10.00", "BankFee")
        }

        // Preview first to read the exact deterministic finding IDs
        val preview = aiService.buildPayloadForPeriod(household, lastComplete.year, lastComplete.monthValue)
        val hboId = preview.tree["findings"].toList().first { it["subject"]?.asString() == "HBO" }["id"].asString()

        // First call: writer response where HBO uses valid placeholder prose, fee is omitted (falls back to template!),
        // and summary uses valid placeholders; second call: grounded followup citing findings.0.figures.f2.value
        var callCount = 0
        model.responder = {
            callCount++
            if (callCount == 1) {
                ScriptedSuggestionModel.answer(
                    """
                    {
                      "summary": "Expenses came to {f1} against a {f2} average while income was {f4} against {f5}, leaving {f7} saved — above your {f8} average thanks to higher income ({f9} compared with {f10}).",
                      "findings": [
                        {
                          "id": "$hboId",
                          "title": "HBO subscription cost",
                          "detail": "HBO runs {f1} a month, or {f2} a year."
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            } else {
                ScriptedSuggestionModel.answer(
                    """
                    {
                      "answer": "HBO costs €179.88 per year.",
                      "citations": [{"path": "findings.0.figures.f2.value", "value": 179.88}]
                    }
                    """.trimIndent(),
                )
            }
        }

        val createdJson = mockMvc.perform(
            post("/api/households/$household/analytics/ai-review")
                .cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"year":${lastComplete.year},"month":${lastComplete.monthValue}}"""),
        ).andExpect(status().isAccepted).andReturn().response.contentAsString

        val runId = objectMapper.readTree(createdJson)["id"].asString()

        val listedJson = mockMvc.perform(get("/api/households/$household/analytics/ai-review").cookie(owner))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val run = objectMapper.readTree(listedJson)[0]
        assertThat(run["status"].asString()).isEqualTo("completed")
        assertThat(run["findingsDropped"].asInt()).isEqualTo(0)
        // Both HBO (model-written) AND financial.fees (omitted -> deterministic template fallback!) are present in order!
        assertThat(run["savingsOpportunities"].size()).isEqualTo(2)
        assertThat(run["savingsOpportunities"][0]["localName"].asString()).isEqualTo("HBO")
        assertThat(run["savingsOpportunities"][0]["detail"].asString()).contains("€14.99").contains("€179.88")
        // Deterministic scenario reduces category by HBO's monthly cost (-14.99), never freezing the whole category
        assertThat(run["savingsOpportunities"][0]["simulationScenario"]["freeze"].size()).isEqualTo(0)
        assertThat(run["savingsOpportunities"][0]["simulationScenario"]["adjustments"][0]["value"].asDouble()).isEqualTo(-14.99)
        assertThat(run["savingsOpportunities"][0]["txFilter"]["q"].asString()).isEqualTo("HBO")
        assertThat(run["modelWrittenCount"].asInt()).isEqualTo(2) // summary + HBO
        assertThat(run["templateFallbackCount"].asInt()).isGreaterThanOrEqualTo(1) // omitted fee fell back to template
        assertThat(run.has("strengths")).isFalse()

        // Follow-up grounding still works against payloadJson
        mockMvc.perform(
            post("/api/households/$household/analytics/ai-review/$runId/followups")
                .cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"question":"Which recurring charges can I cut?"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.citationsTotal").value(1))
            .andExpect(jsonPath("$.citationsUngrounded").value(0))

        // Delete
        mockMvc.perform(delete("/api/households/$household/analytics/ai-review/$runId").cookie(owner).with(csrf()))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/households/$household/analytics/ai-review").cookie(owner))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Autowired private lateinit var reviewRuns: AnalyticsAiReviewRunRepository
    @Autowired private lateinit var householdMembers: com.sephilabs.sharedledger.household.HouseholdMemberRepository
    @Autowired private lateinit var usersRepo: com.sephilabs.sharedledger.identity.user.UserRepository

    @Test
    fun `payload-preview is owner-only and returns bytes identical to the payload sent to the provider`() {
        val (owner, household) = createHousehold("review-preview", enableAi = true)
        val memberEmail = newEmail("review-preview-member")
        val member = sessionOf(register(memberEmail))
        val memberUserId = usersRepo.findByEmailIgnoreCase(memberEmail)!!.id
        householdMembers.save(
            com.sephilabs.sharedledger.household.HouseholdMember(
                com.sephilabs.sharedledger.household.HouseholdMemberId(household, memberUserId),
                com.sephilabs.sharedledger.household.HouseholdRole.member,
            ),
        )
        val lastComplete = YearMonth.now().minusMonths(1)

        for (m in 0L until 4L) {
            postTx(owner, household, lastComplete.minusMonths(m).atDay(1), "income", "income.salary", "4000.00", "Salary")
            postTx(owner, household, lastComplete.minusMonths(m).atDay(10), "expense", "outings.subscriptions", "14.99", "HBO")
        }

        // Non-owner member is forbidden (403).
        mockMvc.perform(
            get("/api/households/$household/analytics/ai-review/payload-preview")
                .param("year", lastComplete.year.toString())
                .param("month", lastComplete.monthValue.toString())
                .cookie(member),
        ).andExpect(status().isForbidden)

        // Owner receives exact payload preview bytes.
        val previewJson = mockMvc.perform(
            get("/api/households/$household/analytics/ai-review/payload-preview")
                .param("year", lastComplete.year.toString())
                .param("month", lastComplete.monthValue.toString())
                .cookie(owner),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val previewNode = objectMapper.readTree(previewJson)
        val previewPayload = previewNode["payloadJson"].asString()
        val previewTree = objectMapper.readTree(previewPayload)
        val previewBytes = previewPayload.toByteArray(Charsets.UTF_8)
        assertThat(previewNode["byteSize"].asInt()).isEqualTo(previewBytes.size)
        assertThat(previewTree.has("forecast12m")).isFalse()
        assertThat(previewTree.has("concentrationAndVolatility")).isFalse()
        assertThat(previewPayload).doesNotContain("mergedVariants")

        // Immediately start a real run and capture the exact ChatPrompt sent to the model.
        var capturedUserPrompt: String? = null
        model.responder = { prompt ->
            capturedUserPrompt = prompt.user
            ScriptedSuggestionModel.answer("""{"summary":null,"findings":[]}""")
        }
        val created = mockMvc.perform(
            post("/api/households/$household/analytics/ai-review")
                .cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"year":${lastComplete.year},"month":${lastComplete.monthValue}}"""),
        ).andExpect(status().isAccepted).andReturn().response.contentAsString
        val runId = UUID.fromString(objectMapper.readTree(created)["id"].asString())
        val storedRun = reviewRuns.findById(runId).orElseThrow()

        // Bytes in preview MUST be byte-for-byte identical to the payload inside the provider request body,
        // even after the run's payload_json round-trips through PostgreSQL JSONB.
        val canonicalStoredBytes = com.sephilabs.sharedledger.ai.AiJsonUtils
            .canonicalJson(objectMapper.readTree(storedRun.payloadJson))
            .toByteArray(Charsets.UTF_8)
        assertThat(canonicalStoredBytes).isEqualTo(previewBytes)
        assertThat(capturedUserPrompt!!.toByteArray(Charsets.UTF_8))
            .isEqualTo(AnalyticsAiReviewPrompt.user(previewPayload).toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `blocks empty or stale month with lastMonthWithData, allows savings_only review on 12m data, and rejects future period`() {
        val (owner, household) = createHousehold("review-stale", enableAi = true)
        val lastComplete = YearMonth.now().minusMonths(1)
        // Seed expenses 4..6 months ago (so last 2 complete months are empty/stale, while 12m has recurring HBO!)
        val oldMonth = lastComplete.minusMonths(4)
        for (offset in 0L..2L) {
            postTx(owner, household, oldMonth.minusMonths(offset).atDay(10), "expense", "outings.subscriptions", "14.99", "HBO")
        }

        mockMvc.perform(
            get("/api/households/$household/analytics/spending-review/candidates")
                .param("year", lastComplete.year.toString())
                .param("month", lastComplete.monthValue.toString())
                .cookie(owner),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period.staleSpendingSince").value(oldMonth.toString()))
            .andExpect(jsonPath("$.period.periodHasNoExpenses").value(true))
            .andExpect(jsonPath("$.period.lastMonthWithData").value(oldMonth.toString()))
            .andExpect(jsonPath("$.period.reviewBlockedReason").value("empty_or_stale"))
            .andExpect(jsonPath("$.skeleton.mode").value("blocked"))

        // Full review of empty/stale month is refused with 400 AI_REVIEW_PERIOD_EMPTY_OR_STALE
        mockMvc.perform(
            post("/api/households/$household/analytics/ai-review")
                .cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"year":${lastComplete.year},"month":${lastComplete.monthValue}}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("AI_REVIEW_PERIOD_EMPTY_OR_STALE"))

        // Savings-only review on 12m data succeeds with summary=null and whatChanged=[]
        model.responder = { ScriptedSuggestionModel.answer("""{"summary":null,"findings":[]}""") }
        val savOnly = mockMvc.perform(
            post("/api/households/$household/analytics/ai-review")
                .cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"year":${lastComplete.year},"month":${lastComplete.monthValue},"mode":"savings_only"}"""),
        ).andExpect(status().isAccepted).andReturn().response.contentAsString
        val savRun = objectMapper.readTree(savOnly)
        assertThat(savRun["scope"]["reviewKind"].asString()).isEqualTo("savings_only")
        assertThat(savRun["summary"].isNull).isTrue()
        assertThat(savRun["savingsOpportunities"].size()).isEqualTo(1)
        assertThat(savRun["whatChanged"].size()).isEqualTo(0)
        assertThat(savRun["budgetFindings"].size()).isEqualTo(0)

        // Future month rejected with 400
        val nextMonth = YearMonth.now().plusMonths(1)
        mockMvc.perform(
            get("/api/households/$household/analytics/spending-review/candidates")
                .param("year", nextMonth.year.toString())
                .param("month", nextMonth.monthValue.toString())
                .cookie(owner),
        ).andExpect(status().isBadRequest)
    }

    @Autowired private lateinit var transactions: com.sephilabs.sharedledger.transaction.TransactionRepository
    @Autowired private lateinit var categoryService: com.sephilabs.sharedledger.catalog.CategoryService
    @Autowired private lateinit var recurringTemplates: com.sephilabs.sharedledger.recurring.RecurringTemplateRepository
    @Autowired private lateinit var dataQueries: SpendingReviewDataQueries

    @Test
    fun `enforces AI_REVIEW_MIN_DAYS_ELAPSED on pace review, separates zero-median one-offs from habitual savings, and invalidates cache on write`() {
        val (owner, household) = createHousehold("review-pace-lumpy", enableAi = true)
        val day5 = LocalDate.of(2026, 11, 5)
        val day7 = LocalDate.of(2026, 11, 7)
        val clockDay5 = java.time.Clock.fixed(day5.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneId.systemDefault())
        val clockDay7 = java.time.Clock.fixed(day7.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneId.systemDefault())

        // Seed 6 months:
        // - outings.travel has 2 peak months of 600 (median=0) -> MUST land in oneOffCategories, NEVER lumpyCategories!
        postTx(owner, household, LocalDate.of(2026, 6, 10), "expense", "outings.travel", "600.00", "SummerTrip")
        postTx(owner, household, LocalDate.of(2026, 9, 10), "expense", "outings.travel", "600.00", "AutumnTrip")
        // - outings.restaurants has 6 active months (4 quiet months of 50, 2 peak months of 650 -> median=50 > 0, mean=250, trim=200/mo) -> habitual!
        for (m in listOf(5, 6, 7, 8)) {
            postTx(owner, household, LocalDate.of(2026, m, 12), "expense", "outings.restaurants", "50.00", "Bistro")
        }
        postTx(owner, household, LocalDate.of(2026, 9, 12), "expense", "outings.restaurants", "650.00", "Bistro")
        postTx(owner, household, LocalDate.of(2026, 10, 12), "expense", "outings.restaurants", "650.00", "Bistro")
        // - non-essential recurring (€120/mo) so recurring + habitual crosses €300/mo target
        for (m in 7..10) {
            postTx(owner, household, LocalDate.of(2026, m, 5), "expense", "outings.hobbies", "120.00", "GolfClub")
        }

        val svcDay5 = SavingsCandidatesService(transactions, categoryService, recurringTemplates, dataQueries, decisionService, reviewCache, clockDay5)
        val pace5 = svcDay5.candidates(household, 2026, 11)
        assertThat(pace5.period.reviewKind).isEqualTo("pace_review")
        assertThat(pace5.period.daysElapsed).isEqualTo(5)
        assertThat(pace5.period.paceReviewEligible).isFalse()

        val svcDay7 = SavingsCandidatesService(transactions, categoryService, recurringTemplates, dataQueries, decisionService, reviewCache, clockDay7)
        val pace7 = svcDay7.candidates(household, 2026, 11)
        assertThat(pace7.period.reviewKind).isEqualTo("pace_review")
        assertThat(pace7.period.daysElapsed).isEqualTo(7)
        assertThat(pace7.period.paceReviewEligible).isTrue()

        // Verify zero-median outings.travel is in oneOffCategories (NOT lumpyCategories), and habitual outings.restaurants IS in lumpyCategories
        val oct = svcDay7.candidates(household, 2026, 10)
        assertThat(oct.candidates.oneOffCategories.map { it.categoryCode }).contains("outings.travel")
        assertThat(oct.candidates.lumpyCategories.map { it.categoryCode }).doesNotContain("outings.travel")
        assertThat(oct.candidates.lumpyCategories).anySatisfy {
            assertThat(it.categoryCode).isEqualTo("outings.restaurants")
            assertThat(it.monthlyMedian).isGreaterThan(java.math.BigDecimal.ZERO)
            assertThat(it.trimToMedianMonthly).isGreaterThan(java.math.BigDecimal("100.00"))
        }
        val combo300 = oct.candidates.cutCombinations.first { it.targetMonthly == 300 }
        assertThat(combo300.reached).isTrue()
        assertThat(combo300.combinedMonthlySaving).isGreaterThanOrEqualTo(java.math.BigDecimal("300.00"))

        // Cache invalidation: adding a new financial.fees transaction produces a fresh fingerprint & updates candidates
        assertThat(oct.candidates.feesAndInterest.fees).isEmpty()
        postTx(owner, household, LocalDate.of(2026, 10, 20), "expense", "financial.fees", "60.00", "CardFee")
        val octAfter = svcDay7.candidates(household, 2026, 10)
        assertThat(octAfter.candidates.feesAndInterest.fees).hasSize(1)
        assertThat(octAfter.candidates.feesAndInterest.fees[0].potentialMonthlySaving).isPositive()
    }

    @Test
    fun `active month count counts distinct trailing-12m calendar months with positive net spend, collapsing multi-tx months and excluding zero, fully-refunded and out-of-window months`() {
        val (owner, household) = createHousehold("review-active-months", enableAi = false)
        val period = YearMonth.of(2026, 5) // trailing 12m window = 2025-06 .. 2026-05

        // Outside the 12-month window (2025-05, 13th month back): must NOT count as active
        postTx(owner, household, LocalDate.of(2025, 5, 15), "expense", "outings.restaurants", "100.00", "OldVisit")

        // 6 single-tx months (2025-06 .. 2025-11) = 6 active months
        for (m in 6..11) {
            postTx(owner, household, LocalDate.of(2025, m, 10), "expense", "outings.restaurants", "80.00", "Bistro")
        }
        // 1 multi-tx month (2025-12: 3 expenses summing to €80) = counts as 1 active month, NOT 3
        postTx(owner, household, LocalDate.of(2025, 12, 3), "expense", "outings.restaurants", "30.00", "Bistro")
        postTx(owner, household, LocalDate.of(2025, 12, 12), "expense", "outings.restaurants", "25.00", "Bistro")
        postTx(owner, household, LocalDate.of(2025, 12, 22), "expense", "outings.restaurants", "25.00", "Bistro")

        // 2026-01: €50 expense fully offset by -€50 refund (net €0) -> must NOT count as active
        postTx(owner, household, LocalDate.of(2026, 1, 5), "expense", "outings.restaurants", "50.00", "CancelledDinner")
        postTx(owner, household, LocalDate.of(2026, 1, 8), "expense", "outings.restaurants", "-50.00", "CancelledDinnerRefund", isRefund = true)

        // 2026-02: €100 expense with -€20 partial refund (net €80 > 0) -> MUST count as 1 active month
        postTx(owner, household, LocalDate.of(2026, 2, 10), "expense", "outings.restaurants", "100.00", "PartialRefundDinner")
        postTx(owner, household, LocalDate.of(2026, 2, 12), "expense", "outings.restaurants", "-20.00", "PartialRefundCredit", isRefund = true)

        // 2026-03: no transactions (€0) -> must NOT count as active

        // 2026-04 & 2026-05: 2 spike months (€400 and €900) = 2 active months
        postTx(owner, household, LocalDate.of(2026, 4, 15), "expense", "outings.restaurants", "400.00", "Celebration")
        postTx(owner, household, LocalDate.of(2026, 5, 15), "expense", "outings.restaurants", "900.00", "BigDinner")

        // Expected 12-month series (2025-06..2026-05):
        // [80, 80, 80, 80, 80, 80, 80, 0, 80, 0, 400, 900] -> 10 active months, total=1940, mean=161.67, median=80.00, trim=81.67/mo
        val resJson = mockMvc.perform(
            get("/api/households/$household/analytics/spending-review/candidates")
                .param("year", period.year.toString())
                .param("month", period.monthValue.toString())
                .cookie(owner),
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val lumpy = objectMapper.readTree(resJson)["candidates"]["lumpyCategories"].toList()
        val rest = lumpy.first { it["categoryCode"].asString() == "outings.restaurants" }
        assertThat(rest["nonZeroMonths"].asInt()).isEqualTo(10)
        assertThat(java.math.BigDecimal(rest["monthlyMedian"].asString())).isEqualByComparingTo(java.math.BigDecimal("80.00"))
        assertThat(java.math.BigDecimal(rest["monthlyMean"].asString())).isEqualByComparingTo(java.math.BigDecimal("161.67"))
        assertThat(java.math.BigDecimal(rest["trimToMedianMonthly"].asString())).isEqualByComparingTo(java.math.BigDecimal("81.67"))
    }

    @Test
    fun `explains income shortfall against median income with an income mover and flags under-sized budgets using six-month averages`() {
        val (owner, household) = createHousehold("review-income-budget-fit", enableAi = false)
        // Seed 14 months (2025-04..2026-05) so both March 2026 and May 2026 have full 12-month trailing windows:
        // normal months €6,000 salary, 2 bonus months (€12,000 in Jan/Feb), and May (€4,500).
        // Seed transport.car_maintenance concentrated in 4 of the 12 months (median €0, 4/12 > €50 budget, 100% concentrated).
        val windowStart = YearMonth.of(2025, 4)
        for (i in 0L until 14L) {
            val ym = windowStart.plusMonths(i)
            val salary = when (ym) {
                YearMonth.of(2026, 1), YearMonth.of(2026, 2) -> "12000.00"
                YearMonth.of(2026, 5) -> "4500.00"
                else -> "6000.00"
            }
            postTx(owner, household, ym.atDay(1), "income", "income.salary", salary, "Secret Employer Corp")
            postTx(owner, household, ym.atDay(10), "expense", "groceries.groceries", "500.00", "Market")
            val carBill = when (ym) {
                YearMonth.of(2025, 9) -> "400.00"
                YearMonth.of(2026, 1) -> "700.00"
                YearMonth.of(2026, 4) -> "600.00"
                YearMonth.of(2026, 5) -> "500.00"
                else -> null
            }
            if (carBill != null) {
                postTx(owner, household, ym.atDay(15), "expense", "transport.car_maintenance", carBill, "Garage")
            }
        }

        // Create €50 monthly budget on transport.car_maintenance for May 2026
        mockMvc.perform(
            put("/api/households/$household/budgets")
                .cookie(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"year":2026,"month":5,"categoryCode":"transport.car_maintenance","amount":"50.00"}]}"""),
        ).andExpect(status().isOk)

        // May 2026: income €4,500 is below BOTH mean (€6,875) and typical month (€6,000)
        val mayJson = mockMvc.perform(
            get("/api/households/$household/analytics/spending-review/candidates")
                .param("year", "2026")
                .param("month", "5")
                .cookie(owner),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val maySkeleton = objectMapper.readTree(mayJson)["skeleton"]
        assertThat(maySkeleton["summary"].asString()).contains("€6,000").contains("€1,500")
        val firstChange = maySkeleton["whatChanged"][0]
        assertThat(firstChange["categoryCode"].asString()).isEqualTo("income.salary")
        assertThat(firstChange["changeKind"].asString()).isEqualTo("drop")
        assertThat(firstChange["detail"].asString()).contains("€4,500").contains("€6,000").doesNotContain("Secret Employer")
        assertThat(firstChange["txFilter"]["direction"].asString()).isEqualTo("income")

        val carBudget = maySkeleton["budgetFindings"][0]
        assertThat(carBudget["categoryCode"].asString()).isEqualTo("transport.car_maintenance")
        assertThat(carBudget["title"].asString()).contains("annual budget")
        assertThat(carBudget["detail"].asString())
            .contains("4 of the last 12 months")
            .contains("€216.67")
            .contains("10×")
            .contains("annual budget fits")

        // March 2026 (€6,000 salary, €500 expenses): normal salary month whose mean is lifted by Jan/Feb €12,000 bonuses!
        // Diagnosis is decided against median income (€6,000) & median savings (€5,500) -> NEVER says "lower income", no income_change drop!
        val marJson = mockMvc.perform(
            get("/api/households/$household/analytics/spending-review/candidates")
                .param("year", "2026")
                .param("month", "3")
                .cookie(owner),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val marSkeleton = objectMapper.readTree(marJson)["skeleton"]
        assertThat(marSkeleton["summary"].asString())
            .doesNotContain("lower income")
            .contains("typical €6,000 month")
        assertThat(marSkeleton["whatChanged"].toList().none { it["categoryCode"]?.asString() == "income.salary" }).isTrue()
    }
}
