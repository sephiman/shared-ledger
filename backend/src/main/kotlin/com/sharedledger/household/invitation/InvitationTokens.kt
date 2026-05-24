package com.sharedledger.household.invitation

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object InvitationTokens {
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
}
