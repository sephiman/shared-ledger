package com.sephilabs.sharedledger.identity.auth

import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class DismissedConsentNoticesTest : AuthHttpTestBase() {

    @Test
    fun `a dismissed consent banner is remembered per user and returned by me`() {
        val session = sessionOf(register(newEmail("dismiss")))

        dismiss(session, """["$CONNECTION@2026-10-12", "$CONNECTION@2026-10-12"]""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.dismissedConsentNotices.length()").value(1))

        me(session).andExpect(jsonPath("$.dismissedConsentNotices[0]").value("$CONNECTION@2026-10-12"))
    }

    @Test
    fun `sending the pruned set replaces what was dismissed before`() {
        val session = sessionOf(register(newEmail("dismiss-prune")))
        dismiss(session, """["$CONNECTION@2026-10-12"]""").andExpect(status().isOk)

        dismiss(session, "[]").andExpect(status().isOk)

        me(session).andExpect(jsonPath("$.dismissedConsentNotices.length()").value(0))
    }

    @Test
    fun `returns 400 for a key that is not a connection and expiry date`() {
        val session = sessionOf(register(newEmail("dismiss-bad")))

        dismiss(session, """["not-a-key"]""").andExpect(status().isBadRequest)
    }

    private fun dismiss(session: Cookie, notices: String): ResultActions =
        mockMvc.perform(
            put("/api/auth/me/dismissed-consent-notices").with(csrf()).cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "notices": $notices }"""),
        )

    private companion object {
        const val CONNECTION = "0f8c2a54-6a3e-4c1e-9d7b-1b2c3d4e5f60"
    }
}
