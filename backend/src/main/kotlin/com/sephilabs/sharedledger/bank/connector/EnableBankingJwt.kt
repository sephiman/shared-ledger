package com.sephilabs.sharedledger.bank.connector

import com.fasterxml.jackson.databind.ObjectMapper
import com.sephilabs.sharedledger.config.AppProperties
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * Builds the RS256 JWT bearer that authenticates every Enable Banking call. Hand-rolled with
 * java.security (no extra JWT dependency), matching the "explicit crypto" style of [BankCrypto]:
 * header `{typ:JWT, alg:RS256, kid:<appId>}`, claims `{iss, aud, iat, exp}`, signed with the
 * application's RSA private key. A fresh short-lived token is minted per request (cheap).
 */
@Component
class EnableBankingJwt(private val props: AppProperties) {
    // A minimal, self-contained mapper — the payloads are two small maps, so no shared bean needed.
    private val mapper = ObjectMapper()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun bearer(now: Instant = Instant.now()): String {
        val eb = props.enableBanking
        val header = mapOf("typ" to "JWT", "alg" to "RS256", "kid" to eb.appId)
        val claims = mapOf(
            "iss" to "enablebanking.com",
            "aud" to "api.enablebanking.com",
            "iat" to now.epochSecond,
            "exp" to now.plusSeconds(TOKEN_TTL_SECONDS).epochSecond,
        )
        val signingInput = "${encode(header)}.${encode(claims)}"
        val signature = sign(signingInput.toByteArray(Charsets.US_ASCII))
        return "$signingInput.${urlEncoder.encodeToString(signature)}"
    }

    private fun encode(map: Map<String, Any>): String =
        urlEncoder.encodeToString(mapper.writeValueAsBytes(map))

    private fun sign(data: ByteArray): ByteArray {
        val privateKey = try {
            val pem = props.enableBanking.privateKey
                .replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace(Regex("\\s"), "")
            val der = Base64.getDecoder().decode(pem)
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        } catch (ex: Exception) {
            throw BankConnectorException("Invalid Enable Banking private key", ex)
        }
        return Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()
    }

    private companion object {
        const val TOKEN_TTL_SECONDS = 3600L
    }
}
