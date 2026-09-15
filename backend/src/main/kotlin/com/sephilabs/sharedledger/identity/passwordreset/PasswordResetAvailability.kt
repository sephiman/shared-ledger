package com.sephilabs.sharedledger.identity.passwordreset

import com.sephilabs.sharedledger.mail.SmtpSettings
import org.springframework.stereotype.Component

/** The self-service reset exists only when SMTP is fully configured: no login-page link, endpoints 404. */
@Component
class PasswordResetAvailability(private val smtp: SmtpSettings) {
    val enabled: Boolean get() = smtp is SmtpSettings.Configured
}
