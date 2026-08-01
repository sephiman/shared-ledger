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
        val timezone: String = "UTC",
    )

    data class AutoSnapshot(
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
        // Benchmark overlay series: how far back to backfill on the first fill. Also the
        // floor the daily gap-fill extends the head down to, so it never re-pulls history.
        val benchmarkHistoryLookbackDays: Long = 3650,
        // Bootstrap missing benchmark history at startup (off in tests to avoid provider HTTP).
        val benchmarkBackfillOnStart: Boolean = true,
        // Minimum gap between user-triggered "refresh prices" runs, so the button can't
        // hammer providers. A click inside the window is reported as skipped, not re-run.
        val manualRefreshCooldownSeconds: Long = 60,
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
     * Enable Banking (PSD2 AIS aggregator, Restricted Production). The API application id and its
     * private key are **not** here: they are per-household configuration owned by
     * `BankCredentialsService` (Settings → Banks). What stays instance-wide is the encryption key
     * that protects them at rest plus the provider tuning below.
     */
    data class EnableBanking(
        val baseUrl: String = "https://api.enablebanking.com",
        // Base64 AES key (16/24/32 bytes) encrypting the stored household credentials and the
        // per-connection session id at rest. The one Enable Banking secret that remains an env var.
        val secretKey: String = "",
        // Where the bank sends the PSU back after SCA — this instance's public frontend URL plus the
        // SPA callback route. Not a secret and not per household: it identifies the *instance*, so
        // every household registers the same value in its own EB application. Blank falls back to
        // deriving it from the request (see BankCallbackUrl).
        val redirectUrl: String = "",
        val timeoutMs: Long = 15000,
        val minRequestIntervalMs: Long = 300,
        // Requested consent lifetime; the bank may shorten it (90–180 days in practice).
        val consentValidDays: Long = 90,
        // How far back a background (strategy=default) window may reach — banks usually cap
        // unattended history to ~90 days, and strict ones reject an over-long window outright rather
        // than trimming it. Also the starting window when an account has nothing stored yet; the
        // initial on-link sync uses strategy=longest (full history) and ignores this.
        val backfillDays: Long = 90,
        // Incremental (background) sync re-reads from the last sync point minus this overlap so
        // late-booked items aren't missed; dedup makes the re-read idempotent.
        val syncOverlapDays: Long = 3,
        // On ASPSP_RATE_LIMIT_EXCEEDED, a background connection waits this long before retrying.
        val rateLimitBackoffHours: Long = 6,
        // PSD2 unattended-access ceiling per consent per day (background fetches only; interactive
        // fetches carry PSU headers and are not counted against this budget by the bank).
        val maxCallsPerDay: Int = 4,
        // The background-sync cron. Also consumed by the @Scheduled placeholder in yaml; mirrored here
        // so the bank config endpoint can surface upcoming run times to the UI.
        val syncCron: String = "0 0 7,19 * * *",
        // Warn this many days before a consent expires so the holder can re-link in time.
        val reminderDaysBefore: Long = 7,
    )
}
