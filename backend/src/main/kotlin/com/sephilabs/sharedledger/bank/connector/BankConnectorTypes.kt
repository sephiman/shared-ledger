package com.sephilabs.sharedledger.bank.connector

import com.sephilabs.sharedledger.transaction.Direction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Provider-agnostic types exchanged with a [BankConnector]. They deliberately mirror the PSD2 AIS
 * shape (ASPSP catalogue, an authorization redirect, a session yielding accounts, and paginated
 * movements) but carry nothing provider-specific, so a second connector (Yapily, Wise) could
 * implement the same interface. Every adapter throws [BankConnectorException] on failure — callers
 * catch that one type, exactly like the portfolio price providers' `ProviderException`.
 */
open class BankConnectorException(
    message: String,
    cause: Throwable? = null,
    /**
     * The provider's machine-readable code when it sent one (`ASPSP_ERROR`,
     * `WRONG_TRANSACTIONS_PERIOD`, `PSU_HEADER_NOT_PROVIDED`, …). Recorded on the sync run so a
     * failing connection says *why* in the UI instead of showing a bare HTTP status.
     */
    val providerCode: String? = null,
) : RuntimeException(message, cause)

/**
 * The ASPSP rejected an unattended (background) fetch for exceeding its per-consent daily budget
 * (Enable Banking `ASPSP_RATE_LIMIT_EXCEEDED`). The caller backs off for a few hours and retries;
 * interactive fetches (PSU headers present) are not subject to this.
 */
class RateLimitExceededException(message: String, cause: Throwable? = null) : BankConnectorException(message, cause)

/**
 * How to page an account's transactions.
 * - [LONGEST]: find the earliest available transaction and fetch everything forward. `dateFrom` is
 *   only a starting hint and `dateTo` is ignored; no WRONG_TRANSACTIONS_PERIOD. Used for the full
 *   on-link history sync.
 * - [DEFAULT]: bounded by `dateFrom` / `dateTo`, **both inclusive** (the provider documents
 *   "including the date"). Used for incremental syncs.
 */
enum class FetchStrategy { LONGEST, DEFAULT }

/**
 * Marks a fetch as happening while the account holder is online. When present these headers are
 * sent to the ASPSP so the call is treated as interactive (PSU) rather than a background fetch,
 * which the bank rate-limits to ~4/day. Absent for scheduled/background syncs.
 */
data class PsuContext(val ipAddress: String, val userAgent: String)

/** One bank in the provider's catalogue, filtered by country. */
data class Aspsp(
    val name: String,
    val country: String,
    val logoUrl: String? = null,
)

/** What we ask the provider to start: an SCA authorization for one bank. */
data class AuthStartRequest(
    val aspspName: String,
    val country: String,
    // Opaque value round-tripped through the bank redirect; we validate it on return.
    val state: String,
    val validUntil: Instant,
)

/** The provider's answer: send the holder here to authenticate at their bank. */
data class AuthStart(
    val url: String,
    // Some providers echo an authorization id; kept for diagnostics, not required.
    val authorizationId: String? = null,
)

/** An account exposed by an authorized session. */
data class AuthorizedAccount(
    val uid: String,
    val iban: String? = null,
    val name: String? = null,
    val currency: String? = null,
)

/** The result of exchanging the redirect `code`: a session plus the accounts it grants. */
data class AuthorizedSession(
    val sessionId: String,
    val accounts: List<AuthorizedAccount>,
    val consentExpiresAt: Instant?,
)

/** Coarse consent lifecycle as the provider reports it. */
enum class ConsentStatus { ACTIVE, EXPIRED, INVALID }

/** One movement as delivered by the bank (raw; sign already resolved to a [Direction]). */
data class BankMovement(
    // The bank's stable id for this movement, unique within its account/connection.
    val bankMovementId: String,
    val bookingDate: LocalDate,
    val valueDate: LocalDate?,
    val direction: Direction,
    // Always positive; sign is captured by [direction].
    val amount: BigDecimal,
    val currency: String,
    val counterparty: String?,
    val description: String?,
    val reference: String?,
)

/** A page of movements; [continuationKey] is non-null when more pages remain. */
data class MovementPage(
    val movements: List<BankMovement>,
    val continuationKey: String? = null,
)
