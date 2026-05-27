package com.sharedledger.notification

import com.sharedledger.common.errors.AppException
import com.sharedledger.household.HouseholdRepository
import com.sharedledger.household.RequireHouseholdOwner
import com.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Locale
import java.util.UUID

/**
 * Owner-only per-household Telegram configuration. The bot token is write-only: it is accepted on
 * save, encrypted at rest, and never returned — the GET response only reports whether one exists.
 */
data class TelegramSettingsDto(
    val active: Boolean,
    val notifyTransactions: Boolean,
    val notifySnapshots: Boolean,
    val notifyMovements: Boolean,
    val notifyLoanPayments: Boolean,
    val notifyRecurringTxn: Boolean,
    val notifyRecurringLoan: Boolean,
    val chatId: String?,
    val tokenConfigured: Boolean,
)

data class TelegramSettingsUpdateRequest(
    val active: Boolean = true,
    val notifyTransactions: Boolean = true,
    val notifySnapshots: Boolean = true,
    val notifyMovements: Boolean = true,
    val notifyLoanPayments: Boolean = true,
    val notifyRecurringTxn: Boolean = true,
    val notifyRecurringLoan: Boolean = true,
    @field:Size(max = 64, message = "validation.invalid")
    val chatId: String? = null,
    // When null/blank the stored token is kept; when present it replaces and is re-encrypted.
    val botToken: String? = null,
)

data class TelegramTestResult(val ok: Boolean, val description: String?)

@RestController
@RequestMapping("/api/households/{householdId}/telegram-settings")
@RequireHouseholdOwner
class TelegramSettingsController(
    private val settingsRepo: TelegramSettingsRepository,
    private val households: HouseholdRepository,
    private val currentUser: CurrentUser,
    private val crypto: TelegramCrypto,
    private val client: TelegramClient,
    private val formatter: TelegramMessageFormatter,
) {

    @GetMapping
    @Transactional(readOnly = true)
    fun get(@PathVariable householdId: UUID): TelegramSettingsDto =
        settingsRepo.findByHouseholdId(householdId)?.toDto() ?: defaultDto()

    @PutMapping
    @Transactional
    fun update(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: TelegramSettingsUpdateRequest,
    ): TelegramSettingsDto {
        val user = currentUser.requireUser()
        val settings = settingsRepo.findByHouseholdId(householdId)
            ?: TelegramSettings(householdId = householdId, createdByUserId = user.id, updatedByUserId = user.id)
        settings.active = body.active
        settings.notifyTransactions = body.notifyTransactions
        settings.notifySnapshots = body.notifySnapshots
        settings.notifyMovements = body.notifyMovements
        settings.notifyLoanPayments = body.notifyLoanPayments
        settings.notifyRecurringTxn = body.notifyRecurringTxn
        settings.notifyRecurringLoan = body.notifyRecurringLoan
        settings.chatId = body.chatId?.takeIf { it.isNotBlank() }
        body.botToken?.takeIf { it.isNotBlank() }?.let { settings.botTokenEnc = crypto.encrypt(it.trim()) }
        settings.updatedByUserId = user.id
        return settingsRepo.save(settings).toDto()
    }

    @PostMapping("/test")
    @Transactional(readOnly = true)
    fun test(@PathVariable householdId: UUID): TelegramTestResult {
        val settings = settingsRepo.findByHouseholdId(householdId)
            ?: throw AppException.badRequest("TELEGRAM_NOT_CONFIGURED")
        if (!settings.isDeliverable()) throw AppException.badRequest("TELEGRAM_NOT_CONFIGURED")
        val household = households.findById(householdId).orElseThrow { AppException.notFound("HOUSEHOLD_NOT_FOUND") }
        val locale = Locale.forLanguageTag(household.defaultLocale.ifBlank { "en" })
        val token = crypto.decrypt(settings.botTokenEnc!!)
        val result = client.sendMessage(token, settings.chatId!!, formatter.testMessage(locale))
        return TelegramTestResult(result.ok, result.description)
    }

    private fun TelegramSettings.toDto() = TelegramSettingsDto(
        active = active,
        notifyTransactions = notifyTransactions,
        notifySnapshots = notifySnapshots,
        notifyMovements = notifyMovements,
        notifyLoanPayments = notifyLoanPayments,
        notifyRecurringTxn = notifyRecurringTxn,
        notifyRecurringLoan = notifyRecurringLoan,
        chatId = chatId,
        tokenConfigured = !botTokenEnc.isNullOrBlank(),
    )

    private fun defaultDto() = TelegramSettingsDto(
        active = true,
        notifyTransactions = true,
        notifySnapshots = true,
        notifyMovements = true,
        notifyLoanPayments = true,
        notifyRecurringTxn = true,
        notifyRecurringLoan = true,
        chatId = null,
        tokenConfigured = false,
    )
}
