package com.sephilabs.sharedledger.household.invitation

import com.sephilabs.sharedledger.common.SecureTokens
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.i18n.Messages
import com.sephilabs.sharedledger.mail.EmailSender
import com.sephilabs.sharedledger.mail.OutgoingEmail
import com.sephilabs.sharedledger.mail.SmtpSettings
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * Dispatches household invitation emails off the request thread when SMTP is configured.
 * Delivery failures are logged and never throw or trigger retries.
 */
@Component
class InvitationMailDispatcher(
    private val mailer: EmailSender,
    private val smtpSettings: SmtpSettings,
    private val messages: Messages,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val isConfigured: Boolean get() = smtpSettings is SmtpSettings.Configured

    @Async("mailExecutor")
    fun dispatch(
        recipientEmail: String,
        householdName: String,
        token: String,
        localeStr: String,
        tokenHash: String,
    ) {
        if (smtpSettings !is SmtpSettings.Configured) {
            return
        }
        val prefix = SecureTokens.logPrefix(tokenHash)
        val mail = compose(recipientEmail, householdName, token, localeStr)
        val result = mailer.send(mail)
        if (result.ok) {
            log.info("invitation_mail recipient={} tokenHashPrefix={} ok=true", recipientEmail, prefix)
        } else {
            log.warn(
                "invitation_mail recipient={} tokenHashPrefix={} ok=false description={}",
                recipientEmail, prefix, result.description,
            )
        }
    }

    private fun compose(
        recipientEmail: String,
        householdName: String,
        token: String,
        localeStr: String,
    ): OutgoingEmail {
        val locale = Locale.forLanguageTag(localeStr.ifBlank { "en" })
        val link = "${props.publicUrl.trim().trimEnd('/')}/register?invite=$token"
        return OutgoingEmail(
            to = recipientEmail,
            subject = messages.resolve(
                "invitation.mail.subject",
                arrayOf(householdName),
                "You've been invited to join $householdName on Shared Ledger",
                locale,
            ),
            body = messages.resolve(
                "invitation.mail.body",
                arrayOf(householdName, link),
                link,
                locale,
            ),
        )
    }
}
