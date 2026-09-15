package com.sephilabs.sharedledger.identity.passwordreset

import com.sephilabs.sharedledger.common.SecureTokens
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.i18n.Messages
import com.sephilabs.sharedledger.mail.EmailSender
import com.sephilabs.sharedledger.mail.OutgoingEmail
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.Locale

/** Everything that depends on whether the address is known — the lookup, the token row, the SMTP round-trip —
 *  runs here, off the request thread, so the endpoint's response is identical in body *and* timing for known
 *  and unknown addresses. Delivery failures are logged, never surfaced or retried. */
@Component
class PasswordResetDispatcher(
    private val service: PasswordResetService,
    private val mailer: EmailSender,
    private val messages: Messages,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("mailExecutor")
    fun dispatch(normalizedEmail: String) {
        val issued = service.issueToken(normalizedEmail)
        if (issued == null) {
            log.info("password_reset_requested known_user=false")
            return
        }
        val prefix = SecureTokens.logPrefix(issued.tokenHash)
        log.info("password_reset_requested known_user=true userId={} tokenHashPrefix={}", issued.userId, prefix)
        val result = mailer.send(compose(issued))
        if (result.ok) {
            log.info("password_reset_mail userId={} tokenHashPrefix={} ok=true", issued.userId, prefix)
        } else {
            log.warn(
                "password_reset_mail userId={} tokenHashPrefix={} ok=false description={}",
                issued.userId, prefix, result.description,
            )
        }
    }

    private fun compose(issued: IssuedResetToken): OutgoingEmail {
        val locale = Locale.forLanguageTag(issued.locale.ifBlank { "en" })
        val link = "${props.publicUrl.trim().trimEnd('/')}/reset-password?token=${issued.token}"
        val minutes = props.passwordReset.ttlMinutes.toString()
        return OutgoingEmail(
            to = issued.email,
            subject = messages.resolve("password_reset.mail.subject", emptyArray(), "Reset your password", locale),
            // The body keeps the link on a line of its own so every mail client auto-links it.
            body = messages.resolve("password_reset.mail.body", arrayOf(link, minutes), link, locale),
        )
    }
}
