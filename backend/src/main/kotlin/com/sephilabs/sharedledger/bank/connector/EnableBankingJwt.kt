package com.sephilabs.sharedledger.bank.connector

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/** The RS256 JWT bearer authenticating every Enable Banking call. Hand-rolled with java.security (no JWT
 *  dependency): header `{typ:JWT, alg:RS256, kid:<appId>}`, claims `{iss, aud, iat, exp}`. A fresh
 *  short-lived token per request; the identity comes from [EbCredentials], so the signer holds no config. */
@Component
class EnableBankingJwt {
    // A minimal, self-contained mapper — the payloads are two small maps, so no shared bean needed.
    private val mapper = ObjectMapper()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun bearer(creds: EbCredentials, now: Instant = Instant.now()): String {
        val header = mapOf("typ" to "JWT", "alg" to "RS256", "kid" to creds.appId)
        val claims = mapOf(
            "iss" to "enablebanking.com",
            "aud" to "api.enablebanking.com",
            "iat" to now.epochSecond,
            "exp" to now.plusSeconds(TOKEN_TTL_SECONDS).epochSecond,
        )
        val signingInput = "${encode(header)}.${encode(claims)}"
        val privateKey = try {
            EbPrivateKey.parse(creds.privateKeyBase64)
        } catch (ex: Exception) {
            throw BankConnectorException("Invalid Enable Banking private key", ex)
        }
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.US_ASCII))
        }.sign()
        return "$signingInput.${urlEncoder.encodeToString(signature)}"
    }

    private fun encode(map: Map<String, Any>): String =
        urlEncoder.encodeToString(mapper.writeValueAsBytes(map))

    private companion object {
        const val TOKEN_TTL_SECONDS = 3600L
    }
}

/** Shared by the JWT signer and by the validation that runs before a key is stored, so a key that saves
 *  is by construction a key that can sign. */
object EbPrivateKey {

    /** Strips literal `\n` escapes, PEM armour and whitespace, so any accepted paste lands on the same body. */
    fun normalize(raw: String): String = raw
        .replace("\\n", "\n")
        .replace(PEM_ARMOUR, "")
        .replace(WHITESPACE, "")

    /** Parses a normalized base64 PKCS#8 body. Throws when it is not a usable RSA private key. */
    fun parse(base64Body: String): PrivateKey {
        val der = Base64.getDecoder().decode(base64Body)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /** PKCS#8 names the algorithm with the rsaEncryption OID up front; PKCS#1 goes straight into the
     *  integers. Only used to pick the more helpful of two rejection messages. */
    fun looksLikePkcs1(base64Body: String): Boolean {
        val der = runCatching { Base64.getDecoder().decode(base64Body) }.getOrNull() ?: return false
        if (der.isEmpty() || der[0] != 0x30.toByte()) return false
        val head = der.copyOfRange(0, minOf(der.size, OID_SEARCH_WINDOW))
        return !head.containsSequence(RSA_ENCRYPTION_OID)
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        if (needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }

    private val PEM_ARMOUR = Regex("-----(BEGIN|END)[^-]*-----")
    private val WHITESPACE = Regex("\\s")

    /** DER encoding of OID 1.2.840.113549.1.1.1 (rsaEncryption), tag + length included. */
    private val RSA_ENCRYPTION_OID = byteArrayOf(
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01,
    )

    /** The algorithm identifier sits in the first few bytes; no need to scan a 1–4 KB key. */
    private const val OID_SEARCH_WINDOW = 64
}
