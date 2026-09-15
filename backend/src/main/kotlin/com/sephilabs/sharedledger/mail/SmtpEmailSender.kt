package com.sephilabs.sharedledger.mail

import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSenderImpl

/** Plain-text mail over authenticated SMTP. The [JavaMailSenderImpl] is a private field on purpose: exposed
 *  as a bean, Boot's mail health indicator would adopt it and open an SMTP connection on every health call. */
class SmtpEmailSender(private val settings: SmtpSettings.Configured) : EmailSender {

    private val sender = JavaMailSenderImpl().apply {
        host = settings.host
        port = settings.port
        username = settings.username
        password = settings.password
        val timeout = settings.timeoutMs.toString()
        javaMailProperties.setProperty("mail.smtp.auth", "true")
        javaMailProperties.setProperty("mail.smtp.starttls.enable", settings.startTls.toString())
        javaMailProperties.setProperty("mail.smtp.starttls.required", settings.startTls.toString())
        javaMailProperties.setProperty("mail.smtp.connectiontimeout", timeout)
        javaMailProperties.setProperty("mail.smtp.timeout", timeout)
        javaMailProperties.setProperty("mail.smtp.writetimeout", timeout)
    }

    override fun send(mail: OutgoingEmail): SendResult {
        val message = SimpleMailMessage().apply {
            from = settings.from
            setTo(mail.to)
            subject = mail.subject
            text = mail.body
        }
        return try {
            sender.send(message)
            SendResult(true, null)
        } catch (ex: MailException) {
            SendResult(false, ex.message)
        }
    }
}
