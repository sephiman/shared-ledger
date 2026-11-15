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
import com.sephilabs.sharedledger.portfolio.HoldingRequest
import com.sephilabs.sharedledger.portfolio.HoldingService
import com.sephilabs.sharedledger.portfolio.LotRequest
import com.sephilabs.sharedledger.portfolio.LotType
import com.sephilabs.sharedledger.portfolio.price.PricePoint
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

@ResourceLock("stub-price-providers")
@ResourceLock("recording-telegram")
class PriceAlertConcurrencyIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val settingsRepo: TelegramSettingsRepository,
    private val crypto: TelegramCrypto,
    private val holdingService: HoldingService,
    private val prices: PricePointRepository,
    private val alertService: PriceAlertService,
    private val alertRepo: PriceAlertRepository,
    private val evaluator: PriceAlertEvaluator,
    private val client: RecordingTelegramClient,
    private val txManager: PlatformTransactionManager,
    private val jdbc: JdbcTemplate,
) : IntegrationTestBase() {

    private val today = LocalDate.now()

    @BeforeEach
    fun resetTelegram() {
        client.sent.clear()
        client.nextOk = true
    }

    private fun seedAlert(): Triple<PriceAlertDto, PriceCoordinate, Instant> {
        val user = users.save(User(email = "race-${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "Race", currency = "EUR", defaultLocale = "en"))
        members.save(HouseholdMember(id = HouseholdMemberId(household.id, user.id), role = HouseholdRole.owner))
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
        val providerSymbol = "sui-${System.nanoTime()}"
        val holding = holdingService.create(
            household.id,
            HoldingRequest(HoldingAssetClass.crypto, "SUI", nativeCurrency = "EUR", provider = HoldingProvider.coingecko, providerSymbol = providerSymbol),
            user,
        )
        holdingService.addLot(household.id, holding.id, LotRequest(LotType.BUY, today.minusDays(2), BigDecimal("10"), BigDecimal("2.00"), "EUR"), user)
        client.sent.clear()
        val now = Instant.now()
        prices.save(
            PricePoint(
                provider = "coingecko",
                providerSymbol = providerSymbol,
                currency = "EUR",
                price = BigDecimal("2.00"),
                priceDate = today,
                asOf = now,
            ),
        )
        val alert = alertService.create(household.id, CreatePriceAlertRequest(holding.id, BigDecimal("2.50")), user)

        // Update price above threshold
        val pt = prices.findByProviderAndProviderSymbolAndCurrencyAndPriceDate("coingecko", providerSymbol, "EUR", today)!!
        pt.price = BigDecimal("2.80")
        pt.asOf = now
        prices.save(pt)

        return Triple(alert, PriceCoordinate("coingecko", providerSymbol, "EUR"), now)
    }

    @Test
    fun `two overlapping evaluations locking the same alert row produce one fired transition and one Telegram message`() {
        val (alert, coord, now) = seedAlert()
        val racer = Executors.newSingleThreadExecutor()
        try {
            var other: java.util.concurrent.Future<*>? = null
            // Tx 1 transitions the alert to FIRED via the conditional update and holds the row lock
            // uncommitted while Tx 2 reads the old ACTIVE status (REPEATABLE/READ COMMITTED snapshot)
            // and queues its own conditional update behind Tx 1's lock.
            TransactionTemplate(txManager).executeWithoutResult {
                val updated = alertRepo.fireIfActive(alert.id, now, BigDecimal("2.80"), now)
                check(updated == 1)
                other = racer.submit {
                    evaluator.evaluateAndDeliver(setOf(coord), today, now)
                }
                awaitBlockedUpdate()
                check(!other!!.isDone) { "competing evaluation finished without waiting on the locked alert row" }
            }
            other!!.get(30, TimeUnit.SECONDS)
            racer.shutdown()
            assertThat(racer.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        } finally {
            racer.shutdownNow()
        }

        // Verify stored state & single delivery (deliverPendingAlerts if needed)
        evaluator.deliverPendingAlerts(today, now.plusSeconds(1))

        val stored = alertRepo.findById(alert.id).orElseThrow()
        assertThat(stored.status).isEqualTo(AlertStatus.FIRED)
        assertThat(stored.deliveryLastError).isNull()
        assertThat(stored.deliverySentAt).isNotNull()
        assertThat(stored.deliveryAttempts).isEqualTo(1)
        assertThat(client.sent).hasSize(1)
    }

    @Test
    fun `barrier-synchronized concurrent evaluateAndDeliver calls emit at most one Telegram message`() {
        val (alert, coord, now) = seedAlert()
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val f1 = pool.submit {
                barrier.await(5, TimeUnit.SECONDS)
                evaluator.evaluateAndDeliver(setOf(coord), today, now)
            }
            val f2 = pool.submit {
                barrier.await(5, TimeUnit.SECONDS)
                evaluator.evaluateAndDeliver(setOf(coord), today, now)
            }
            f1.get(30, TimeUnit.SECONDS)
            f2.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        val stored = alertRepo.findById(alert.id).orElseThrow()
        assertThat(stored.status).isEqualTo(AlertStatus.FIRED)
        assertThat(stored.deliveryAttempts).isEqualTo(1)
        assertThat(stored.deliverySentAt).isNotNull()
        assertThat(client.sent).hasSize(1)
    }

    private fun awaitBlockedUpdate() {
        val deadline = Instant.now().plus(Duration.ofSeconds(15))
        while (Instant.now().isBefore(deadline)) {
            jdbc.queryForObject("SELECT pg_stat_clear_snapshot()::text", String::class.java)
            val blocked = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE pg_backend_pid() = ANY(pg_blocking_pids(pid))",
                Int::class.java,
            )
            if (blocked != null && blocked > 0) return
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos())
        }
        error("competing evaluation never reached the locked price_alerts row")
    }
}
