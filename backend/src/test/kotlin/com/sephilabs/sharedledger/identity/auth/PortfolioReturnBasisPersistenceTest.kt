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

class PortfolioReturnBasisPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val authService: AuthService,
    private val encoder: PasswordEncoder,
) : IntegrationTestBase() {

    private fun newUser(prefix: String): User =
        users.save(User(email = "$prefix${System.nanoTime()}@example.com", passwordHash = encoder.encode("x")!!, locale = "en"))

    @Test
    fun `defaults to open cost`() {
        val saved = newUser("prb-default")
        assertThat(users.findById(saved.id).orElseThrow().portfolioReturnBasis).isEqualTo("OPEN_COST")
    }

    @Test
    fun `basis persists and can be switched back`() {
        val saved = newUser("prb-set")

        authService.setPortfolioReturnBasis(saved.id, "TURNOVER")
        assertThat(users.findById(saved.id).orElseThrow().portfolioReturnBasis).isEqualTo("TURNOVER")

        authService.setPortfolioReturnBasis(saved.id, "NET_INVESTED")
        assertThat(users.findById(saved.id).orElseThrow().portfolioReturnBasis).isEqualTo("NET_INVESTED")

        authService.setPortfolioReturnBasis(saved.id, "OPEN_COST")
        assertThat(users.findById(saved.id).orElseThrow().portfolioReturnBasis).isEqualTo("OPEN_COST")
    }

    @Test
    fun `unknown basis is rejected and leaves the stored value untouched`() {
        val saved = newUser("prb-invalid")

        assertThatThrownBy { authService.setPortfolioReturnBasis(saved.id, "PEAK_DEPLOYED") }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("INVALID_PORTFOLIO_RETURN_BASIS")

        assertThat(users.findById(saved.id).orElseThrow().portfolioReturnBasis).isEqualTo("OPEN_COST")
    }

    @Test
    fun `preference is per user`() {
        val alice = newUser("prb-alice")
        val bob = newUser("prb-bob")

        authService.setPortfolioReturnBasis(alice.id, "TURNOVER")

        assertThat(users.findById(alice.id).orElseThrow().portfolioReturnBasis).isEqualTo("TURNOVER")
        assertThat(users.findById(bob.id).orElseThrow().portfolioReturnBasis).isEqualTo("OPEN_COST")
    }
}
