package com.sharedledger.household

import com.sharedledger.IntegrationTestBase
import com.sharedledger.identity.user.User
import com.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

class HouseholdMembersListTest @Autowired constructor(
    private val controller: HouseholdController,
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val txManager: PlatformTransactionManager,
) : IntegrationTestBase() {

    @Test
    fun `listMembers returns one row per member, owners first then joinedAt asc`() {
        val tx = TransactionTemplate(txManager)
        var householdId: UUID? = null
        var ownerId: UUID? = null
        var memberId: UUID? = null
        var secondOwnerId: UUID? = null

        tx.execute {
            val owner = users.save(User(email = "o${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
            val member = users.save(User(email = "m${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
            val secondOwner = users.save(User(email = "z${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
            val h = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
            members.save(HouseholdMember(HouseholdMemberId(h.id, member.id), HouseholdRole.member, joinedAt = Instant.parse("2026-01-02T00:00:00Z")))
            members.save(HouseholdMember(HouseholdMemberId(h.id, owner.id), HouseholdRole.owner, joinedAt = Instant.parse("2026-01-01T00:00:00Z")))
            members.save(HouseholdMember(HouseholdMemberId(h.id, secondOwner.id), HouseholdRole.owner, joinedAt = Instant.parse("2026-01-03T00:00:00Z")))
            householdId = h.id
            ownerId = owner.id
            memberId = member.id
            secondOwnerId = secondOwner.id
        }

        val rows = controller.listMembers(householdId!!)

        assertThat(rows.map { it.userId }).containsExactly(ownerId, secondOwnerId, memberId)
        assertThat(rows.first().role).isEqualTo(HouseholdRole.owner)
        assertThat(rows.last().role).isEqualTo(HouseholdRole.member)
        assertThat(rows.map { it.email }).allSatisfy { assertThat(it).contains("@example.com") }
    }
}
