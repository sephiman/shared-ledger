package com.sephilabs.sharedledger.fire

import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.snapshot.Snapshot
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValue
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValueId
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRequest
import com.sephilabs.sharedledger.transaction.TransactionService
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** The what-if sandbox inputs the levers and life-event templates rely on, and the guard on events that would do nothing. */
class FireSandboxEventsTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val users: UserRepository,
    private val snapshots: SnapshotRepository,
    private val transactions: TransactionService,
    private val fireService: FireService,
) : AuthHttpTestBase() {

    private val today = LocalDate.now()

    @Test
    fun `a template previewed through the sandbox equals the projection once saved`() {
        val f = fixture()
        val events = listOf(
            FireCashFlowEventInput(today.year + 12, CashFlowEventKind.spending_delta, BigDecimal("-650.00"), "Mortgage ends", template = "mortgage_end"),
            FireCashFlowEventInput(today.year + 3, CashFlowEventKind.lump_sum, BigDecimal("-25000.00"), "New car", template = "big_expense"),
        )
        val preview = fireService.project(f.householdId, FirePreviewRequest(cashFlowEvents = events))

        fireService.update(f.householdId, fireSettingsRequest(cashFlowEvents = events), f.user)
        val saved = fireService.project(f.householdId)

        assertThat(preview.scenarios).isEqualTo(saved.scenarios)
        assertThat(preview.tiers).isEqualTo(saved.tiers)
        // And the preview really moved something: it is not the plan without the events.
        fireService.update(f.householdId, fireSettingsRequest(), f.user)
        assertThat(fireService.project(f.householdId).scenarios).isNotEqualTo(saved.scenarios)
    }

    @Test
    fun `the unsaved settings form previews exactly what saving it would project, and nothing is saved`() {
        val f = fixture()
        addTx(f, YearMonth.from(today).atDay(3), "groceries.groceries", "3000.00")
        val form = fireSettingsRequest().copy(
            spendingLookbackMonths = 3,
            spendingAggregation = SpendingAggregationMode.median,
            excludeCurrentMonth = true,
            safeWithdrawalRatePct = BigDecimal("3.50"),
            fatMultiplier = BigDecimal("2.00"),
        )
        val preview = fireService.project(f.householdId, FirePreviewRequest(settings = form))

        assertThat(preview.spending.lookbackMonths).isEqualTo(3)
        assertThat(preview.spending.aggregation).isEqualTo(SpendingAggregationMode.median)
        assertThat(preview.spending.excludeCurrentMonth).isTrue()
        assertThat(preview.settings.safeWithdrawalRatePct).isEqualByComparingTo("3.50")
        // Nothing was saved: the plan still averages 12 months including this month's extra 3000.
        assertThat(fireService.project(f.householdId).spending.derivedTotalMonthly).isEqualByComparingTo("2250.00")

        fireService.update(f.householdId, form, f.user)
        val saved = fireService.project(f.householdId)
        assertThat(preview.spending).isEqualTo(saved.spending)
        assertThat(preview.tiers).isEqualTo(saved.tiers)
        assertThat(preview.scenarios).isEqualTo(saved.scenarios)
    }

    @Test
    fun `the preview endpoint takes the unsaved form as JSON`() {
        val f = fixture()
        addTx(f, YearMonth.from(today).atDay(3), "groceries.groceries", "3000.00")
        val body = objectMapper.writeValueAsString(
            FirePreviewRequest(settings = fireSettingsRequest().copy(spendingLookbackMonths = 3)),
        )
        mockMvc.perform(
            post("/api/households/${f.householdId}/fire/preview").with(csrf()).cookie(f.session)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.spending.lookbackMonths").value(3))
            .andExpect(jsonPath("$.spending.derivedTotalMonthly").value("3000.00"))
            .andExpect(jsonPath("$.settings.spendingLookbackMonths").value(3))
    }

    @Test
    fun `the preview refuses every invalid form with exactly the error Save returns`() {
        val f = fixture()
        val valid = fireSettingsRequest()
        val invalidForms = mapOf(
            "one-off amount in a year not simulated" to valid.copy(
                cashFlowEvents = listOf(FireCashFlowEventInput(today.year, CashFlowEventKind.lump_sum, BigDecimal("10000.00"), "Bonus")),
            ),
            "no return scenarios" to valid.copy(returnScenarios = emptyList()),
            "negative scenario deviation" to valid.copy(
                returnScenarios = listOf(ReturnScenarioInput(BigDecimal("6.0"), BigDecimal("-1.0"), false, "Base")),
            ),
            "unordered tax brackets" to valid.copy(
                taxBrackets = listOf(TaxBracketInput(BigDecimal("6000.00"), BigDecimal("21.0")), TaxBracketInput(BigDecimal("0.00"), BigDecimal("19.0"))),
            ),
            "no tax brackets" to valid.copy(taxBrackets = emptyList()),
            "bracket rate above 100" to valid.copy(taxBrackets = listOf(TaxBracketInput(BigDecimal("0.00"), BigDecimal("101.0")))),
            "withdrawal rate of zero" to valid.copy(safeWithdrawalRatePct = BigDecimal("0.00")),
            "inflation above 50" to valid.copy(expectedInflationPct = BigDecimal("51.00")),
            "Fat multiplier below 1" to valid.copy(fatMultiplier = BigDecimal("0.50")),
        )

        invalidForms.forEach { (case, form) ->
            val saveError = errorOf(
                put("/api/households/${f.householdId}/fire/settings").content(objectMapper.writeValueAsString(form)), f,
            )
            val previewError = errorOf(
                post("/api/households/${f.householdId}/fire/preview")
                    .content(objectMapper.writeValueAsString(FirePreviewRequest(settings = form))),
                f,
            )
            assertThat(previewError).describedAs(case).isEqualTo(saveError)
        }
        // Save refused all of them: the plan is still the valid fixture.
        assertThat(fireService.project(f.householdId).settings.safeWithdrawalRatePct).isEqualByComparingTo("4.00")
    }

    /** A 400's code, args and per-field messages; preview fields sit under `settings.`, so that prefix is dropped. */
    private fun errorOf(request: MockHttpServletRequestBuilder, f: Fixture): Map<String, Any?> {
        val response = mockMvc.perform(request.with(csrf()).cookie(f.session).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest)
            .andReturn().response.contentAsString
        val body = objectMapper.readTree(response)
        val fields = body["fields"]?.properties()?.associate { (k, v) -> k.removePrefix("settings.") to v.asString() }
        return mapOf("code" to body["code"].asString(), "args" to body["args"]?.toString(), "fields" to fields)
    }

    @Test
    fun `saved events keep the template they came from`() {
        val f = fixture()
        val events = listOf(
            FireCashFlowEventInput(today.year + 5, CashFlowEventKind.spending_delta, BigDecimal("-800.00"), "Part-time", template = "part_time_income"),
            FireCashFlowEventInput(today.year + 9, CashFlowEventKind.spending_delta, BigDecimal("800.00"), "Part-time", template = "part_time_income"),
            FireCashFlowEventInput(today.year + 4, CashFlowEventKind.lump_sum, BigDecimal("5000.00"), "Manual"),
        )
        fireService.update(f.householdId, fireSettingsRequest(cashFlowEvents = events), f.user)

        mockMvc.perform(get("/api/households/${f.householdId}/fire/settings").cookie(f.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cashFlowEvents.length()").value(3))
            .andExpect(jsonPath("$.cashFlowEvents[0].template").value("part_time_income"))
            .andExpect(jsonPath("$.cashFlowEvents[1].amount").value("800.00"))
            .andExpect(jsonPath("$.cashFlowEvents[2].template").doesNotExist())
    }

    @Test
    fun `returns 400 when a one-off amount is dated in a year the projection does not simulate`() {
        val f = fixture()
        val body = objectMapper.writeValueAsString(
            fireSettingsRequest(
                cashFlowEvents = listOf(FireCashFlowEventInput(today.year, CashFlowEventKind.lump_sum, BigDecimal("10000.00"), "Bonus")),
            ),
        )
        mockMvc.perform(
            put("/api/households/${f.householdId}/fire/settings").with(csrf()).cookie(f.session)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("FIRE_LUMP_SUM_NOT_SIMULATED"))
    }

    @Test
    fun `a return shift moves every scenario's mean and nothing is saved`() {
        val f = fixture()
        val shifted = fireService.project(f.householdId, FirePreviewRequest(returnShiftPct = BigDecimal("1.0")))
        assertThat(shifted.scenarios.single().meanPercent).isEqualByComparingTo("7.0")
        assertThat(fireService.project(f.householdId).scenarios.single().meanPercent).isEqualByComparingTo("6.0")
    }

    @Test
    fun `an essential spending what-if overrides the essentials base`() {
        val f = fixture()
        val preview = fireService.project(f.householdId, FirePreviewRequest(monthlyEssentialSpending = BigDecimal("900.00")))
        assertThat(preview.spending.essentialMonthly).isEqualByComparingTo("900.00")
        assertThat(preview.tiers.single { it.key == FireTierKey.lean }.monthlyNetSpending).isEqualByComparingTo("900.00")
    }

    @Test
    fun `the mortgage suggestion is the window's monthly average of the mortgage category`() {
        val f = fixture()
        mockMvc.perform(get("/api/households/${f.householdId}/fire/templates/suggestions").cookie(f.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mortgageMonthly").doesNotExist())

        (0L until 12L).forEach { back -> addTx(f, YearMonth.from(today).minusMonths(back).atDay(2), "home.mortgage", "600.00") }
        mockMvc.perform(get("/api/households/${f.householdId}/fire/templates/suggestions").cookie(f.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mortgageMonthly").value("600.00"))
    }

    // -------------------------------------------------------------------------------------

    private class Fixture(val householdId: UUID, val user: User, val session: Cookie)

    private fun fixture(): Fixture {
        val email = newEmail("fire-sandbox")
        val session = sessionOf(register(email))
        val householdId = UUID.fromString(objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString())
        val f = Fixture(householdId, requireNotNull(users.findByEmailIgnoreCase(email)), session)
        fireService.update(householdId, fireSettingsRequest(), f.user)
        (0L until 12L).forEach { back -> addTx(f, YearMonth.from(today).minusMonths(back).atDay(1), "groceries.groceries", "2000.00") }
        val snapshot = Snapshot(householdId = householdId, snapshotDate = today, createdByUserId = f.user.id, updatedByUserId = f.user.id)
        snapshot.assetValues.add(SnapshotAssetValue(SnapshotAssetValueId(snapshot.id, "etfs"), BigDecimal("200000.00")))
        snapshots.save(snapshot)
        return f
    }

    private fun addTx(f: Fixture, date: LocalDate, category: String, amount: String) {
        transactions.create(
            f.householdId,
            TransactionRequest(occurrenceDate = date, direction = Direction.expense, categoryCode = category, amount = BigDecimal(amount), description = null),
            f.user,
        )
    }
}
