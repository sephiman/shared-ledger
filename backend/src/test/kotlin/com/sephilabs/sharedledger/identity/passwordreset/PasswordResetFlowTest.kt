package com.sephilabs.sharedledger.identity.passwordreset

import com.sephilabs.sharedledger.common.SecureTokens
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.mail.RecordingEmailSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** Shares the recording mailer and the availability stub with the other mail-backed flows; the lock keeps
 *  those classes from running at the same time. Every request carries its own source IP so the per-IP
 *  buckets of one test never bleed into another. */
@ResourceLock("mail-doubles")
class PasswordResetFlowTest @Autowired constructor(
    private val mailer: RecordingEmailSender,
    private val tokens: PasswordResetTokenRepository,
    private val props: AppProperties,
) : AuthHttpTestBase() {

    @BeforeEach
    fun letMailThrough() {
        mailer.nextOk = true
    }

    @Test
    fun `answers identically whether or not the address belongs to an account`() {
        val ip = uniqueIp()
        val known = newEmail("reset-known")
        register(known, PASSWORD, "en", ip)
        val unknown = newEmail("reset-unknown")

        val forKnown = requestReset(known, ip)
        val forUnknown = requestReset(unknown, ip)

        assertThat(forKnown.response.status).isEqualTo(202)
        assertThat(forUnknown.response.status).isEqualTo(202)
        assertThat(forUnknown.response.contentAsString).isEqualTo(forKnown.response.contentAsString)
        assertThat(mailer.to(known)).hasSize(1)
        assertThat(mailer.to(unknown)).isEmpty()
    }

    @Test
    fun `the mailed link resets the password once and is rejected afterwards`() {
        val ip = uniqueIp()
        val email = newEmail("reset-once")
        register(email, PASSWORD, "en", ip)
        requestReset(email, ip)
        val token = tokenMailedTo(email)

        validate(token, ip).andExpect(status().isOk)
        confirm(token, NEW_PASSWORD, ip).andExpect(status().isOk)

        login(email, NEW_PASSWORD, ip).andExpect(status().isOk)
        login(email, PASSWORD, ip).andExpect(status().isUnauthorized)
        confirm(token, "yet-another-password", ip)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"))
        validate(token, ip)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"))
    }

    @Test
    fun `an expired link is rejected with the same code as an unknown one`() {
        val ip = uniqueIp()
        val email = newEmail("reset-expired")
        register(email, PASSWORD, "en", ip)
        requestReset(email, ip)
        val token = tokenMailedTo(email)
        val row = tokens.findByTokenHash(SecureTokens.hash(token))!!
        row.expiresAt = Instant.now().minusSeconds(1)
        tokens.save(row)

        validate(token, ip).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"))
        confirm(token, NEW_PASSWORD, ip).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"))
        validate("not-a-token-at-all", ip).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"))
        login(email, PASSWORD, ip).andExpect(status().isOk)
    }

    @Test
    fun `requesting a new link invalidates the previous unexpired one`() {
        val ip = uniqueIp()
        val email = newEmail("reset-again")
        register(email, PASSWORD, "en", ip)
        requestReset(email, ip)
        val first = tokenMailedTo(email)
        requestReset(email, ip)
        val second = tokenMailedTo(email)

        assertThat(second).isNotEqualTo(first)
        validate(first, ip).andExpect(status().isBadRequest)
        validate(second, ip).andExpect(status().isOk)
        assertThat(tokens.findByTokenHash(SecureTokens.hash(first))).isNull()
    }

    @Test
    fun `a completed reset invalidates every active session of the user`() {
        val ip = uniqueIp()
        val email = newEmail("reset-sessions")
        val fromRegister = sessionOf(register(email, PASSWORD, "en", ip))
        val fromLogin = sessionOf(login(email, PASSWORD, ip).andReturn())
        me(fromRegister).andExpect(status().isOk)
        me(fromLogin).andExpect(status().isOk)
        assertThat(sessionRows(email)).isGreaterThanOrEqualTo(2)

        requestReset(email, ip)
        confirm(tokenMailedTo(email), NEW_PASSWORD, ip).andExpect(status().isOk)

        me(fromRegister).andExpect(status().isUnauthorized)
        me(fromLogin).andExpect(status().isUnauthorized)
        assertThat(sessionRows(email)).isZero()
        login(email, NEW_PASSWORD, ip).andExpect(status().isOk)
    }

    @Test
    fun `limits requests per source ip`() {
        val ip = uniqueIp()
        repeat(props.passwordReset.perHourPerIp.toInt()) { i ->
            assertThat(requestReset(newEmail("reset-ip$i"), ip).response.status).isEqualTo(202)
        }

        val blocked = requestReset(newEmail("reset-ip-over"), ip)

        assertThat(blocked.response.status).isEqualTo(429)
        assertThat(requestReset(newEmail("reset-other-ip"), uniqueIp()).response.status).isEqualTo(202)
    }

    @Test
    fun `limits requests per target email across source ips, case-insensitively`() {
        val email = newEmail("reset-target")
        repeat(props.passwordReset.perHourPerEmail.toInt()) {
            assertThat(requestReset(email, uniqueIp()).response.status).isEqualTo(202)
        }

        val blocked = requestReset(email.uppercase(), uniqueIp())

        assertThat(blocked.response.status).isEqualTo(429)
        assertThat(blocked.response.contentAsString).contains("RATE_LIMITED")
    }

    @Test
    fun `writes the mail in the user's stored locale with the link on a line of its own`() {
        val ip = uniqueIp()
        val email = newEmail("reset-es")
        register(email, PASSWORD, "es", ip)

        requestReset(email, ip)

        val mail = mailer.to(email).last()
        assertThat(mail.subject).isEqualTo("Restablece tu contraseña de Shared Ledger")
        assertThat(mail.body).contains("60 minutos")
        val linkLine = mail.body.lines().single { it.startsWith("https://ledger.test/reset-password?token=") }
        assertThat(linkLine).doesNotContain(" ")
        assertThat(mail.body).doesNotContain(SecureTokens.hash(linkLine.substringAfter("token=")))
    }

    @Test
    fun `a failed delivery is logged and the request still succeeds`() {
        val ip = uniqueIp()
        val email = newEmail("reset-smtp-down")
        register(email, PASSWORD, "en", ip)
        mailer.nextOk = false

        assertThat(requestReset(email, ip).response.status).isEqualTo(202)
        assertThat(mailer.to(email)).hasSize(1)
    }

    private fun requestReset(email: String, ip: String): MvcResult =
        mockMvc.perform(
            post("/api/auth/password-reset").with(csrf()).with(from(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "email": "$email" }"""),
        ).andReturn()

    private fun validate(token: String, ip: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/password-reset/validate").with(csrf()).with(from(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "token": "$token" }"""),
        )

    private fun confirm(token: String, newPassword: String, ip: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/password-reset/confirm").with(csrf()).with(from(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "token": "$token", "newPassword": "$newPassword" }"""),
        )

    /** The raw token from the latest mail to [email]; also asserts the link stands alone on its line. */
    private fun tokenMailedTo(email: String): String {
        val body = mailer.to(email).last().body
        val linkLine = body.lines().single { it.startsWith("https://ledger.test/reset-password?token=") }
        return linkLine.substringAfter("token=")
    }
}
