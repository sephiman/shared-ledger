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
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

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

    @Test
    fun `login authenticates a user against the stored argon2 hash`() {
        val email = "login${System.nanoTime()}@example.com"
        val password = "password1234"
        register(email, password)

        // Exercise the real /login path (authManager.authenticate against the persisted hash).
        // A 200 here proves the encoder used at verification matches the one that wrote the hash —
        // ruling out the password-encoder mismatch that would surface as a 401 INVALID_CREDENTIALS.
        mockMvc.perform(
            post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "email": "$email", "password": "$password", "rememberMe": true }"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(email))

        // And a wrong password must be rejected with 401, not accepted.
        mockMvc.perform(
            post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "email": "$email", "password": "wrong-password", "rememberMe": true }"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `login succeeds when the request already carries a valid authenticated session`() {
        // Reproduces the prod scenario: durable sessions mean the browser still holds a valid
        // SESSION cookie when the user lands on /login. Logging in WHILE that session is attached
        // must still authenticate. On master (in-memory) a restart invalidated the cookie, so this
        // path was never exercised; durable sessions made it the normal case.
        val email = "relogin${System.nanoTime()}@example.com"
        val password = "password1234"
        val registered = mockMvc.perform(
            post("/api/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "email": "$email",
                      "password": "$password",
                      "locale": "en",
                      "household": { "name": "Home", "currency": "EUR", "defaultLocale": "en" }
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn()
        val existingSession = registered.request.session as MockHttpSession

        mockMvc.perform(
            post("/api/auth/login").with(csrf()).session(existingSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "email": "$email", "password": "$password", "rememberMe": true }"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.email").value(email))
    }

    private fun register(email: String, password: String) {
        mockMvc.perform(
            post("/api/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "email": "$email",
                      "password": "$password",
                      "locale": "en",
                      "household": { "name": "Home", "currency": "EUR", "defaultLocale": "en" }
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated)
    }
}
