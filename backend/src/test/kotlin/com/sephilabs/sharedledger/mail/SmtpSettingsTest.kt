package com.sephilabs.sharedledger.mail

import com.sephilabs.sharedledger.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SmtpSettingsTest {

    private val complete = AppProperties.Mail(
        host = "smtp.gmail.com",
        port = 587,
        username = "me@gmail.com",
        password = "abcd efgh ijkl mnop",
        from = "Shared Ledger <me@gmail.com>",
    )

    @Test
    fun `nothing set resolves to absent`() {
        assertThat(SmtpSettings.resolve(AppProperties.Mail(), "")).isEqualTo(SmtpSettings.Absent)
    }

    @Test
    fun `a public url alone is not an attempt to configure mail`() {
        assertThat(SmtpSettings.resolve(AppProperties.Mail(), "https://ledger.example.com")).isEqualTo(SmtpSettings.Absent)
    }

    @Test
    fun `a partial group names every missing variable, the public url included`() {
        val partial = AppProperties.Mail(host = "smtp.gmail.com", username = "me@gmail.com")

        val resolved = SmtpSettings.resolve(partial, "")

        assertThat(resolved).isEqualTo(
            SmtpSettings.PartiallyConfigured(listOf("SMTP_PASSWORD", "MAIL_FROM", "APP_PUBLIC_URL")),
        )
    }

    @Test
    fun `a group missing only the public url is still not configured`() {
        assertThat(SmtpSettings.resolve(complete, "  "))
            .isEqualTo(SmtpSettings.PartiallyConfigured(listOf("APP_PUBLIC_URL")))
    }

    @Test
    fun `a complete group is configured with the public url normalized`() {
        val resolved = SmtpSettings.resolve(complete, " https://ledger.example.com/ ") as SmtpSettings.Configured

        assertThat(resolved.publicUrl).isEqualTo("https://ledger.example.com")
        assertThat(resolved.host).isEqualTo("smtp.gmail.com")
        assertThat(resolved.port).isEqualTo(587)
        assertThat(resolved.startTls).isTrue()
        assertThat(resolved.from).isEqualTo("Shared Ledger <me@gmail.com>")
    }
}
