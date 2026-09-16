package com.sephilabs.sharedledger.mail

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

/** Captures outgoing mail instead of talking SMTP. */
class RecordingEmailSender : EmailSender {
    val sent = CopyOnWriteArrayList<OutgoingEmail>()
    @Volatile var nextOk = true

    override fun send(mail: OutgoingEmail): SendResult {
        sent.add(mail)
        return SendResult(nextOk, if (nextOk) null else "535-5.7.8 Username and Password not accepted")
    }

    fun to(email: String): List<OutgoingEmail> = sent.filter { it.to == email }
}

/** Lets a test pretend SMTP is absent without booting a second Spring context. [forced] null = real value. */
class TogglableMailAvailability(smtp: SmtpSettings) : MailAvailability(smtp) {
    @Volatile var forced: Boolean? = null

    override val enabled: Boolean get() = forced ?: super.enabled
}

@TestConfiguration
class MailTestDoublesConfig {
    @Bean
    @Primary
    fun recordingEmailSender() = RecordingEmailSender()

    @Bean
    @Primary
    fun togglableMailAvailability(smtp: SmtpSettings) = TogglableMailAvailability(smtp)
}
