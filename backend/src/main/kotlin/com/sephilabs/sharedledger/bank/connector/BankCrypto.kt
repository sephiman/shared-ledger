package com.sephilabs.sharedledger.bank.connector

import com.sephilabs.sharedledger.common.AesGcmCipher
import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.stereotype.Component

/**
 * AES-GCM encryption for the Enable Banking session id at rest — the handle that grants continued
 * read access to a linked account. The key comes from `app.enable-banking.secret-key`; see
 * [AesGcmCipher] for the shared construction and output format.
 *
 * Encryption is explicit (not a JPA AttributeConverter) so the session id never leaks into logs,
 * DTOs, or API responses. The key is validated lazily — the app boots without it and only fails
 * (with a clear code) if a connection actually needs to store or read a session.
 */
@Component
class BankCrypto(private val props: AppProperties) {

    fun encrypt(plaintext: String): String = AesGcmCipher.encrypt(requireKey(), plaintext)

    fun decrypt(stored: String): String = AesGcmCipher.decrypt(requireKey(), stored, "BANK_SESSION_CORRUPT")

    private fun requireKey() =
        AesGcmCipher.parseKey(props.enableBanking.secretKey, "BANK_ENCRYPTION_KEY_MISSING", "BANK_ENCRYPTION_KEY_INVALID")
}
