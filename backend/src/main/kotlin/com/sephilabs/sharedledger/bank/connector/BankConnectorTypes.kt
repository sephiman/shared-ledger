package com.sephilabs.sharedledger.bank.connector

import com.sephilabs.sharedledger.transaction.Direction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Provider-agnostic types exchanged with a [BankConnector], mirroring the PSD2 AIS shape but carrying
 *  nothing provider-specific. Every adapter throws [BankConnectorException] on failure. */
open class BankConnectorException(
    message: String,
    cause: Throwable? = null,
    /** The provider's machine-readable code when it sent one (`ASPSP_ERROR`, `WRONG_TRANSACTIONS_PERIOD`, …).
     *  Recorded on the sync run so a failing connection says *why* instead of showing a bare HTTP status. */
    val providerCode: String? = null,
) : RuntimeException(message, cause)

/** The ASPSP rejected an unattended fetch for exceeding its per-consent daily budget. The caller backs
 *  off for a few hours; interactive fetches (PSU headers present) are not subject to this. */
class RateLimitExceededException(message: String, cause: Throwable? = null) : BankConnectorException(message, cause)

/** How to page an account's transactions.
 *  - [LONGEST]: fetch all available history forward; `dateFrom` is a hint, `dateTo` ignored. On-link sync.
 *  - [DEFAULT]: bounded by `dateFrom`/`dateTo`, **both inclusive** per the provider. Incremental syncs. */
enum class FetchStrategy { LONGEST, DEFAULT }

/** Marks a fetch as happening while the holder is online: these headers make the ASPSP treat the call as
 *  interactive rather than a background fetch, which banks rate-limit to ~4/day. Absent for scheduled syncs. */
data class PsuContext(val ipAddress: String, val userAgent: String)

/** The API application a provider call is made under, resolved per household. [privateKeyBase64] is the
 *  bare PKCS#8 body — validated before storage, so signing can treat it as well-formed. */
data class EbCredentials(val appId: String, val privateKeyBase64: String)

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
    // Must match a redirect URL registered in the household's EB application (see BankCallbackUrl).
    val redirectUrl: String,
)

/** The provider's answer: send the holder here to authenticate at their bank. */
data class AuthStart(
    val url: String,
    // Some providers echo an authorization id; kept for diagnostics, not required.
    val authorizationId: String? = null,
)

data class AuthorizedAccount(
    val uid: String,
    val iban: String? = null,
    val name: String? = null,
    val currency: String? = null,
)

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
