package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.bank.connector.BankCrypto
import com.sephilabs.sharedledger.bank.connector.EbCredentials
import com.sephilabs.sharedledger.bank.connector.EbPrivateKey
import com.sephilabs.sharedledger.common.errors.AppException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Per-household Enable Banking credentials. No instance-wide fallback: a household without them
 * can't use the feature, and its connections are parked rather than re-routed through someone
 * else's application. Keys are validated with the same parse the JWT signer uses, so a bad paste
 * fails at the form instead of at the first sync.
 */
@Service
class BankCredentialsService(
    private val repo: BankCredentialsRepository,
    private val connections: BankConnectionRepository,
    private val crypto: BankCrypto,
) {

    /** The household's credentials, or null when none are configured. */
    @Transactional(readOnly = true)
    fun resolve(householdId: UUID): EbCredentials? {
        val row = repo.findByHouseholdId(householdId) ?: return null
        return EbCredentials(appId = row.appId, privateKeyBase64 = crypto.decrypt(row.privateKeyEnc))
    }

    /** For the interactive paths (link, catalogue), where missing credentials are an error. */
    fun require(householdId: UUID): EbCredentials =
        resolve(householdId) ?: throw AppException.badRequest("BANK_CREDENTIALS_REQUIRED")

    @Transactional(readOnly = true)
    fun findRow(householdId: UUID): BankCredentials? = repo.findByHouseholdId(householdId)

    /** Connections that would need re-linking if the household switched to [appId]. */
    @Transactional(readOnly = true)
    fun mismatchedConnections(householdId: UUID, appId: String): Long =
        connections.countMismatchedAppId(householdId, appId)

    /**
     * A blank [rawPrivateKey] keeps the stored one — the form never receives the key back, so
     * "unchanged" has to be expressible. Switching to an application other than the one existing
     * connections were authorized under needs [confirm]; the caller sees the count first.
     */
    @Transactional
    fun save(
        householdId: UUID,
        appId: String,
        rawPrivateKey: String?,
        confirm: Boolean,
        byUserId: UUID,
    ): BankCredentials {
        val existing = repo.findByHouseholdId(householdId)
        val normalizedKey = rawPrivateKey?.takeIf { it.isNotBlank() }?.let { validatedKey(it) }
        if (normalizedKey == null && existing == null) throw AppException.badRequest("BANK_PRIVATE_KEY_REQUIRED")

        val mismatched = mismatchedConnections(householdId, appId)
        if (mismatched > 0 && !confirm) {
            // The count also travels in `fields`: on a first save there is no stored app id, so the
            // UI cannot derive it and would warn about "0 connections".
            throw AppException(
                code = "BANK_CREDENTIALS_APP_ID_CHANGED",
                httpStatus = 409,
                args = arrayOf(mismatched),
                fields = mapOf("connections" to mismatched.toString()),
            )
        }

        val row = existing ?: BankCredentials(
            householdId = householdId,
            appId = appId,
            privateKeyEnc = "",
            createdByUserId = byUserId,
            updatedByUserId = byUserId,
        )
        row.appId = appId
        normalizedKey?.let { row.privateKeyEnc = crypto.encrypt(it) }
        row.updatedByUserId = byUserId
        return repo.save(row)
    }

    /**
     * Normalizes a paste (full PEM or bare base64) and proves it parses as PKCS#8. PKCS#1 is the
     * common wrong paste, so it gets its own message carrying the conversion command.
     */
    fun validatedKey(raw: String): String {
        val normalized = EbPrivateKey.normalize(raw)
        if (normalized.isBlank()) throw AppException.badRequest("BANK_PRIVATE_KEY_INVALID")
        return try {
            EbPrivateKey.parse(normalized)
            normalized
        } catch (_: Exception) {
            if (EbPrivateKey.looksLikePkcs1(normalized)) throw AppException.badRequest("BANK_PRIVATE_KEY_PKCS1")
            throw AppException.badRequest("BANK_PRIVATE_KEY_INVALID")
        }
    }
}
