package com.sephilabs.sharedledger.household

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.auth.AuthService
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class HouseholdLifecycleTest @Autowired constructor(
    private val controller: HouseholdController,
    private val authService: AuthService,
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val txManager: PlatformTransactionManager,
) : IntegrationTestBase() {

    @AfterEach
    fun clearAuth() { SecurityContextHolder.clearContext() }

    private fun loginAs(user: User) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user.email, "x", emptyList())
    }

    private fun newUser(): User = users.save(User(
        email = "u${System.nanoTime()}@example.com",
        passwordHash = "x",
        locale = "en",
    ))

    @Test
    fun `create household persists owner membership and trims name + uppercases currency`() {
        val user = newUser()
        loginAs(user)

        val dto = controller.create(HouseholdCreateRequest(name = "  Alpha  ", currency = "usd")).body!!

        assertThat(dto.name).isEqualTo("Alpha")
        assertThat(dto.currency).isEqualTo("USD")
        assertThat(dto.role).isEqualTo(HouseholdRole.owner.name)

        val membership = members.findByIdHouseholdIdAndIdUserId(dto.id, user.id)
        assertThat(membership).isNotNull
        assertThat(membership!!.role).isEqualTo(HouseholdRole.owner)
    }

    @Test
    fun `setDefaultHousehold rejects non-members and persists for members`() {
        val owner = newUser()
        val outsider = newUser()
        val h = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        members.save(HouseholdMember(HouseholdMemberId(h.id, owner.id), HouseholdRole.owner))

        assertThatThrownBy { authService.setDefaultHousehold(outsider.id, h.id) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("NOT_A_HOUSEHOLD_MEMBER")

        authService.setDefaultHousehold(owner.id, h.id)
        val reloaded = TransactionTemplate(txManager).execute { users.findById(owner.id).orElseThrow() }!!
        assertThat(reloaded.defaultHouseholdId).isEqualTo(h.id)
    }

    @Test
    fun `delete household refuses when any user has it as their default`() {
        val owner = newUser()
        val h = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        members.save(HouseholdMember(HouseholdMemberId(h.id, owner.id), HouseholdRole.owner))
        authService.setDefaultHousehold(owner.id, h.id)

        assertThatThrownBy { controller.delete(h.id) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("HOUSEHOLD_IS_DEFAULT")

        assertThat(households.existsById(h.id)).isTrue()
    }

    @Test
    fun `delete household removes it and its memberships when nobody has it as default`() {
        val owner = newUser()
        val keeper = households.save(Household(name = "Keep", currency = "EUR", defaultLocale = "en"))
        val victim = households.save(Household(name = "Bye", currency = "EUR", defaultLocale = "en"))
        members.save(HouseholdMember(HouseholdMemberId(keeper.id, owner.id), HouseholdRole.owner))
        members.save(HouseholdMember(HouseholdMemberId(victim.id, owner.id), HouseholdRole.owner))
        authService.setDefaultHousehold(owner.id, keeper.id)

        val response = controller.delete(victim.id)

        assertThat(response.statusCode.value()).isEqualTo(204)
        assertThat(households.existsById(victim.id)).isFalse()
        assertThat(members.findByIdHouseholdIdAndIdUserId(victim.id, owner.id)).isNull()
    }
}
