package com.sephilabs.sharedledger.notification

import com.sephilabs.sharedledger.common.AesGcmCipher
import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.stereotype.Component

/**
 * AES-GCM encryption for the Telegram bot token at rest. The key comes from
 * `app.telegram.token-key` (env `TELEGRAM_TOKEN_KEY`); see [AesGcmCipher] for the shared
 * construction and output format.
 *
 * The key is only required when a token is actually stored or read; the app boots fine without
 * it and only fails (with a clear error) if a household tries to save/use a token while the key
 * is missing or malformed. Encryption is done explicitly here (not via a JPA AttributeConverter)
 * so the secret never leaks into logs, DTOs, or the GET settings response.
 */
@Component
class TelegramCrypto(private val props: AppProperties) {

    fun encrypt(plaintext: String): String = AesGcmCipher.encrypt(requireKey(), plaintext)

    fun decrypt(stored: String): String = AesGcmCipher.decrypt(requireKey(), stored, "TELEGRAM_TOKEN_CORRUPT")

    private fun requireKey() =
        AesGcmCipher.parseKey(props.telegram.tokenKey, "TELEGRAM_ENCRYPTION_KEY_MISSING", "TELEGRAM_ENCRYPTION_KEY_INVALID")
}
