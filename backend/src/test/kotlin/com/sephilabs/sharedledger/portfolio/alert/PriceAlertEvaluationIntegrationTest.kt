package com.sephilabs.sharedledger.portfolio.alert

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.notification.RecordingTelegramClient
import com.sephilabs.sharedledger.notification.TelegramCrypto
import com.sephilabs.sharedledger.notification.TelegramSettings
import com.sephilabs.sharedledger.notification.TelegramSettingsRepository
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingProvider
import com.sephilabs.sharedledger.portfolio.HoldingRepository
import com.sephilabs.sharedledger.portfolio.HoldingRequest
import com.sephilabs.sharedledger.portfolio.HoldingService
import com.sephilabs.sharedledger.portfolio.LotRequest
import com.sephilabs.sharedledger.portfolio.LotType
import com.sephilabs.sharedledger.portfolio.StubCryptoProvider
import com.sephilabs.sharedledger.portfolio.StubEquityProvider
import com.sephilabs.sharedledger.portfolio.price.DailyPrice
import com.sephilabs.sharedledger.portfolio.price.PricePoint
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import com.sephilabs.sharedledger.portfolio.price.PriceRefreshService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ResourceLock("stub-price-providers")
@ResourceLock("recording-telegram")
@ResourceLock("equity-provider-migration")
class PriceAlertEvaluationIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val settingsRepo: TelegramSettingsRepository,
    private val crypto: TelegramCrypto,
    private val holdingService: HoldingService,
    private val holdingRepo: HoldingRepository,
    private val prices: PricePointRepository,
    private val alertService: PriceAlertService,
    private val alertRepo: PriceAlertRepository,
    private val evaluator: PriceAlertEvaluator,
    private val refreshService: PriceRefreshService,
    private val stubCrypto: StubCryptoProvider,
    private val stubEquity: StubEquityProvider,
    private val client: RecordingTelegramClient,
) : IntegrationTestBase() {

    private val today = LocalDate.now()

    @BeforeEach
    fun resetStubs() {
        client.sent.clear()
        client.nextOk = true
        stubCrypto.current.clear()
        stubCrypto.history.clear()
        stubCrypto.failFor.clear()
        stubEquity.history.clear()
        stubEquity.currencies.clear()
        stubEquity.failFor.clear()
        stubEquity.notFoundFor.clear()
    }

    private fun seedHousehold(enableTelegram: Boolean = true): Triple<User, Household, TelegramSettings?> {
        val user = users.save(User(email = "eval-${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "Eval", currency = "EUR", defaultLocale = "en"))
        members.save(HouseholdMember(id = HouseholdMemberId(household.id, user.id), role = HouseholdRole.owner))
        val settings = if (enableTelegram) {
            settingsRepo.save(
                TelegramSettings(
                    householdId = household.id,
                    active = true,
                    chatId = "chat-${System.nanoTime()}",
                    botTokenEnc = crypto.encrypt("bot-secret"),
                    createdByUserId = user.id,
                    updatedByUserId = user.id,
                ),
            )
        } else {
            null
        }
        return Triple(user, household, settings)
    }

    private fun seedCryptoHolding(householdId: UUID, user: User, symbol: String, initialPrice: BigDecimal): Pair<UUID, String> {
        val providerSymbol = "${symbol.lowercase()}-${System.nanoTime()}"
        val holding = holdingService.create(
            householdId,
            HoldingRequest(HoldingAssetClass.crypto, symbol, nativeCurrency = "EUR", provider = HoldingProvider.coingecko, providerSymbol = providerSymbol),
            user,
        )
        holdingService.addLot(householdId, holding.id, LotRequest(LotType.BUY, today.minusDays(5), BigDecimal("2"), initialPrice, "EUR"), user)
        client.sent.clear()
        prices.save(
            PricePoint(
                provider = "coingecko",
                providerSymbol = providerSymbol,
                currency = "EUR",
                price = initialPrice,
                priceDate = today,
                asOf = Instant.now(),
            ),
        )
        return holding.id to providerSymbol
    }

    @Test
    fun `fires once at exact threshold and ignores subsequent crossings`() {
        val (user, household, _) = seedHousehold()
        val (holdingId, providerSymbol) = seedCryptoHolding(household.id, user, "SUI", BigDecimal("2.10"))

        val alert = alertService.create(
            household.id,
            CreatePriceAlertRequest(holdingId, BigDecimal("2.80"), note = "sell half"),
            user,
        )

        // Refresh exactly at threshold -> fires once
        stubCrypto.current[providerSymbol] = BigDecimal("2.80")
        refreshService.refreshCrypto(today)

        val afterFirst = alertRepo.findById(alert.id).orElseThrow()
        assertThat(afterFirst.status).isEqualTo(AlertStatus.FIRED)
        assertThat(afterFirst.firedPrice).isEqualByComparingTo("2.80")
        assertThat(afterFirst.deliverySentAt).isNotNull()
        assertThat(afterFirst.deliveryAttempts).isEqualTo(1)
        assertThat(client.sent).hasSize(1)
        assertThat(client.sent.single().text).contains("SUI").contains("sell half")

        // Second refresh above threshold sends nothing
        stubCrypto.current[providerSymbol] = BigDecimal("3.20")
        refreshService.refreshCrypto(today)
        assertThat(client.sent).hasSize(1)

        // BELOW direction fires at exact threshold (<=)
        val belowAlert = alertService.create(household.id, CreatePriceAlertRequest(holdingId, BigDecimal("2.50")), user)
        stubCrypto.current[providerSymbol] = BigDecimal("2.50")
        refreshService.refreshCrypto(today)
        val belowStored = alertRepo.findById(belowAlert.id).orElseThrow()
        assertThat(belowStored.status).isEqualTo(AlertStatus.FIRED)
        assertThat(belowStored.direction).isEqualTo(AlertDirection.BELOW)
        assertThat(client.sent).hasSize(2)
    }

    @Test
    fun `stale price or delisted holding never fires`() {
        val (user, household, _) = seedHousehold()
        val (holdingId, providerSymbol) = seedCryptoHolding(household.id, user, "OLD", BigDecimal("10.00"))
        val alert = alertService.create(household.id, CreatePriceAlertRequest(holdingId, BigDecimal("12.00")), user)

        // Move stored asOf older than CRYPTO_STALE_AFTER (3h) without refreshing from provider
        val pt = prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate("coingecko", providerSymbol, "EUR", today)!!
        pt.price = BigDecimal("15.00")
        pt.asOf = Instant.now().minusSeconds(4 * 3600)
        prices.save(pt)

        evaluator.evaluateAndDeliver(setOf(PriceCoordinate("coingecko", providerSymbol, "EUR")), today, Instant.now())
        assertThat(alertRepo.findById(alert.id).orElseThrow().status).isEqualTo(AlertStatus.ACTIVE)
        assertThat(client.sent).isEmpty()

        // Mark holding delisted and make point fresh -> still must NOT fire
        pt.asOf = Instant.now()
        prices.save(pt)
        val h = holdingRepo.findById(holdingId).orElseThrow()
        h.providerNotFoundSince = today
        holdingRepo.save(h)

        evaluator.evaluateAndDeliver(setOf(PriceCoordinate("coingecko", providerSymbol, "EUR")), today, Instant.now())
        assertThat(alertRepo.findById(alert.id).orElseThrow().status).isEqualTo(AlertStatus.ACTIVE)
        assertThat(client.sent).isEmpty()
    }

    @Test
    fun `expiry runs silently before evaluation while NO_EXPIRY alert stays evaluable`() {
        val (user, household, _) = seedHousehold()
        val (holdingId, providerSymbol) = seedCryptoHolding(household.id, user, "EXP", BigDecimal("10.00"))
        val expiring = alertService.create(household.id, CreatePriceAlertRequest(holdingId, BigDecimal("12.00")), user)
        val perpetual = alertService.create(
            household.id,
            CreatePriceAlertRequest(holdingId, BigDecimal("12.00"), expiryPreset = AlertExpiryPreset.NO_EXPIRY),
            user,
        )

        // Push expiring.expiresAt into the past
        val row = alertRepo.findById(expiring.id).orElseThrow()
        row.expiresAt = Instant.now().minusSeconds(60)
        alertRepo.save(row)

        val pt = prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate("coingecko", providerSymbol, "EUR", today)!!
        pt.price = BigDecimal("13.00")
        pt.asOf = Instant.now()
        prices.save(pt)

        evaluator.evaluateAndDeliver(setOf(PriceCoordinate("coingecko", providerSymbol, "EUR")), today, Instant.now())

        assertThat(alertRepo.findById(expiring.id).orElseThrow().status).isEqualTo(AlertStatus.EXPIRED)
        assertThat(alertRepo.findById(perpetual.id).orElseThrow().status).isEqualTo(AlertStatus.FIRED)
        assertThat(client.sent).hasSize(1)
    }

    @Test
    fun `historical backfill above threshold never fires when current price does not cross`() {
        val (user, household, _) = seedHousehold()
        val symbol = "HIST-${System.nanoTime()}"
        val providerSymbol = symbol.lowercase()
        val holding = holdingService.create(
            household.id,
            HoldingRequest(HoldingAssetClass.etf, symbol, nativeCurrency = "USD", provider = HoldingProvider.yahoo, providerSymbol = providerSymbol),
            user,
        )
        // Seed yesterday's and today's price: today is below threshold (100), yesterday was spike (150)!
        prices.save(
            PricePoint(
                provider = "yahoo",
                providerSymbol = providerSymbol,
                currency = "USD",
                price = BigDecimal("100.00"),
                priceDate = today,
                asOf = Instant.now(),
            ),
        )
        holdingService.addLot(household.id, holding.id, LotRequest(LotType.BUY, today.minusDays(5), BigDecimal("1"), BigDecimal("95"), "EUR"), user)
        client.sent.clear()

        val alert = alertService.create(household.id, CreatePriceAlertRequest(holding.id, BigDecimal("120.00")), user)

        // Backfill / gap-fill inserts a past spike (3 days ago = 150.00) while today stays 100.00
        stubEquity.currencies[providerSymbol] = "USD"
        stubEquity.history[providerSymbol] = mutableListOf(
            DailyPrice(today.minusDays(3), BigDecimal("150.00")),
            DailyPrice(today, BigDecimal("100.00")),
        )
        val loadedHolding = holdingRepo.findById(holding.id).orElseThrow()
        refreshService.extendBackfill(loadedHolding, today.minusDays(5))

        assertThat(alertRepo.findById(alert.id).orElseThrow().status).isEqualTo(AlertStatus.ACTIVE)
        assertThat(client.sent).isEmpty()
    }

    @Test
    fun `delivery failure retries across jobs honoring min spacing without duplicates and exhausts at 3 attempts`() {
        val (user, household, _) = seedHousehold()
        val (holdingId, providerSymbol) = seedCryptoHolding(household.id, user, "RETRY", BigDecimal("5.00"))
        val alert = alertService.create(household.id, CreatePriceAlertRequest(holdingId, BigDecimal("6.00")), user)

        client.nextOk = false
        val t0 = Instant.now()
        val pt = prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate("coingecko", providerSymbol, "EUR", today)!!
        pt.price = BigDecimal("6.50")
        pt.asOf = t0
        prices.save(pt)

        // Attempt 1 fails -> status stays FIRED, attempts = 1
        evaluator.evaluateAndDeliver(setOf(PriceCoordinate("coingecko", providerSymbol, "EUR")), today, t0)
        val after1 = alertRepo.findById(alert.id).orElseThrow()
        assertThat(after1.status).isEqualTo(AlertStatus.FIRED)
        assertThat(after1.deliveryAttempts).isEqualTo(1)
        assertThat(after1.deliverySentAt).isNull()
        assertThat(after1.deliveryLastError).contains("chat not found")

        // Sweep 2 minutes later (< 15m min spacing): does NOT burn attempt 2!
        evaluator.deliverPendingAlerts(today, t0.plusSeconds(120))
        assertThat(alertRepo.findById(alert.id).orElseThrow().deliveryAttempts).isEqualTo(1)

        // Separate household equity refresh triggers cross-household pending sweep after spacing expires
        val (userB, hhB, _) = seedHousehold(enableTelegram = true)
        val eqSym = "EQB-${System.nanoTime()}"
        val hB = holdingService.create(
            hhB.id,
            HoldingRequest(HoldingAssetClass.etf, eqSym, nativeCurrency = "EUR", provider = HoldingProvider.yahoo, providerSymbol = eqSym.lowercase()),
            userB,
        )
        holdingService.addLot(hhB.id, hB.id, LotRequest(LotType.BUY, today.minusDays(5), BigDecimal("1"), BigDecimal("50"), "EUR"), userB)
        client.sent.clear()
        stubEquity.currencies[eqSym.lowercase()] = "EUR"
        stubEquity.history[eqSym.lowercase()] = mutableListOf(DailyPrice(today, BigDecimal("51.00")))

        // Backdate last attempt > 15 min ago so an immediate equity job sweeps household A's pending retry!
        val beforeSweep = alertRepo.findById(alert.id).orElseThrow()
        beforeSweep.deliveryLastAttemptAt = Instant.now().minus(ALERT_DELIVERY_RETRY_MIN_SPACING).minusSeconds(60)
        alertRepo.save(beforeSweep)

        client.nextOk = true
        refreshService.refreshEquities(today)
        val after2 = alertRepo.findById(alert.id).orElseThrow()
        assertThat(after2.deliveryAttempts).isEqualTo(2)
        assertThat(after2.deliverySentAt).isNotNull()
        assertThat(after2.deliveryLastError).isNull()
        assertThat(client.sent).hasSize(1)

        // Further sweeps never send a duplicate
        evaluator.deliverPendingAlerts(today, Instant.now().plusSeconds(7200))
        assertThat(client.sent).hasSize(1)

        // Test exhaustion at MAX_ALERT_DELIVERY_ATTEMPTS (3)
        val alert2 = alertService.create(household.id, CreatePriceAlertRequest(holdingId, BigDecimal("7.00")), user)
        client.nextOk = false
        pt.price = BigDecimal("7.50")
        pt.asOf = t0
        prices.save(pt)
        evaluator.evaluateAndDeliver(setOf(PriceCoordinate("coingecko", providerSymbol, "EUR")), today, t0)
        evaluator.deliverPendingAlerts(today, t0.plusSeconds(1000))
        evaluator.deliverPendingAlerts(today, t0.plusSeconds(2000))
        evaluator.deliverPendingAlerts(today, t0.plusSeconds(3000))
        val exhausted = alertRepo.findById(alert2.id).orElseThrow()
        assertThat(exhausted.deliveryAttempts).isEqualTo(MAX_ALERT_DELIVERY_ATTEMPTS)
        assertThat(exhausted.deliverySentAt).isNull()
    }

    @Test
    fun `missing Telegram pauses evaluation until Telegram is restored`() {
        val (user, household, settings) = seedHousehold(enableTelegram = true)
        val (holdingId, providerSymbol) = seedCryptoHolding(household.id, user, "PAUSE", BigDecimal("1.00"))
        val alert = alertService.create(household.id, CreatePriceAlertRequest(holdingId, BigDecimal("1.50")), user)

        // Remove/deactivate Telegram
        settings!!.active = false
        settingsRepo.save(settings)

        stubCrypto.current[providerSymbol] = BigDecimal("2.00")
        refreshService.refreshCrypto(today)

        assertThat(alertRepo.findById(alert.id).orElseThrow().status).isEqualTo(AlertStatus.ACTIVE)
        assertThat(client.sent).isEmpty()

        // Re-enable Telegram -> next refresh evaluates & fires
        settings.active = true
        settingsRepo.save(settings)
        refreshService.refreshCrypto(today)

        assertThat(alertRepo.findById(alert.id).orElseThrow().status).isEqualTo(AlertStatus.FIRED)
        assertThat(client.sent).hasSize(1)
    }
}
