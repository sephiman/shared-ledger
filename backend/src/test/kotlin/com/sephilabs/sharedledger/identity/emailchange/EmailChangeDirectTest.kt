package com.sephilabs.sharedledger.identity.emailchange

import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.mail.RecordingEmailSender
import com.sephilabs.sharedledger.mail.TogglableMailAvailability
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Without SMTP there is no channel to verify the new address with — and no reset flow to protect either —
 *  so the password check alone carries the change and it applies immediately. */
@ResourceLock("mail-doubles")
class EmailChangeDirectTest @Autowired constructor(
    private val availability: TogglableMailAvailability,
    private val mailer: RecordingEmailSender,
) : AuthHttpTestBase() {

    @BeforeEach
    fun hideSmtp() {
        availability.forced = false
    }

    @AfterEach
    fun restore() {
        availability.forced = null
    }

    @Test
    fun `applies the change at once, keeps the caller signed in and drops their other devices`() {
        val current = newEmail("direct-from")
        val wanted = newEmail("direct-to")
        val caller = sessionOf(register(current))
        val otherDevice = sessionOf(login(current, PASSWORD).andExpect(status().isOk).andReturn())
        val mailsBefore = mailer.sent.size

        val result = requestChange(caller, wanted, PASSWORD)

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString).contains(""""status":"applied"""")
        assertThat(mailer.sent).hasSize(mailsBefore)

        // The caller gets a new session under the new principal; every earlier one is gone.
        val rebuilt = result.response.getCookie("SESSION")!!
        assertThat(rebuilt.value).isNotEqualTo(caller.value)
        me(rebuilt).andExpect(status().isOk).andExpect(jsonPath("$.email").value(wanted))
        me(caller).andExpect(status().isUnauthorized)
        me(otherDevice).andExpect(status().isUnauthorized)
        assertThat(sessionRows(current)).isZero()

        login(wanted, PASSWORD).andExpect(status().isOk)
        login(current, PASSWORD).andExpect(status().isUnauthorized)
    }

    @Test
    fun `still refuses a wrong password and a taken address`() {
        val current = newEmail("direct-guard")
        val other = newEmail("direct-guard-other")
        register(other)
        val session = sessionOf(register(current))

        assertThat(requestChange(session, newEmail("direct-guard-to"), "not-the-password").response.status)
            .isEqualTo(400)
        assertThat(requestChange(session, other, PASSWORD).response.status).isEqualTo(409)

        me(session).andExpect(status().isOk).andExpect(jsonPath("$.email").value(current))
    }

    private fun requestChange(session: Cookie, newEmail: String, password: String): MvcResult =
        mockMvc.perform(
            post("/api/auth/email").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "newEmail": "$newEmail", "currentPassword": "$password" }"""),
        ).andReturn()
}
