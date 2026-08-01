package com.sephilabs.sharedledger.bank

import java.security.KeyPairGenerator
import java.util.Base64

/**
 * One real RSA key pair for the whole test run (generation is the slow part, so it is shared). A
 * genuine key keeps the validation tests honest: what they accept really signs a JWT, and the
 * PKCS#1 form they reject is a real encoding rather than a lookalike.
 */
object BankTestKeys {

    private val keyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }

    /** The bare PKCS#8 base64 body: what a one-line paste looks like, and what we store. */
    val pkcs8Base64: String by lazy { Base64.getEncoder().encodeToString(keyPair.private.encoded) }

    /** The same key as a downloaded PEM file, armour and line breaks included. */
    val pkcs8Pem: String by lazy {
        buildString {
            append("-----BEGIN PRIVATE KEY-----\n")
            pkcs8Base64.chunked(64).forEach { append(it).append('\n') }
            append("-----END PRIVATE KEY-----\n")
        }
    }

    /**
     * The same key in PKCS#1 — the common wrong paste. A PKCS#8 PrivateKeyInfo is
     * `SEQUENCE { INTEGER, SEQUENCE, OCTET STRING }` whose OCTET STRING *is* the PKCS#1 body.
     */
    val pkcs1Base64: String by lazy {
        val der = keyPair.private.encoded
        var i = 0
        fun readLength(): Int {
            val first = der[i++].toInt() and 0xFF
            if (first < 0x80) return first
            var value = 0
            repeat(first and 0x7F) { value = (value shl 8) or (der[i++].toInt() and 0xFF) }
            return value
        }
        fun skipField() {
            i++ // tag
            val length = readLength()
            i += length
        }
        i++ // outer SEQUENCE tag
        readLength()
        skipField() // INTEGER version
        skipField() // SEQUENCE AlgorithmIdentifier
        check(der[i].toInt() and 0xFF == 0x04) { "expected an OCTET STRING holding the PKCS#1 key" }
        i++
        val length = readLength()
        Base64.getEncoder().encodeToString(der.copyOfRange(i, i + length))
    }
}
