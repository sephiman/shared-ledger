package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.common.errors.AppException
import org.springframework.stereotype.Component

/** The instance-level switch: without a configured provider every AI endpoint answers as if it did not exist. */
@Component
class AiAvailability(private val settings: AiProviderSettings) {
    val enabled: Boolean get() = settings is AiProviderSettings.Configured

    fun requireConfigured(): AiProviderSettings.Configured {
        if (!enabled) throw AppException.notFound()
        return settings as AiProviderSettings.Configured
    }
}
