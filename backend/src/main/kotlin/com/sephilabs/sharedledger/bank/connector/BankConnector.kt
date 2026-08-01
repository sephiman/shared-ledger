package com.sephilabs.sharedledger.bank.connector

import java.time.LocalDate

/**
 * A PSD2 AIS aggregator behind one interface. [EnableBankingConnector] is the primary
 * implementation (Restricted Production). Alternative providers (Yapily, or Wise via its own API)
 * would implement the same contract; the CSV importer remains the always-available fallback.
 *
 * Every method takes the [EbCredentials] to act under, so the connector stays a stateless singleton
 * (one process-wide request pacer) rather than being built per household.
 *
 * All methods talk to the provider over HTTP and throw [BankConnectorException] on any failure.
 */
interface BankConnector {

    /** The bank (ASPSP) catalogue for a country, so the picker is never a hard-coded list. */
    fun listAspsps(creds: EbCredentials, country: String): List<Aspsp>

    /** Begin an SCA authorization; returns the URL to redirect the holder to their bank. */
    fun startAuthorization(creds: EbCredentials, request: AuthStartRequest): AuthStart

    /** Exchange the redirect `code` for a session and the accounts it authorizes. */
    fun completeAuthorization(creds: EbCredentials, code: String): AuthorizedSession

    /** Current consent status for a session (drives expiry/suspension handling). */
    fun sessionStatus(creds: EbCredentials, sessionId: String): ConsentStatus

    /**
     * One page of an account's movements. Pass the previous page's `continuationKey` to page
     * forward; null starts a new fetch. Callers must keep paging until a page returns no
     * `continuationKey` — an empty page may still carry one.
     *
     * [strategy] picks the window semantics: [FetchStrategy.LONGEST] ignores the dates and pulls
     * all available history; [FetchStrategy.DEFAULT] is bounded by [dateFrom] (inclusive) /
     * [dateTo] (exclusive). [psu], when present, marks the call as interactive (not background).
     */
    fun fetchMovements(
        creds: EbCredentials,
        sessionId: String,
        accountUid: String,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        strategy: FetchStrategy,
        continuationKey: String? = null,
        psu: PsuContext? = null,
    ): MovementPage
}
