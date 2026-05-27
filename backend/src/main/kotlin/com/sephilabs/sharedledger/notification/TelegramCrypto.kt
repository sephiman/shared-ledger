package com.sephilabs.sharedledger.notification

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM encryption for the Telegram bot token at rest.
 *
 * The key comes from `app.telegram.token-key` (env `TELEGRAM_TOKEN_KEY`) as a base64-encoded
 * 16/24/32-byte AES key. The output format is base64( iv(12 bytes) || ciphertext+tag ).
 *
 * The key is only required when a token is actually stored or read; the app boots fine without
 * it and only fails (with a clear error) if a household tries to save/use a token while the key
 * is missing or malformed. Encryption is done explicitly here (not via a JPA AttributeConverter)
 * so the secret never leaks into logs, DTOs, or the GET settings response.
 */
@Component
class TelegramCrypto(private val props: AppProperties) {

    private val random = SecureRandom()

    fun encrypt(plaintext: String): String {
        val key = requireKey()
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(stored: String): String {
        val key = requireKey()
        val bytes = try {
            Base64.getDecoder().decode(stored)
        } catch (_: IllegalArgumentException) {
            throw AppException.badRequest("TELEGRAM_TOKEN_CORRUPT")
        }
        if (bytes.size <= IV_LENGTH) throw AppException.badRequest("TELEGRAM_TOKEN_CORRUPT")
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val ciphertext = bytes.copyOfRange(IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun requireKey(): SecretKeySpec {
        val raw = props.telegram.tokenKey
        if (raw.isBlank()) throw AppException.badRequest("TELEGRAM_ENCRYPTION_KEY_MISSING")
        val keyBytes = try {
            Base64.getDecoder().decode(raw)
        } catch (_: IllegalArgumentException) {
            throw AppException.badRequest("TELEGRAM_ENCRYPTION_KEY_INVALID")
        }
        if (keyBytes.size !in intArrayOf(16, 24, 32)) {
            throw AppException.badRequest("TELEGRAM_ENCRYPTION_KEY_INVALID")
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
