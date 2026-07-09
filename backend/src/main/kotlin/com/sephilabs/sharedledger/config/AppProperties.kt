package com.sephilabs.sharedledger.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val security: Security = Security(),
    val registration: Registration = Registration(),
    val bootstrap: Bootstrap = Bootstrap(),
    val invitations: Invitations = Invitations(),
    val scheduler: Scheduler = Scheduler(),
    val autoSnapshot: AutoSnapshot = AutoSnapshot(),
    val fire: Fire = Fire(),
    val telegram: Telegram = Telegram(),
    val portfolio: Portfolio = Portfolio(),
    val enableBanking: EnableBanking = EnableBanking(),
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

    data class AutoSnapshot(
        // Daily check; per-household frequency decides whether today is a due date.
        val cron: String = "0 0 6 * * *",
        // Which day scheduled weekly snapshots land on.
        val weeklyDay: java.time.DayOfWeek = java.time.DayOfWeek.MONDAY,
        // Day-of-month for monthly snapshots; clamped to the month's length.
        val monthlyDay: Int = 1,
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

    enum class EquityProviderKind { YAHOO, EODHD, TWELVE_DATA }

    data class Portfolio(
        val coingecko: PriceProviderConfig = PriceProviderConfig(
            baseUrl = "https://api.coingecko.com",
            minRequestIntervalMs = 2500,
        ),
        // Binance public data (keyless): crypto history fallback when CoinGecko can't
        // serve a range (365-day Demo ceiling) or is down. USDT quotes treated as USD.
        val binance: PriceProviderConfig = PriceProviderConfig(
            baseUrl = "https://api.binance.com",
            minRequestIntervalMs = 300,
        ),
        // Yahoo Finance (unofficial, keyless): no hard daily quota, long history, native
        // EUR for Xetra listings. Fragile and ToS-gray — official alternatives below.
        val yahoo: PriceProviderConfig = PriceProviderConfig(
            baseUrl = "https://query1.finance.yahoo.com",
            minRequestIntervalMs = 1500,
        ),
        val yahooFallbackBaseUrl: String = "https://query2.finance.yahoo.com",
        // EODHD (official alternative): free tier covers UCITS ETFs but only 20 calls/day
        // and 1 year of history — set equity-history-ceiling-days: 365 when selected.
        val eodhd: PriceProviderConfig = PriceProviderConfig(
            baseUrl = "https://eodhd.com",
            minRequestIntervalMs = 1000,
        ),
        // Twelve Data (official alternative): free plan does NOT cover European UCITS ETFs.
        val twelvedata: PriceProviderConfig = PriceProviderConfig(
            baseUrl = "https://api.twelvedata.com",
            // Free tier: 8 requests/minute.
            minRequestIntervalMs = 8000,
        ),
        val frankfurter: PriceProviderConfig = PriceProviderConfig(
            baseUrl = "https://api.frankfurter.dev",
            minRequestIntervalMs = 500,
        ),
        val equityProvider: EquityProviderKind = EquityProviderKind.YAHOO,
        val cryptoRefreshCron: String = "0 5 * * * *",
        val fxRefreshCron: String = "0 30 0 * * *",
        // Must run after the FX refresh: non-EUR equity valuations need the day's rate.
        val equityRefreshCron: String = "0 0 1 * * *",
        // CoinGecko Demo plan serves at most 365 days of history.
        val cryptoHistoryCeilingDays: Long = 365,
        // 0 = uncapped (Yahoo serves long history; backfill reaches the earliest lot).
        // Set 365 when equity-provider is EODHD (its free tier caps at 1 year).
        val equityHistoryCeilingDays: Long = 0,
        val backfillOnLink: Boolean = true,
        // Household base currency prices/rates are quoted in.
        val vsCurrency: String = "eur",
        // A valuation whose price observation is older than this is flagged stale.
        val stalePriceThresholdDays: Long = 7,
        // Lot-matching method for sells. Only FIFO is implemented; AVERAGE is reserved.
        val costMethod: CostMethod = CostMethod.FIFO,
    ) {
        val baseCurrency: String get() = vsCurrency.uppercase()
    }

    enum class CostMethod { FIFO, AVERAGE }

    data class PriceProviderConfig(
        val baseUrl: String = "",
        val apiKey: String = "",
        val timeoutMs: Long = 10000,
        val minRequestIntervalMs: Long = 1000,
    )

    /**
     * Enable Banking (PSD2 AIS aggregator, Restricted Production). The operator creates the API
     * application in the Enable Banking Control Panel once and supplies its id + RSA private key
     * via env. The feature is only exposed when both are present ([configured]) — see the
     * three-level visibility in the Settings/Banks UI.
     */
    data class EnableBanking(
        val baseUrl: String = "https://api.enablebanking.com",
        // The API application id (JWT `kid`). Blank = feature not configured.
        val appId: String = "",
        // PKCS#8 PEM RSA private key used to sign the JWT bearer. Blank = feature not configured.
        val privateKey: String = "",
        // Where the bank sends the PSU back after SCA — the SPA callback route.
        val redirectUrl: String = "",
        // Base64 AES key (16/24/32 bytes) encrypting the per-connection session id at rest.
        val secretKey: String = "",
        val timeoutMs: Long = 15000,
        val minRequestIntervalMs: Long = 300,
        // Requested consent lifetime; the bank may shorten it (90–180 days in practice).
        val consentValidDays: Long = 90,
        // How far back to pull on the first sync (banks usually cap history to ~90 days). Only a
        // fallback: the initial sync uses strategy=longest (full history), not this window.
        val backfillDays: Long = 90,
        // Incremental (background) sync re-reads from the last sync point minus this overlap so
        // late-booked items aren't missed; dedup makes the re-read idempotent.
        val syncOverlapDays: Long = 3,
        // On ASPSP_RATE_LIMIT_EXCEEDED, a background connection waits this long before retrying.
        val rateLimitBackoffHours: Long = 6,
        // PSD2 unattended-access ceiling per consent per day (background fetches only; interactive
        // fetches carry PSU headers and are not counted against this budget by the bank).
        val maxCallsPerDay: Int = 4,
        // Twice daily, within the call budget.
        val syncCron: String = "0 0 7,19 * * *",
        // Daily check for consents nearing expiry.
        val reminderCron: String = "0 0 8 * * *",
        // Warn this many days before a consent expires so the holder can re-link in time.
        val reminderDaysBefore: Long = 7,
    ) {
        val configured: Boolean get() = appId.isNotBlank() && privateKey.isNotBlank()
    }
}
