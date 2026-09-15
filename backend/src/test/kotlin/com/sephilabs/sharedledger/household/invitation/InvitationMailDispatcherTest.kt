package com.sephilabs.sharedledger.household.invitation

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.i18n.Messages
import com.sephilabs.sharedledger.mail.EmailSender
import com.sephilabs.sharedledger.mail.OutgoingEmail
import com.sephilabs.sharedledger.mail.SendResult
import com.sephilabs.sharedledger.mail.SmtpSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource

class InvitationMailDispatcherTest {

    private class FakeEmailSender : EmailSender {
        val sent = mutableListOf<OutgoingEmail>()
        override fun send(mail: OutgoingEmail): SendResult {
            sent.add(mail)
            return SendResult(true, null)
        }
    }

    private val mailer = FakeEmailSender()
    private val props = AppProperties(publicUrl = "https://ledger.example.com")
    private val messages = Messages(ResourceBundleMessageSource().apply { setBasename("i18n/messages") })

    @Test
    fun `does not send email when SMTP is absent`() {
        val dispatcher = InvitationMailDispatcher(mailer, SmtpSettings.Absent, messages, props)

        dispatcher.dispatch("user@example.com", "TestHome", "token123", "en", "hash123")

        assertThat(mailer.sent).isEmpty()
    }

    @Test
    fun `sends formatted email when SMTP is configured`() {
        val smtp = SmtpSettings.Configured(
            host = "smtp.test",
            port = 587,
            username = "user",
            password = "pass",
            startTls = true,
            from = "noreply@example.com",
            publicUrl = "https://ledger.example.com",
            timeoutMs = 5000,
        )
        val dispatcher = InvitationMailDispatcher(mailer, smtp, messages, props)

        dispatcher.dispatch("user@example.com", "TestHome", "token123", "en", "hash123")

        assertThat(mailer.sent).hasSize(1)
        val sentMail = mailer.sent.single()
        assertThat(sentMail.to).isEqualTo("user@example.com")
        assertThat(sentMail.subject).contains("TestHome")
        assertThat(sentMail.body).contains("https://ledger.example.com/register?invite=token123")
    }
}
