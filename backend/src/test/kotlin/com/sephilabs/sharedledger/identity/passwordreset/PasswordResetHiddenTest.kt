package com.sephilabs.sharedledger.identity.passwordreset

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.mail.RecordingEmailSender
import com.sephilabs.sharedledger.mail.TogglableMailAvailability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/** The test profile configures SMTP, so "hidden" is simulated through the availability stub. Locked together
 *  with every other test that shares the mail doubles. */
@ResourceLock("mail-doubles")
class PasswordResetHiddenTest @Autowired constructor(
    private val context: WebApplicationContext,
    private val availability: TogglableMailAvailability,
    private val mailer: RecordingEmailSender,
) : IntegrationTestBase() {

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @AfterEach
    fun restore() {
        availability.forced = null
    }

    @Test
    fun `without smtp the login page is told nothing and every reset endpoint answers 404`() {
        availability.forced = false
        val mailsBefore = mailer.sent.size

        mockMvc.perform(get("/api/auth/features"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.passwordReset").value(false))
        post("/api/auth/password-reset", """{ "email": "someone@example.com" }""").andExpect(status().isNotFound)
        post("/api/auth/password-reset/validate", """{ "token": "whatever" }""").andExpect(status().isNotFound)
        post("/api/auth/password-reset/confirm", """{ "token": "whatever", "newPassword": "password1234" }""")
            .andExpect(status().isNotFound)

        assertThat(mailer.sent).hasSize(mailsBefore)
    }

    @Test
    fun `with smtp fully configured the login page is told the reset is available`() {
        mockMvc.perform(get("/api/auth/features"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.passwordReset").value(true))
    }

    private fun post(path: String, body: String) =
        mockMvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
}
