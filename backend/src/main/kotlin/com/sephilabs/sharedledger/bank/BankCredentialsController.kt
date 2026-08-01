package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.bank.connector.BankConnector
import com.sephilabs.sharedledger.bank.connector.BankConnectorException
import com.sephilabs.sharedledger.household.RequireHouseholdOwner
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Owner-only: the secret is accepted on save, encrypted at rest, never returned. Unlike linking (per
 *  member, own SCA), choosing the API application is a decision for the whole household. */
@RestController
@RequestMapping("/api/households/{householdId}/banks/credentials")
@RequireHouseholdOwner
class BankCredentialsController(
    private val credentials: BankCredentialsService,
    private val connections: BankConnectionRepository,
    private val callbackUrl: BankCallbackUrl,
    private val connector: BankConnector,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    @Transactional(readOnly = true)
    fun get(@PathVariable householdId: UUID): BankCredentialsDto =
        credentials.findRow(householdId).toDto(householdId)

    @PutMapping
    @Transactional
    fun update(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: BankCredentialsUpdateRequest,
    ): BankCredentialsDto {
        val user = currentUser.requireUser()
        val saved = credentials.save(
            householdId = householdId,
            appId = body.appId.trim(),
            rawPrivateKey = body.privateKey,
            confirm = body.confirm == true,
            byUserId = user.id,
        )
        return saved.toDto(householdId)
    }

    /** Surfaces a wrong application id or mismatched key here rather than at the first sync — what matters
     *  is that the provider accepts the signature. Not @Transactional: the provider call is slow HTTP and
     *  would hold a pooled DB connection for its duration. */
    @PostMapping("/validate")
    fun validate(@PathVariable householdId: UUID): BankCredentialsTestResult {
        val creds = credentials.require(householdId)
        return try {
            connector.listAspsps(creds, PROBE_COUNTRY)
            BankCredentialsTestResult(ok = true, message = null)
        } catch (ex: BankConnectorException) {
            BankCredentialsTestResult(ok = false, message = ex.message)
        }
    }

    private fun BankCredentials?.toDto(householdId: UUID) = BankCredentialsDto(
        appId = this?.appId,
        privateKeyConfigured = !this?.privateKeyEnc.isNullOrBlank(),
        redirectUrl = callbackUrl.current(),
        connectionCount = connections.countByHouseholdId(householdId),
        mismatchedConnectionCount = this?.let { credentials.mismatchedConnections(householdId, it.appId) } ?: 0,
    )

    private companion object {
        const val PROBE_COUNTRY = "NL"
    }
}
