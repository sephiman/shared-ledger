package com.sephilabs.sharedledger.identity

import com.sephilabs.sharedledger.IntegrationTestBase
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP fixture for the identity flows. The Spring Session filter is in the chain on purpose: it is what
 * makes these sessions SPRING_SESSION rows rather than mock sessions, which every assertion about killing
 * a user's other devices depends on.
 */
abstract class AuthHttpTestBase : IntegrationTestBase() {

    @Autowired
    protected lateinit var context: WebApplicationContext

    @Autowired
    protected lateinit var sessionFilter: SessionRepositoryFilter<*>

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    protected lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters<DefaultMockMvcBuilder>(sessionFilter)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
    }

    protected fun register(email: String, password: String = PASSWORD, locale: String = "en", ip: String = uniqueIp()): MvcResult =
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

    protected fun login(email: String, password: String, ip: String = uniqueIp()): ResultActions =
        mockMvc.perform(
            post("/api/auth/login").with(csrf()).with(from(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "email": "$email", "password": "$password", "rememberMe": true }"""),
        )

    protected fun sessionOf(result: MvcResult): Cookie = result.response.getCookie("SESSION")!!

    protected fun me(session: Cookie): ResultActions = mockMvc.perform(get("/api/auth/me").cookie(session))

    protected fun sessionRows(email: String): Long =
        jdbc.queryForObject(
            "SELECT count(*) FROM spring_session WHERE principal_name = ?",
            Long::class.javaObjectType,
            email,
        ) ?: 0L

    protected fun from(ip: String) = RequestPostProcessor { request -> request.remoteAddr = ip; request }

    protected fun newEmail(tag: String) = "$tag-${System.nanoTime()}@example.com"

    protected companion object {
        const val PASSWORD = "password1234"
        const val NEW_PASSWORD = "brand-new-pass-99"
        private val ipCounter = AtomicInteger(1)

        /** Every request carries its own source IP by default: register and login are throttled per IP,
         *  and a suite-wide 127.0.0.1 would exhaust one bucket for everyone. */
        fun uniqueIp(): String {
            val n = ipCounter.getAndIncrement()
            return "10.77.${n / 256}.${n % 256}"
        }
    }
}
