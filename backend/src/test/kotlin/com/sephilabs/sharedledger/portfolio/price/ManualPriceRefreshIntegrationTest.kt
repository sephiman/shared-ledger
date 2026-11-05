package com.sephilabs.sharedledger.portfolio.price

import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingProvider
import com.sephilabs.sharedledger.portfolio.HoldingRequest
import com.sephilabs.sharedledger.portfolio.HoldingService
import com.sephilabs.sharedledger.portfolio.LotRequest
import com.sephilabs.sharedledger.portfolio.LotType
import com.sephilabs.sharedledger.portfolio.StubEquityProvider
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Stubbed providers, and the test executor runs each triggered refresh inline, so a trigger has finished
 *  by the time it returns. Every holding is created while the stub has no history for it, so nothing is
 *  stored (and nothing counts as freshly fetched) until a test seeds it. */
@ResourceLock("stub-price-providers")
class ManualPriceRefreshIntegrationTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val service: ManualPriceRefreshService,
    private val refresh: PriceRefreshService,
    private val holdingService: HoldingService,
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val prices: PricePointRepository,
    private val stubEquity: StubEquityProvider,
) : AuthHttpTestBase() {

    private val today = LocalDate.now()

    @AfterEach
    fun resetStub() {
        stubEquity.duringHistory = {}
        stubEquity.rateLimitedFor.clear()
    }

    // --- API ----------------------------------------------------------------------------------

    @Test
    fun `a click starts a run and the next one inside the cooldown is refused with when it reopens`() {
        val session = sessionOf(register(newEmail("refresh-now")))
        val household = householdOf(session)

        mockMvc.perform(post(path(household)).with(csrf()).cookie(session))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.outcome").value("started"))
            .andExpect(jsonPath("$.status.running").value(false))
            .andExpect(jsonPath("$.status.nextAllowedAt").isNotEmpty)

        mockMvc.perform(post(path(household)).with(csrf()).cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.outcome").value("cooldown"))

        mockMvc.perform(get(path(household)).cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.lastFinishedAt").isNotEmpty)
            .andExpect(jsonPath("$.nextAllowedAt").isNotEmpty)
            .andExpect(jsonPath("$.equitiesStaleOnly").value(false))
    }

    @Test
    fun `a household that never refreshed can refresh right away`() {
        val session = sessionOf(register(newEmail("refresh-fresh")))

        mockMvc.perform(get(path(householdOf(session))).cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.lastFinishedAt").isEmpty)
            .andExpect(jsonPath("$.nextAllowedAt").isEmpty)
    }

    // --- Limits -------------------------------------------------------------------------------

    @Test
    fun `one household's cooldown does not hold back another, and it lifts after five minutes`() {
        val (_, first) = seed()
        val (_, second) = seed()

        assertThat(service.trigger(first.id).outcome).isEqualTo(ManualRefreshOutcome.STARTED)
        val again = service.trigger(first.id)
        assertThat(again.outcome).isEqualTo(ManualRefreshOutcome.COOLDOWN)
        assertThat(again.status.nextAllowedAt).isAfter(Instant.now().plusSeconds(290))

        assertThat(service.trigger(second.id).outcome).isEqualTo(ManualRefreshOutcome.STARTED)
        assertThat(service.trigger(first.id, Instant.now().plusSeconds(301)).outcome)
            .isEqualTo(ManualRefreshOutcome.STARTED)
    }

    @Test
    fun `a click while the household's run is in flight is reported as already running`() {
        val (user, household) = seed()
        val ps = etf(household.id, user, "RUN")
        stubEquity.history[ps] = mutableListOf(DailyPrice(today, BigDecimal("10")))
        var inFlight: ManualRefreshTrigger? = null
        stubEquity.duringHistory = { inFlight = inFlight ?: service.trigger(household.id) }

        service.trigger(household.id)

        assertThat(inFlight!!.outcome).isEqualTo(ManualRefreshOutcome.ALREADY_RUNNING)
        assertThat(inFlight!!.status.running).isTrue()
        assertThat(service.status(household.id).running).isFalse()
    }

    @Test
    fun `only the series the household holds are fetched`() {
        val (user, mine) = seed()
        val (otherUser, theirs) = seed()
        val own = etf(mine.id, user, "OWN")
        val foreign = etf(theirs.id, otherUser, "FOR")
        stubEquity.history[own] = mutableListOf(DailyPrice(today, BigDecimal("10")))
        stubEquity.history[foreign] = mutableListOf(DailyPrice(today, BigDecimal("20")))

        val calls = callsDuring { service.trigger(mine.id) }

        assertThat(calls.of(own)).isNotEmpty
        assertThat(calls.of(foreign)).isEmpty()
    }

    @Test
    fun `a symbol this household closed but another still holds is refreshed up to today`() {
        val (user, closer) = seed()
        val (otherUser, holder) = seed()
        val ps = "SHR${System.nanoTime() % 100000}.DE"
        etf(closer.id, user, "SHR", ps, lotDaysAgo = 20)
        sell(closer.id, user, ps, daysAgo = 15)
        etf(holder.id, otherUser, "SHR", ps, lotDaysAgo = 20)
        stubEquity.history[ps] = mutableListOf(DailyPrice(today.minusDays(20), BigDecimal("10")), DailyPrice(today, BigDecimal("12")))

        val calls = callsDuring { service.trigger(closer.id) }

        // Judged on this household alone the series would stop a week past its close.
        assertThat(calls.of(ps).map { it.third }).contains(today)
    }

    @Test
    fun `a series fetched in the last ten minutes is skipped, an older one is fetched`() {
        val (user, household) = seed()
        val recent = etf(household.id, user, "REC")
        val older = etf(household.id, user, "OLD")
        storedPrice(recent, today.minusDays(1), fetchedAt = Instant.now().minusSeconds(60))
        storedPrice(older, today.minusDays(1), fetchedAt = Instant.now().minus(Duration.ofHours(1)))
        stubEquity.history[recent] = mutableListOf(DailyPrice(today, BigDecimal("10")))
        stubEquity.history[older] = mutableListOf(DailyPrice(today, BigDecimal("10")))

        val calls = callsDuring { service.trigger(household.id) }

        assertThat(calls.of(recent)).isEmpty()
        assertThat(calls.of(older)).isNotEmpty
    }

    @Test
    fun `with a quota-limited provider only stale equity series are fetched`() {
        val (user, household) = seed()
        val fresh = etf(household.id, user, "FRS", lotDaysAgo = 2)
        val stale = etf(household.id, user, "STL", lotDaysAgo = 10)
        val anHourAgo = Instant.now().minus(Duration.ofHours(1))
        storedPrice(fresh, today.minusDays(2), fetchedAt = anHourAgo)
        storedPrice(stale, today.minusDays(10), fetchedAt = anHourAgo)

        val calls = callsDuring {
            refresh.refreshEquities(
                today,
                ManualRefreshScope(household.id, skipFetchedSince = Instant.now().minusSeconds(600), equitiesStaleOnly = true),
            )
        }

        assertThat(calls.of(fresh)).isEmpty()
        assertThat(calls.of(stale)).isNotEmpty
    }

    @Test
    fun `a 429 stops the run and keeps every household's manual runs off that provider`() {
        // A run's clock is its trigger instant: a day back, so the back-off is long over for every other test.
        val at = Instant.now().minus(Duration.ofDays(1))
        val (user, limited) = seed()
        val (otherUser, next) = seed()
        val throttled = etf(limited.id, user, "THR")
        val later = etf(next.id, otherUser, "LTR")
        stubEquity.rateLimitedFor += throttled
        stubEquity.history[later] = mutableListOf(DailyPrice(today, BigDecimal("10")))

        val calls = callsDuring {
            service.trigger(limited.id, at)
            service.trigger(next.id, at.plusSeconds(60))
        }

        assertThat(calls.of(throttled)).hasSize(1)
        assertThat(calls.of(later)).isEmpty()
        assertThat(service.status(next.id).running).isFalse()
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun etf(
        householdId: UUID,
        user: User,
        prefix: String,
        providerSymbol: String = "$prefix${System.nanoTime() % 100000}.DE",
        lotDaysAgo: Long = 5,
    ): String {
        val holding = holdingService.create(
            householdId,
            HoldingRequest(
                assetClass = HoldingAssetClass.etf,
                symbol = providerSymbol.substringBefore('.'),
                provider = HoldingProvider.yahoo,
                providerSymbol = providerSymbol,
            ),
            user,
        )
        holdingService.addLot(
            householdId,
            holding.id,
            LotRequest(tradedOn = today.minusDays(lotDaysAgo), quantity = BigDecimal("1"), unitPrice = BigDecimal("100")),
            user,
        )
        return providerSymbol
    }

    private fun sell(householdId: UUID, user: User, providerSymbol: String, daysAgo: Long) {
        val holding = holdingService.list(householdId).single { it.providerSymbol == providerSymbol }
        holdingService.addLot(
            householdId,
            holding.id,
            LotRequest(type = LotType.SELL, tradedOn = today.minusDays(daysAgo), quantity = BigDecimal("1"), unitPrice = BigDecimal("110")),
            user,
        )
    }

    private fun storedPrice(providerSymbol: String, date: LocalDate, fetchedAt: Instant) {
        prices.save(
            PricePoint(
                provider = "yahoo",
                providerSymbol = providerSymbol,
                currency = "EUR",
                price = BigDecimal("10"),
                priceDate = date,
                asOf = fetchedAt,
                fetchedAt = fetchedAt,
            ),
        )
    }

    // Linking and adding lots fire their own backfill through the same stub, so only count the calls [block] makes.
    private fun callsDuring(block: () -> Unit): List<Triple<String, LocalDate, LocalDate>> {
        val before = stubEquity.historyCalls.size
        block()
        return stubEquity.historyCalls.drop(before)
    }

    private fun List<Triple<String, LocalDate, LocalDate>>.of(providerSymbol: String) = filter { it.first == providerSymbol }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "mr${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }

    private fun path(household: UUID) = "/api/households/$household/portfolio/refresh-prices"

    private fun householdOf(session: Cookie): UUID = UUID.fromString(
        objectMapper.readTree(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString())
}
