package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.identity.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** One household switch governs every AI feature; each answers this code when it is off. */
const val AI_FEATURES_DISABLED = "AI_FEATURES_DISABLED"

@Service
class AiSuggestionSettingsService(
    private val availability: AiAvailability,
    private val settings: AiSuggestionSettingsRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun get(householdId: UUID): AiSuggestionSettingsDto = toDto(isEnabled(householdId))

    @Transactional
    fun update(householdId: UUID, enabled: Boolean, by: User): AiSuggestionSettingsDto {
        availability.requireConfigured()
        val row = settings.findById(householdId).orElseGet { AiSuggestionSettings(householdId = householdId) }
        row.enabled = enabled
        row.updatedByUserId = by.id
        row.updatedAt = Instant.now()
        settings.save(row)
        log.info("ai_suggestion_settings_updated household={} enabled={} by={}", householdId, enabled, by.id)
        return toDto(enabled)
    }

    /** 404 when the instance has no provider, like every other AI path. */
    @Transactional(readOnly = true)
    fun isEnabled(householdId: UUID): Boolean {
        availability.requireConfigured()
        return settings.findById(householdId).map { it.enabled }.orElse(false)
    }

    private fun toDto(enabled: Boolean): AiSuggestionSettingsDto {
        val provider = availability.requireConfigured()
        return AiSuggestionSettingsDto(enabled = enabled, model = provider.model, providerHost = provider.host)
    }
}
