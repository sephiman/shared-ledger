package com.sephilabs.sharedledger.identity.passwordreset

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.common.SecureTokens
import com.sephilabs.sharedledger.config.AppProperties
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.session.web.http.SessionRepositoryFilter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Shares the recording mailer and the availability stub with [PasswordResetHiddenTest]; the lock keeps the
 *  two classes from running at the same time. Every request carries its own source IP so the per-IP buckets
 *  of one test never bleed into another. */
@ResourceLock("password-reset-doubles")
class PasswordResetFlowTest @Autowired constructor(
    private val context: WebApplicationContext,
    private val sessionFilter: SessionRepositoryFilter<*>,
    private val mailer: RecordingEmailSender,
    private val tokens: PasswordResetTokenRepository,
    private val props: AppProperties,
    private val jdbc: JdbcTemplate,
) : IntegrationTestBase() {

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        // The Spring Session filter is what makes these sessions SPRING_SESSION rows rather than mock sessions,
        // which the invalidation test relies on.
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(sessionFilter)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
        mailer.nextOk = true
    }

    @Test
    fun `answers identically whether or not the address belongs to an account`() {
        val ip = uniqueIp()
        val known = newEmail("known")
        register(known, PASSWORD, "en", ip)
        val unknown = newEmail("unknown")

        val forKnown = requestReset(known, ip)
        val forUnknown = requestReset(unknown, ip)

        assertThat(forKnown.response.status).isEqualTo(202)
        assertThat(forUnknown.response.status).isEqualTo(202)
        assertThat(forUnknown.response.contentAsString).isEqualTo(forKnown.response.contentAsString)
        assertThat(mailer.sent.filter { it.to == known }).hasSize(1)
        assertThat(mailer.sent.filter { it.to == unknown }).isEmpty()
    }

    @Test
    fun `the mailed link resets the password once and is rejected afterwards`() {
        val ip = uniqueIp()
        val email = newEmail("once")
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
        val email = newEmail("expired")
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
        val email = newEmail("again")
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
        val email = newEmail("sessions")
        val fromRegister = register(email, PASSWORD, "en", ip).response.getCookie("SESSION")!!
        val fromLogin = login(email, PASSWORD, ip).andReturn().response.getCookie("SESSION")!!
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
            assertThat(requestReset(newEmail("ip$i"), ip).response.status).isEqualTo(202)
        }

        val blocked = requestReset(newEmail("ip-over"), ip)

        assertThat(blocked.response.status).isEqualTo(429)
        assertThat(requestReset(newEmail("other-ip"), uniqueIp()).response.status).isEqualTo(202)
    }

    @Test
    fun `limits requests per target email across source ips, case-insensitively`() {
        val email = newEmail("target")
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
        val email = newEmail("es")
        register(email, PASSWORD, "es", ip)

        requestReset(email, ip)

        val mail = mailer.sent.last { it.to == email }
        assertThat(mail.subject).isEqualTo("Restablece tu contraseña de Shared Ledger")
        assertThat(mail.body).contains("60 minutos")
        val linkLine = mail.body.lines().single { it.startsWith("https://ledger.test/reset-password?token=") }
        assertThat(linkLine).doesNotContain(" ")
        assertThat(mail.body).doesNotContain(SecureTokens.hash(linkLine.substringAfter("token=")))
    }

    @Test
    fun `a failed delivery is logged and the request still succeeds`() {
        val ip = uniqueIp()
        val email = newEmail("smtp-down")
        register(email, PASSWORD, "en", ip)
        mailer.nextOk = false

        assertThat(requestReset(email, ip).response.status).isEqualTo(202)
        assertThat(mailer.sent.filter { it.to == email }).hasSize(1)
    }

    private fun register(email: String, password: String, locale: String, ip: String): MvcResult =
        mockMvc.perform(
            post("/api/auth/register").with(csrf()).with(from(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "email": "$email",
                      "password": "$password",
                      "locale": "$locale",
                      "household": { "name": "Home", "currency": "EUR", "defaultLocale": "$locale" }
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn()

    private fun login(email: String, password: String, ip: String): ResultActions =
        mockMvc.perform(
            post("/api/auth/login").with(csrf()).with(from(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "email": "$email", "password": "$password", "rememberMe": true }"""),
        )

    private fun me(session: Cookie): ResultActions = mockMvc.perform(get("/api/auth/me").cookie(session))

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
        val body = mailer.sent.last { it.to == email }.body
        val linkLine = body.lines().single { it.startsWith("https://ledger.test/reset-password?token=") }
        return linkLine.substringAfter("token=")
    }

    private fun sessionRows(email: String): Long =
        jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Long::class.javaObjectType, email) ?: 0L

    private fun from(ip: String) = RequestPostProcessor { request -> request.remoteAddr = ip; request }

    private fun newEmail(tag: String) = "reset-$tag-${System.nanoTime()}@example.com"

    private companion object {
        const val PASSWORD = "password1234"
        const val NEW_PASSWORD = "brand-new-pass-99"
        val ipCounter = AtomicInteger(1)

        fun uniqueIp(): String {
            val n = ipCounter.getAndIncrement()
            return "10.77.${n / 256}.${n % 256}"
        }
    }
}
