package com.sephilabs.sharedledger.household.invitation

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class InvitationPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val invitations: HouseholdInvitationRepository,
    private val service: InvitationService,
) : IntegrationTestBase() {

    @Test
    fun `issue persists invitation row with hashed token`() {
        val (owner, household) = seedOwnerAndHousehold()

        val issued = service.issue(
            household.id,
            CreateInvitationRequest(email = "guest@example.com", role = HouseholdRole.member),
            owner,
        )

        val reloaded = invitations.findById(issued.id).orElseThrow()
        assertThat(reloaded.householdId).isEqualTo(household.id)
        assertThat(reloaded.email).isEqualTo("guest@example.com")
        assertThat(reloaded.role).isEqualTo(HouseholdRole.member)
        assertThat(reloaded.createdByUserId).isEqualTo(owner.id)
        assertThat(reloaded.acceptedAt).isNull()
        assertThat(reloaded.revokedAt).isNull()
        // Token is stored hashed, never raw.
        assertThat(reloaded.tokenHash).isNotEqualTo(issued.token)
        assertThat(reloaded.tokenHash).isEqualTo(InvitationTokens.hash(issued.token))
    }

    @Test
    fun `revoke marks revokedAt`() {
        val (owner, household) = seedOwnerAndHousehold()
        val issued = service.issue(household.id, CreateInvitationRequest(role = HouseholdRole.member), owner)
        assertThat(invitations.findById(issued.id).orElseThrow().revokedAt).isNull()

        service.revoke(household.id, issued.id, owner)

        val reloaded = invitations.findById(issued.id).orElseThrow()
        assertThat(reloaded.revokedAt).isNotNull
        assertThat(reloaded.updatedByUserId).isEqualTo(owner.id)
    }

    @Test
    fun `accept marks acceptedAt and creates a household membership`() {
        val (owner, household) = seedOwnerAndHousehold()
        val guest = users.save(User(email = "g${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val issued = service.issue(household.id, CreateInvitationRequest(role = HouseholdRole.member), owner)

        service.accept(issued.token, guest)

        val reloaded = invitations.findById(issued.id).orElseThrow()
        assertThat(reloaded.acceptedAt).isNotNull
        assertThat(reloaded.acceptedByUserId).isEqualTo(guest.id)

        val membership = members.findByIdHouseholdIdAndIdUserId(household.id, guest.id)
        assertThat(membership).isNotNull
        assertThat(membership!!.role).isEqualTo(HouseholdRole.member)
    }

    @Test
    fun `consumeIfPresent (registration path) marks acceptedAt and creates membership`() {
        val (owner, household) = seedOwnerAndHousehold()
        val newcomer = users.save(User(email = "n${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val issued = service.issue(household.id, CreateInvitationRequest(role = HouseholdRole.owner), owner)

        val joinedHouseholdId = service.consumeIfPresent(issued.token, newcomer)

        assertThat(joinedHouseholdId).isEqualTo(household.id)
        val reloaded = invitations.findById(issued.id).orElseThrow()
        assertThat(reloaded.acceptedAt).isNotNull
        assertThat(reloaded.acceptedByUserId).isEqualTo(newcomer.id)

        val membership = members.findByIdHouseholdIdAndIdUserId(household.id, newcomer.id)
        assertThat(membership).isNotNull
        assertThat(membership!!.role).isEqualTo(HouseholdRole.owner)
    }

    private fun seedOwnerAndHousehold(): Pair<User, Household> {
        val owner = users.save(User(email = "o${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        members.save(HouseholdMember(HouseholdMemberId(household.id, owner.id), HouseholdRole.owner))
        return owner to household
    }
}
