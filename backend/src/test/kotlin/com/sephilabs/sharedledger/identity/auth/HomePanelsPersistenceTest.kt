package com.sephilabs.sharedledger.identity.auth

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder

class HomePanelsPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val authService: AuthService,
    private val encoder: PasswordEncoder,
) : IntegrationTestBase() {

    private fun newUser(prefix: String): User =
        users.save(User(email = "$prefix${System.nanoTime()}@example.com", passwordHash = encoder.encode("x")!!, locale = "en"))

    @Test
    fun `defaults to no hidden panels`() {
        val saved = newUser("hp-default")
        assertThat(users.findById(saved.id).orElseThrow().hiddenHomePanels).isEmpty()
    }

    @Test
    fun `hidden panels persist and can be cleared`() {
        val saved = newUser("hp-set")

        authService.setHiddenHomePanels(saved.id, listOf("portfolio", "savings_rate"))
        // Serialized in canonical (enum) order regardless of input order.
        assertThat(users.findById(saved.id).orElseThrow().hiddenHomePanels).isEqualTo("savings_rate,portfolio")

        authService.setHiddenHomePanels(saved.id, emptyList())
        assertThat(users.findById(saved.id).orElseThrow().hiddenHomePanels).isEmpty()
    }

    @Test
    fun `unknown panel ids are rejected`() {
        val saved = newUser("hp-invalid")

        assertThatThrownBy { authService.setHiddenHomePanels(saved.id, listOf("savings_rate", "expenses_chart")) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("INVALID_HOME_PANEL")

        assertThat(users.findById(saved.id).orElseThrow().hiddenHomePanels).isEmpty()
    }

    @Test
    fun `preference is per user`() {
        val alice = newUser("hp-alice")
        val bob = newUser("hp-bob")

        authService.setHiddenHomePanels(alice.id, listOf("money_lent"))

        assertThat(users.findById(alice.id).orElseThrow().hiddenHomePanels).isEqualTo("money_lent")
        assertThat(users.findById(bob.id).orElseThrow().hiddenHomePanels).isEmpty()
    }
}
