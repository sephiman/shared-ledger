package com.sephilabs.sharedledger.identity.emailchange

import com.sephilabs.sharedledger.common.SecureTokens
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.mail.RecordingEmailSender
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
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

/** The verified path: SMTP is configured in the test profile, so nothing moves until the link in the mail
 *  to the new address is opened. */
@ResourceLock("mail-doubles")
class EmailChangeFlowTest @Autowired constructor(
    private val mailer: RecordingEmailSender,
    private val tokens: EmailChangeTokenRepository,
    private val props: AppProperties,
) : AuthHttpTestBase() {

    @Test
    fun `mails a link to the new address and a notice to the old one, changing nothing yet`() {
        val current = newEmail("change-from")
        val wanted = newEmail("change-to")
        val session = sessionOf(register(current))

        val result = requestChange(session, wanted, PASSWORD)

        assertThat(result.response.status).isEqualTo(202)
        assertThat(result.response.contentAsString).contains(""""status":"pending"""")
        val confirmation = mailer.to(wanted).last()
        assertThat(confirmation.subject).isEqualTo("Confirm your new Shared Ledger email")
        val linkLine = confirmation.body.lines().single { it.startsWith("$PUBLIC_URL/confirm-email?token=") }
        assertThat(linkLine).doesNotContain(" ")
        // The notice warns the address losing the account without naming the new one or carrying a link.
        val notice = mailer.to(current).last()
        assertThat(notice.subject).isEqualTo("A change of email was requested for your Shared Ledger account")
        assertThat(notice.body).doesNotContain(wanted)
        assertThat(notice.body).doesNotContain("token=")
        // Nothing moved: the old address still logs in and the session still works.
        me(session).andExpect(status().isOk).andExpect(jsonPath("$.email").value(current))
        login(current, PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `the link moves the account and drops every session held under the old address`() {
        val current = newEmail("change-move")
        val wanted = newEmail("change-moved")
        val fromRegister = sessionOf(register(current))
        val fromLogin = sessionOf(login(current, PASSWORD).andExpect(status().isOk).andReturn())
        assertThat(sessionRows(current)).isGreaterThanOrEqualTo(2)
        requestChange(fromRegister, wanted, PASSWORD)

        confirmChange(tokenMailedTo(wanted))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("applied"))
            .andExpect(jsonPath("$.email").value(wanted))

        me(fromRegister).andExpect(status().isUnauthorized)
        me(fromLogin).andExpect(status().isUnauthorized)
        assertThat(sessionRows(current)).isZero()
        login(current, PASSWORD).andExpect(status().isUnauthorized)
        val fresh = sessionOf(login(wanted, PASSWORD).andExpect(status().isOk).andReturn())
        me(fresh).andExpect(status().isOk).andExpect(jsonPath("$.email").value(wanted))
    }

    @Test
    fun `the link works once, and an expired one is refused with the same code`() {
        val current = newEmail("change-once")
        val wanted = newEmail("change-once-to")
        requestChange(sessionOf(register(current)), wanted, PASSWORD)
        val token = tokenMailedTo(wanted)

        confirmChange(token).andExpect(status().isOk)
        confirmChange(token).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_TOKEN_INVALID"))

        val expiredOwner = newEmail("change-expired")
        val expiredTarget = newEmail("change-expired-to")
        requestChange(sessionOf(register(expiredOwner)), expiredTarget, PASSWORD)
        val expired = tokenMailedTo(expiredTarget)
        tokens.findByTokenHash(SecureTokens.hash(expired))!!.let {
            it.expiresAt = Instant.now().minusSeconds(1)
            tokens.save(it)
        }

        confirmChange(expired).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_TOKEN_INVALID"))
        confirmChange("not-a-token-at-all").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_TOKEN_INVALID"))
        login(expiredOwner, PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `asking again invalidates the link mailed before it`() {
        val current = newEmail("change-twice")
        val session = sessionOf(register(current))
        val first = newEmail("change-first")
        val second = newEmail("change-second")
        requestChange(session, first, PASSWORD)
        val firstToken = tokenMailedTo(first)
        requestChange(session, second, PASSWORD)
        val secondToken = tokenMailedTo(second)

        assertThat(secondToken).isNotEqualTo(firstToken)
        confirmChange(firstToken).andExpect(status().isBadRequest)
        confirmChange(secondToken).andExpect(status().isOk)
        login(second, PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `refuses a wrong password and mails nothing`() {
        val current = newEmail("change-badpass")
        val wanted = newEmail("change-badpass-to")
        val session = sessionOf(register(current))
        val before = mailer.sent.size

        requestChange(session, wanted, "not-the-password").let {
            assertThat(it.response.status).isEqualTo(400)
            assertThat(it.response.contentAsString).contains("PASSWORD_MISMATCH")
        }

        assertThat(mailer.sent).hasSize(before)
        me(session).andExpect(status().isOk).andExpect(jsonPath("$.email").value(current))
    }

    @Test
    fun `refuses an address that is taken or unchanged, and mails nothing`() {
        val current = newEmail("change-taken")
        val other = newEmail("change-taken-other")
        register(other)
        val session = sessionOf(register(current))
        val before = mailer.sent.size

        assertThat(requestChange(session, other, PASSWORD).response.status).isEqualTo(409)
        assertThat(requestChange(session, current.uppercase(), PASSWORD).response.status).isEqualTo(400)
        assertThat(requestChange(session, current, PASSWORD).response.contentAsString).contains("EMAIL_UNCHANGED")

        assertThat(mailer.sent).hasSize(before)
    }

    @Test
    fun `refuses the link when the address was registered in the meantime`() {
        val current = newEmail("change-race")
        val wanted = newEmail("change-race-to")
        requestChange(sessionOf(register(current)), wanted, PASSWORD)
        val token = tokenMailedTo(wanted)
        register(wanted)

        confirmChange(token).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
        login(current, PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `a password change cancels a link still waiting to be opened`() {
        val current = newEmail("change-cancelled")
        val wanted = newEmail("change-cancelled-to")
        val session = sessionOf(register(current))
        requestChange(session, wanted, PASSWORD)
        val token = tokenMailedTo(wanted)

        changePassword(session, PASSWORD, NEW_PASSWORD).andExpect(status().isOk)

        confirmChange(token).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_TOKEN_INVALID"))
        login(current, NEW_PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `a completed password reset cancels a link still waiting to be opened`() {
        val current = newEmail("change-reset")
        val wanted = newEmail("change-reset-to")
        val ip = uniqueIp()
        val session = sessionOf(register(current, ip = ip))
        requestChange(session, wanted, PASSWORD)
        val changeToken = tokenMailedTo(wanted)

        mockMvc.perform(
            post("/api/auth/password-reset").with(csrf()).with(from(ip))
                .contentType(MediaType.APPLICATION_JSON).content("""{ "email": "$current" }"""),
        ).andExpect(status().isAccepted)
        val resetToken = mailer.to(current)
            .last { it.body.contains("/reset-password?token=") }
            .body.lines().single { it.startsWith("$PUBLIC_URL/reset-password?token=") }
            .substringAfter("token=")
        mockMvc.perform(
            post("/api/auth/password-reset/confirm").with(csrf()).with(from(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "token": "$resetToken", "newPassword": "$NEW_PASSWORD" }"""),
        ).andExpect(status().isOk)

        confirmChange(changeToken).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_TOKEN_INVALID"))
        login(current, NEW_PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `writes both mails in the user's stored locale`() {
        val current = newEmail("change-es")
        val wanted = newEmail("change-es-to")
        val session = sessionOf(register(current, locale = "es"))

        requestChange(session, wanted, PASSWORD)

        val confirmation = mailer.to(wanted).last()
        assertThat(confirmation.subject).isEqualTo("Confirma tu nuevo email de Shared Ledger")
        assertThat(confirmation.body).contains("60 minutos")
        assertThat(mailer.to(current).last().subject)
            .isEqualTo("Se ha solicitado un cambio de email en tu cuenta de Shared Ledger")
    }

    @Test
    fun `limits how many links one account can send in an hour`() {
        val current = newEmail("change-limit")
        val session = sessionOf(register(current))
        repeat(props.emailChange.perHourPerUser.toInt()) { i ->
            assertThat(requestChange(session, newEmail("change-limit$i"), PASSWORD).response.status).isEqualTo(202)
        }

        val blocked = requestChange(session, newEmail("change-limit-over"), PASSWORD)

        assertThat(blocked.response.status).isEqualTo(429)
        assertThat(blocked.response.contentAsString).contains("RATE_LIMITED")
    }

    private fun requestChange(session: Cookie, newEmail: String, password: String): MvcResult =
        mockMvc.perform(
            post("/api/auth/email").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "newEmail": "$newEmail", "currentPassword": "$password" }"""),
        ).andReturn()

    private fun confirmChange(token: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/email/confirm").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""{ "token": "$token" }"""),
        )

    private fun changePassword(session: Cookie, current: String, next: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/password").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "currentPassword": "$current", "newPassword": "$next" }"""),
        )

    private fun tokenMailedTo(email: String): String =
        mailer.to(email).last { it.body.contains("/confirm-email?token=") }
            .body.lines().single { it.startsWith("$PUBLIC_URL/confirm-email?token=") }
            .substringAfter("token=")

    private companion object {
        const val PUBLIC_URL = "https://ledger.test"
    }
}
