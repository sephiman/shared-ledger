package com.sephilabs.sharedledger.mail

import com.sephilabs.sharedledger.config.AppProperties

/** What the optional SMTP env group resolved to. A partial group is deliberately *not* configured: the
 *  feature depending on it stays hidden instead of showing a link whose mail could never go out. */
sealed interface SmtpSettings {

    data class Configured(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val startTls: Boolean,
        val from: String,
        val publicUrl: String,
        val timeoutMs: Long,
    ) : SmtpSettings

    /** Some of the group is set; [missing] names the env variables still blank, for the startup warning. */
    data class PartiallyConfigured(val missing: List<String>) : SmtpSettings

    data object Absent : SmtpSettings

    companion object {
        fun resolve(mail: AppProperties.Mail, publicUrl: String): SmtpSettings {
            val smtpGroup = linkedMapOf(
                "SMTP_HOST" to mail.host,
                "SMTP_USERNAME" to mail.username,
                "SMTP_PASSWORD" to mail.password,
                "MAIL_FROM" to mail.from,
            )
            // APP_PUBLIC_URL alone is not an attempt to configure mail (the bank redirect also uses it).
            if (smtpGroup.values.all { it.isBlank() }) return Absent
            val missing = (smtpGroup + ("APP_PUBLIC_URL" to publicUrl)).filterValues { it.isBlank() }.keys.toList()
            if (missing.isNotEmpty()) return PartiallyConfigured(missing)
            return Configured(
                host = mail.host.trim(),
                port = mail.port,
                username = mail.username,
                password = mail.password,
                startTls = mail.startTls,
                from = mail.from.trim(),
                publicUrl = publicUrl.trim().trimEnd('/'),
                timeoutMs = mail.timeoutMs,
            )
        }
    }
}
