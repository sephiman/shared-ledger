package com.sephilabs.sharedledger.bank.connector

import com.sephilabs.sharedledger.common.AesGcmCipher
import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.stereotype.Component

/** AES-GCM encryption for the Enable Banking session id at rest; see [AesGcmCipher] for the construction.
 *  Explicit rather than a JPA AttributeConverter so the session id never leaks into logs, DTOs or API
 *  responses. The key is validated lazily — the app boots without it and only fails when a session is used. */
@Component
class BankCrypto(private val props: AppProperties) {

    fun encrypt(plaintext: String): String = AesGcmCipher.encrypt(requireKey(), plaintext)

    fun decrypt(stored: String): String = AesGcmCipher.decrypt(requireKey(), stored, "BANK_SESSION_CORRUPT")

    private fun requireKey() =
        AesGcmCipher.parseKey(props.enableBanking.secretKey, "BANK_ENCRYPTION_KEY_MISSING", "BANK_ENCRYPTION_KEY_INVALID")
}
