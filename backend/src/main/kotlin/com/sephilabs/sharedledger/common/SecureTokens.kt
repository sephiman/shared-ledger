package com.sephilabs.sharedledger.common

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Opaque one-shot tokens handed to a user exactly once (invitations, password resets): 256 random bits,
 *  URL-safe, and only the SHA-256 hash is ever stored or logged. */
object SecureTokens {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest(token.toByteArray()))
    }

    /** Enough of the hash to correlate log lines, too little to look the stored row up from a log. */
    fun logPrefix(tokenHash: String): String = tokenHash.take(8)
}
