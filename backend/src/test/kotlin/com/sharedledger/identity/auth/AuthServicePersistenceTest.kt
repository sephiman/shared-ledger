package com.sharedledger.identity.auth

import com.sharedledger.IntegrationTestBase
import com.sharedledger.identity.user.User
import com.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder

class AuthServicePersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val authService: AuthService,
    private val encoder: PasswordEncoder,
) : IntegrationTestBase() {

    @Test
    fun `updateLocale persists`() {
        val saved = users.save(User(email = "loc${System.nanoTime()}@example.com", passwordHash = encoder.encode("x")!!, locale = "en"))

        authService.updateLocale(saved.id, "es")

        val reloaded = users.findById(saved.id).orElseThrow()
        assertThat(reloaded.locale).isEqualTo("es")
    }

    @Test
    fun `changePassword persists a new hash`() {
        val initial = encoder.encode("oldpass1234")!!
        val saved = users.save(User(email = "pw${System.nanoTime()}@example.com", passwordHash = initial, locale = "en"))

        authService.changePassword(saved.id, "oldpass1234", "newpass5678")

        val reloaded = users.findById(saved.id).orElseThrow()
        assertThat(reloaded.passwordHash).isNotEqualTo(initial)
        assertThat(encoder.matches("newpass5678", reloaded.passwordHash)).isTrue
        assertThat(encoder.matches("oldpass1234", reloaded.passwordHash)).isFalse
    }

    @Test
    fun `recordLogin stamps lastLoginAt`() {
        val saved = users.save(User(email = "ll${System.nanoTime()}@example.com", passwordHash = encoder.encode("x")!!, locale = "en"))
        assertThat(saved.lastLoginAt).isNull()

        authService.recordLogin(saved.id)

        val reloaded = users.findById(saved.id).orElseThrow()
        assertThat(reloaded.lastLoginAt).isNotNull
    }
}
