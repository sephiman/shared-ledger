package com.sephilabs.sharedledger.notification

import com.sephilabs.sharedledger.common.AesGcmCipher
import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.stereotype.Component

/** AES-GCM encryption for the Telegram bot token at rest; key from `app.telegram.token-key`, construction
 *  in [AesGcmCipher]. Explicit rather than a JPA AttributeConverter so the secret never leaks into logs,
 *  DTOs or the GET settings response. The key is only needed when a token is stored or read, so the app
 *  boots without it. */
@Component
class TelegramCrypto(private val props: AppProperties) {

    fun encrypt(plaintext: String): String = AesGcmCipher.encrypt(requireKey(), plaintext)

    fun decrypt(stored: String): String = AesGcmCipher.decrypt(requireKey(), stored, "TELEGRAM_TOKEN_CORRUPT")

    private fun requireKey() =
        AesGcmCipher.parseKey(props.telegram.tokenKey, "TELEGRAM_ENCRYPTION_KEY_MISSING", "TELEGRAM_ENCRYPTION_KEY_INVALID")
}
