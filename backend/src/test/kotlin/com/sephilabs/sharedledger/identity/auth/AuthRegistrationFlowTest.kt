package com.sephilabs.sharedledger.identity.auth

import com.sephilabs.sharedledger.IntegrationTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@TestPropertySource(properties = ["app.registration.mode=open"])
class AuthRegistrationFlowTest @Autowired constructor(
    private val context: WebApplicationContext,
) : IntegrationTestBase() {

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
    }

    @Test
    fun `register drops the new user into an authenticated session`() {
        val email = "reg${System.nanoTime()}@example.com"
        val body = """
            {
              "email": "$email",
              "password": "password1234",
              "locale": "en",
              "household": { "name": "Home", "currency": "EUR", "defaultLocale": "en" }
            }
        """.trimIndent()

        val registered = mockMvc.perform(
            post("/api/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.email").value(email))
            .andReturn()

        // The session created during /register must already carry the security
        // context — i.e. /me succeeds with no intervening /login call. Before the
        // fix this returned 401, forcing every first write to fail.
        val session = registered.request.session as MockHttpSession
        mockMvc.perform(get("/api/auth/me").session(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(email))
    }
}
