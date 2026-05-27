package com.sharedledger.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val security: Security = Security(),
    val registration: Registration = Registration(),
    val bootstrap: Bootstrap = Bootstrap(),
    val invitations: Invitations = Invitations(),
    val scheduler: Scheduler = Scheduler(),
    val fire: Fire = Fire(),
    val telegram: Telegram = Telegram(),
) {
    data class Security(
        val cookieSecure: Boolean = true,
        val loginRate: LoginRate = LoginRate(),
        val importRate: ImportRate = ImportRate(),
    )

    data class LoginRate(
        val perMinute: Long = 5,
        val perHour: Long = 20,
    )

    data class ImportRate(
        val perHour: Long = 10,
    )

    data class Registration(
        val mode: RegistrationMode = RegistrationMode.INVITE_ONLY,
    )

    enum class RegistrationMode { OPEN, INVITE_ONLY, CLOSED }

    data class Bootstrap(
        val adminEmail: String = "",
        val adminPassword: String = "",
        val householdName: String = "Home",
        val householdCurrency: String = "EUR",
        val householdLocale: String = "en",
    )

    data class Invitations(
        val ttlDays: Long = 14,
    )

    data class Scheduler(
        val recurringCron: String = "0 0 2 * * *",
        val timezone: String = "UTC",
    )

    data class Fire(
        val monteCarloTrials: Int = 10000,
    )

    data class Telegram(
        val baseUrl: String = "https://api.telegram.org",
        val timeoutMs: Long = 5000,
        // Base64-encoded AES key (16/24/32 bytes) for encrypting bot tokens at rest.
        val tokenKey: String = "",
    )
}
