package com.sephilabs.sharedledger.common

import com.sephilabs.sharedledger.common.errors.AppException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Shared AES-GCM construction for secrets at rest (Telegram bot tokens, Enable Banking session ids).
 *  Key is base64 16/24/32 bytes; output is base64( iv(12) || ciphertext+tag ). Callers pass their own
 *  error codes so failures surface domain-specific (`TELEGRAM_TOKEN_CORRUPT` vs `BANK_SESSION_CORRUPT`). */
object AesGcmCipher {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    /** Decodes and validates a base64 AES key, throwing the given codes when absent or malformed. */
    fun parseKey(rawBase64: String, missingCode: String, invalidCode: String): SecretKeySpec {
        if (rawBase64.isBlank()) throw AppException.badRequest(missingCode)
        val keyBytes = try {
            Base64.getDecoder().decode(rawBase64)
        } catch (_: IllegalArgumentException) {
            throw AppException.badRequest(invalidCode)
        }
        if (keyBytes.size !in intArrayOf(16, 24, 32)) {
            throw AppException.badRequest(invalidCode)
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(key: SecretKeySpec, plaintext: String): String {
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(key: SecretKeySpec, stored: String, corruptCode: String): String {
        val bytes = try {
            Base64.getDecoder().decode(stored)
        } catch (_: IllegalArgumentException) {
            throw AppException.badRequest(corruptCode)
        }
        if (bytes.size <= IV_LENGTH) throw AppException.badRequest(corruptCode)
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val ciphertext = bytes.copyOfRange(IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
