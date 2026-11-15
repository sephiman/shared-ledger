package com.sephilabs.sharedledger.portfolio.alert

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.household.admin.HouseholdDataWipeService
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.notification.TelegramCrypto
import com.sephilabs.sharedledger.notification.TelegramSettings
import com.sephilabs.sharedledger.notification.TelegramSettingsRepository
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingProvider
import com.sephilabs.sharedledger.portfolio.HoldingRequest
import com.sephilabs.sharedledger.portfolio.HoldingService
import com.sephilabs.sharedledger.portfolio.LotRequest
import com.sephilabs.sharedledger.portfolio.LotType
import com.sephilabs.sharedledger.portfolio.StubCryptoProvider
import com.sephilabs.sharedledger.portfolio.price.PricePoint
import com.sephilabs.sharedledger.portfolio.price.PricePointRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

@ResourceLock("stub-price-providers")
@ResourceLock("recording-telegram")
class PriceAlertServiceIntegrationTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val settingsRepo: TelegramSettingsRepository,
    private val crypto: TelegramCrypto,
    private val holdings: HoldingService,
    private val prices: PricePointRepository,
    private val alertService: PriceAlertService,
    private val alertRepo: PriceAlertRepository,
    private val wipeService: HouseholdDataWipeService,
    private val props: AppProperties,
    private val stubCrypto: StubCryptoProvider,
) : AuthHttpTestBase() {

    private val today = LocalDate.now()

    @AfterEach
    fun cleanupStub() {
        stubCrypto.current.clear()
        stubCrypto.history.clear()
        stubCrypto.failFor.clear()
    }

    private fun seedUserHousehold(enableTelegram: Boolean = true): Triple<User, Household, TelegramSettings?> {
        val email = "alert-svc-${System.nanoTime()}@example.com"
        val user = users.save(User(email = email, passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "Alert Home", currency = "EUR", defaultLocale = "en"))
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

    private fun createOpenCrypto(householdId: UUID, user: User, symbol: String = "SUI", price: BigDecimal = BigDecimal("2.10")): UUID {
        val created = holdings.create(
            householdId,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = symbol,
                nativeCurrency = "USD",
                provider = HoldingProvider.coingecko,
                providerSymbol = "${symbol.lowercase()}-${System.nanoTime()}",
            ),
            user,
        )
        holdings.addLot(
            householdId,
            created.id,
            LotRequest(LotType.BUY, today.minusDays(5), BigDecimal("10"), BigDecimal("2.00"), "EUR"),
            user,
        )
        prices.save(
            PricePoint(
                provider = "coingecko",
                providerSymbol = created.providerSymbol!!,
                currency = "EUR",
                price = price,
                priceDate = today,
                asOf = Instant.now(),
            ),
        )
        return created.id
    }

    @Test
    fun `direction inference, equal rejection, stale price, expiry presets, and custom end-of-day`() {
        val (user, household, _) = seedUserHousehold()
        val holdingId = createOpenCrypto(household.id, user, "SUI", BigDecimal("2.10"))

        val above = alertService.create(
            household.id,
            CreatePriceAlertRequest(
                holdingId = holdingId,
                threshold = BigDecimal("2.80"),
                expiryPreset = AlertExpiryPreset.DAYS_30,
                note = "  take profit  ",
            ),
            user,
        )
        assertThat(above.direction).isEqualTo(AlertDirection.ABOVE)
        assertThat(above.note).isEqualTo("take profit")
        assertThat(above.expiresAt).isNotNull()

        val customDate = today.plusDays(10)
        val belowCustom = alertService.create(
            household.id,
            CreatePriceAlertRequest(
                holdingId = holdingId,
                threshold = BigDecimal("1.50"),
                expiryPreset = AlertExpiryPreset.CUSTOM_DATE,
                customExpiryDate = customDate,
            ),
            user,
        )
        val expectedEod = ZonedDateTime.of(customDate, LocalTime.of(23, 59, 59), props.displayZoneId()).toInstant()
        assertThat(belowCustom.direction).isEqualTo(AlertDirection.BELOW)
        assertThat(belowCustom.expiresAt).isEqualTo(expectedEod)

        val perpetual = alertService.create(
            household.id,
            CreatePriceAlertRequest(
                holdingId = holdingId,
                threshold = BigDecimal("3.00"),
                expiryPreset = AlertExpiryPreset.NO_EXPIRY,
            ),
            user,
        )
        assertThat(perpetual.expiresAt).isNull()

        // Equal target or already-satisfied expectedDirection is rejected
        assertThatThrownBy {
            alertService.create(
                household.id,
                CreatePriceAlertRequest(holdingId = holdingId, threshold = BigDecimal("2.1000")),
                user,
            )
        }.isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo("PRICE_ALERT_ALREADY_MET")

        assertThatThrownBy {
            alertService.create(
                household.id,
                CreatePriceAlertRequest(
                    holdingId = holdingId,
                    threshold = BigDecimal("1.90"),
                    expectedDirection = AlertDirection.ABOVE,
                ),
                user,
            )
        }.isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo("PRICE_ALERT_ALREADY_MET")

        // Stale-only price holding is rejected
        val staleHolding = holdings.create(
            household.id,
            HoldingRequest(
                assetClass = HoldingAssetClass.crypto,
                symbol = "OLD",
                nativeCurrency = "EUR",
                provider = HoldingProvider.coingecko,
                providerSymbol = "old-${System.nanoTime()}",
            ),
            user,
        )
        holdings.addLot(household.id, staleHolding.id, LotRequest(LotType.BUY, today.minusDays(20), BigDecimal("1"), BigDecimal("5"), "EUR"), user)
        prices.save(
            PricePoint(
                provider = "coingecko",
                providerSymbol = staleHolding.providerSymbol!!,
                currency = "EUR",
                price = BigDecimal("6.00"),
                priceDate = today.minusDays(props.portfolio.stalePriceThresholdDays + 1),
                asOf = Instant.now(),
            ),
        )
        assertThatThrownBy {
            alertService.create(household.id, CreatePriceAlertRequest(staleHolding.id, BigDecimal("8.00")), user)
        }.isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo("PRICE_ALERT_STALE_PRICE")
    }

    @Test
    fun `enforces 50 active alert cap per household while allowing finished alerts`() {
        val (user, household, _) = seedUserHousehold()
        val holdingId = createOpenCrypto(household.id, user, "CAP", BigDecimal("10.00"))

        repeat(MAX_ACTIVE_ALERTS_PER_HOUSEHOLD) { idx ->
            alertService.create(
                household.id,
                CreatePriceAlertRequest(holdingId = holdingId, threshold = BigDecimal("${11 + idx}.00")),
                user,
            )
        }

        assertThatThrownBy {
            alertService.create(
                household.id,
                CreatePriceAlertRequest(holdingId = holdingId, threshold = BigDecimal("99.00")),
                user,
            )
        }.isInstanceOf(AppException::class.java)
            .extracting("code").isEqualTo("PRICE_ALERT_CAP_REACHED")

        // Cancel one -> frees a slot
        val first = alertService.list(household.id).first()
        alertService.cancel(household.id, first.id)

        val createdAfterCancel = alertService.create(
            household.id,
            CreatePriceAlertRequest(holdingId = holdingId, threshold = BigDecimal("99.00")),
            user,
        )
        assertThat(createdAfterCancel.status).isEqualTo(AlertStatus.ACTIVE)
    }

    @Test
    fun `closing position cancels active alerts with POSITION_CLOSED and holding or household wipe removes all`() {
        val (user, household, _) = seedUserHousehold()
        val holdingId = createOpenCrypto(household.id, user, "CLOSE", BigDecimal("5.00"))
        alertService.create(household.id, CreatePriceAlertRequest(holdingId, BigDecimal("6.00")), user)
        alertService.create(household.id, CreatePriceAlertRequest(holdingId, BigDecimal("7.00")), user)

        // Sell all 10 units -> closes position
        holdings.addLot(
            household.id,
            holdingId,
            LotRequest(LotType.SELL, today, BigDecimal("10"), BigDecimal("5.50"), "EUR"),
            user,
        )

        val afterClose = alertService.list(household.id, holdingId)
        assertThat(afterClose).hasSize(2)
        assertThat(afterClose).allSatisfy { dto ->
            assertThat(dto.status).isEqualTo(AlertStatus.CANCELLED)
            assertThat(dto.cancelReason).isEqualTo(AlertCancelReason.POSITION_CLOSED)
            assertThat(dto.cancelledAt).isNotNull()
        }

        // Deleting holding removes its alerts
        holdings.delete(household.id, holdingId, user)
        assertThat(alertRepo.findAllByHouseholdIdOrderByCreatedAtDesc(household.id)).isEmpty()

        // Re-create alert and test full household wipe + deleteLot close
        val h2 = createOpenCrypto(household.id, user, "WIPE", BigDecimal("4.00"))
        val a3 = alertService.create(household.id, CreatePriceAlertRequest(h2, BigDecimal("5.00")), user)
        val lotToDelete = holdings.list(household.id).first { it.id == h2 }.lots.single()
        holdings.deleteLot(household.id, h2, lotToDelete.id, user)
        val afterDeleteLot = alertService.list(household.id, h2).single { it.id == a3.id }
        assertThat(afterDeleteLot.status).isEqualTo(AlertStatus.CANCELLED)
        assertThat(afterDeleteLot.cancelReason).isEqualTo(AlertCancelReason.POSITION_CLOSED)

        assertThat(alertRepo.findAllByHouseholdIdOrderByCreatedAtDesc(household.id)).hasSize(1)
        wipeService.wipe(household.id, user.id)
        assertThat(alertRepo.findAllByHouseholdIdOrderByCreatedAtDesc(household.id)).isEmpty()
    }

    @Test
    fun `HTTP gating when Telegram absent, non-owner member permissions, and household isolation`() {
        val emailA = "member-a-${System.nanoTime()}@example.com"
        val regA = register(emailA)
        val cookiesA = regA.response.cookies
        val ownerA = users.findByEmailIgnoreCase(emailA)!!
        val householdA = members.findAllByIdUserId(ownerA.id).first().id.householdId

        // Add non-owner member to household A
        val emailMember = "member-b-${System.nanoTime()}@example.com"
        val regMember = register(emailMember)
        val cookiesMember = regMember.response.cookies
        val memberUser = users.findByEmailIgnoreCase(emailMember)!!
        members.save(HouseholdMember(id = HouseholdMemberId(householdA, memberUser.id), role = HouseholdRole.member))

        // Separate user B on household B (not a member of A)
        val emailOutsider = "outsider-${System.nanoTime()}@example.com"
        val cookiesOutsider = register(emailOutsider).response.cookies

        val holdingId = createOpenCrypto(householdA, ownerA, "ETH", BigDecimal("2000"))

        // Without Telegram, endpoints answer 404 (feature absent) and summary says telegramActive=false
        mockMvc.perform(get("/api/households/$householdA/portfolio/summary").cookie(*cookiesA))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.telegramActive").value(false))

        mockMvc.perform(get("/api/households/$householdA/portfolio/alerts").cookie(*cookiesA))
            .andExpect(status().isNotFound)

        // Enable Telegram on household A -> non-owner member can create & cancel, outsider gets 403
        val settings = settingsRepo.save(
            TelegramSettings(
                householdId = householdA,
                active = true,
                chatId = "chat-a",
                botTokenEnc = crypto.encrypt("tok"),
                createdByUserId = ownerA.id,
                updatedByUserId = ownerA.id,
            ),
        )

        val createdJson = mockMvc.perform(
            post("/api/households/$householdA/portfolio/alerts")
                .cookie(*cookiesMember)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"holdingId":"$holdingId","threshold":"2500.00","expiryPreset":"DAYS_90"}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.direction").value("ABOVE"))
            .andReturn().response.contentAsString
        val alertId = objectMapper.readTree(createdJson)["id"].asString()

        // Outsider blocked (403)
        mockMvc.perform(get("/api/households/$householdA/portfolio/alerts").cookie(*cookiesOutsider))
            .andExpect(status().isForbidden)

        // Disable Telegram -> alert stays in DB, endpoints answer 404, re-enabling restores visibility
        settings.active = false
        settingsRepo.save(settings)
        mockMvc.perform(get("/api/households/$householdA/portfolio/alerts").cookie(*cookiesMember))
            .andExpect(status().isNotFound)
        assertThat(alertRepo.findAllByHouseholdIdOrderByCreatedAtDesc(householdA)).hasSize(1)

        settings.active = true
        settingsRepo.save(settings)
        mockMvc.perform(get("/api/households/$householdA/portfolio/alerts").cookie(*cookiesMember))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(alertId))

        // Member cancels then deletes
        mockMvc.perform(post("/api/households/$householdA/portfolio/alerts/$alertId/cancel").cookie(*cookiesMember).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelReason").value("USER"))

        mockMvc.perform(delete("/api/households/$householdA/portfolio/alerts/$alertId").cookie(*cookiesMember).with(csrf()))
            .andExpect(status().isNoContent)
    }
}
