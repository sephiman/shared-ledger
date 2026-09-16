package com.sephilabs.sharedledger.identity.emailchange

import com.sephilabs.sharedledger.common.SecureTokens
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.i18n.Messages
import com.sephilabs.sharedledger.mail.EmailSender
import com.sephilabs.sharedledger.mail.OutgoingEmail
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.Locale

/** Two mails per request: the confirmation link to the address being claimed, and a notice to the address
 *  losing the account so its owner can react. Both are fire-and-log, neither is retried. */
@Component
class EmailChangeDispatcher(
    private val mailer: EmailSender,
    private val messages: Messages,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("mailExecutor")
    fun dispatch(issued: IssuedEmailChangeToken) {
        val locale = Locale.forLanguageTag(issued.locale.ifBlank { "en" })
        val prefix = SecureTokens.logPrefix(issued.tokenHash)
        send("confirmation", issued.userId.toString(), prefix, confirmation(issued, locale))
        send("notice", issued.userId.toString(), prefix, notice(issued, locale))
    }

    private fun send(kind: String, userId: String, prefix: String, mail: OutgoingEmail) {
        val result = mailer.send(mail)
        if (result.ok) {
            log.info("email_change_mail kind={} userId={} tokenHashPrefix={} ok=true", kind, userId, prefix)
        } else {
            log.warn(
                "email_change_mail kind={} userId={} tokenHashPrefix={} ok=false description={}",
                kind, userId, prefix, result.description,
            )
        }
    }

    private fun confirmation(issued: IssuedEmailChangeToken, locale: Locale): OutgoingEmail {
        val link = "${props.publicUrl.trim().trimEnd('/')}/confirm-email?token=${issued.token}"
        val minutes = props.emailChange.ttlMinutes.toString()
        return OutgoingEmail(
            to = issued.newEmail,
            subject = messages.resolve("email_change.mail.subject", emptyArray(), "Confirm your new email", locale),
            // The link stays on a line of its own so every mail client auto-links it.
            body = messages.resolve("email_change.mail.body", arrayOf(link, minutes), link, locale),
        )
    }

    /** Deliberately carries no link and does not name the new address: it is a heads-up to an address that
     *  may no longer be in the right hands. */
    private fun notice(issued: IssuedEmailChangeToken, locale: Locale): OutgoingEmail = OutgoingEmail(
        to = issued.currentEmail,
        subject = messages.resolve("email_change.notice.subject", emptyArray(), "Email change requested", locale),
        body = messages.resolve("email_change.notice.body", emptyArray(), "Email change requested", locale),
    )
}
