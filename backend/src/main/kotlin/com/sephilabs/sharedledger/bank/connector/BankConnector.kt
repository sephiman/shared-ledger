package com.sephilabs.sharedledger.bank.connector

import java.time.LocalDate

/**
 * A PSD2 AIS aggregator behind one interface. [EnableBankingConnector] is the primary
 * implementation (Restricted Production). Alternative providers (Yapily, or Wise via its own API)
 * would implement the same contract; the CSV importer remains the always-available fallback.
 *
 * All methods talk to the provider over HTTP and throw [BankConnectorException] on any failure.
 */
interface BankConnector {

    /** The bank (ASPSP) catalogue for a country, so the picker is never a hard-coded list. */
    fun listAspsps(country: String): List<Aspsp>

    /** Begin an SCA authorization; returns the URL to redirect the holder to their bank. */
    fun startAuthorization(request: AuthStartRequest): AuthStart

    /** Exchange the redirect `code` for a session and the accounts it authorizes. */
    fun completeAuthorization(code: String): AuthorizedSession

    /** Current consent status for a session (drives expiry/suspension handling). */
    fun sessionStatus(sessionId: String): ConsentStatus

    /**
     * One page of movements for an account since [dateFrom]. Pass the previous page's
     * `continuationKey` to page forward; null starts from the beginning of the window.
     */
    fun fetchMovements(
        sessionId: String,
        accountUid: String,
        dateFrom: LocalDate,
        continuationKey: String? = null,
    ): MovementPage
}
