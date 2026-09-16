package com.sephilabs.sharedledger.household.invitation

import com.sephilabs.sharedledger.common.SecureTokens
import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.mail.RecordingEmailSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired

/** Counts mails on the shared recording sender, so it can't run beside the other mail-backed flows. */
@ResourceLock("mail-doubles")
class InvitationPersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val invitations: HouseholdInvitationRepository,
    private val service: InvitationService,
    private val mailer: RecordingEmailSender,
    private val props: AppProperties,
) : IntegrationTestBase() {

    @Test
    fun `issue persists invitation row with hashed token and dispatches email`() {
        val (owner, household) = seedOwnerAndHousehold()
        val recipientEmail = "guest-${System.nanoTime()}@example.com"

        val issued = service.issue(
            household.id,
            CreateInvitationRequest(email = recipientEmail, role = HouseholdRole.member),
            owner,
        )

        val reloaded = invitations.findById(issued.id).orElseThrow()
        assertThat(reloaded.householdId).isEqualTo(household.id)
        assertThat(reloaded.email).isEqualTo(recipientEmail)
        assertThat(reloaded.role).isEqualTo(HouseholdRole.member)
        assertThat(reloaded.createdByUserId).isEqualTo(owner.id)
        assertThat(reloaded.acceptedAt).isNull()
        assertThat(reloaded.revokedAt).isNull()
        // Token is stored hashed, never raw.
        assertThat(reloaded.tokenHash).isNotEqualTo(issued.token)
        assertThat(reloaded.tokenHash).isEqualTo(SecureTokens.hash(issued.token))

        val mail = mailer.sent.lastOrNull { it.to == recipientEmail }
        assertThat(mail).isNotNull
        assertThat(mail!!.subject).contains(household.name)
        assertThat(mail.body).contains("${props.publicUrl.trimEnd('/')}/register?invite=${issued.token}")
    }

    @Test
    fun `issue uses household default locale for invitation email`() {
        val owner = users.save(User(email = "o${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "Hogar", currency = "EUR", defaultLocale = "es"))
        members.save(HouseholdMember(HouseholdMemberId(household.id, owner.id), HouseholdRole.owner))
        val recipientEmail = "spanish-${System.nanoTime()}@example.com"

        service.issue(
            household.id,
            CreateInvitationRequest(email = recipientEmail, role = HouseholdRole.member),
            owner,
        )

        val mail = mailer.sent.last { it.to == recipientEmail }
        assertThat(mail.subject).isEqualTo("Te han invitado a unirte a Hogar en Shared Ledger")
        assertThat(mail.body).contains("Haz clic en el siguiente enlace")
    }

    @Test
    fun `issue does not dispatch email when recipient email is omitted`() {
        val (owner, household) = seedOwnerAndHousehold()
        val sentCountBefore = mailer.sent.size

        service.issue(
            household.id,
            CreateInvitationRequest(role = HouseholdRole.member),
            owner,
        )

        assertThat(mailer.sent.size).isEqualTo(sentCountBefore)
    }

    @Test
    fun `resend resets token and dispatches fresh email`() {
        val (owner, household) = seedOwnerAndHousehold()
        val recipientEmail = "resend-${System.nanoTime()}@example.com"
        val firstIssued = service.issue(
            household.id,
            CreateInvitationRequest(email = recipientEmail, role = HouseholdRole.member),
            owner,
        )

        val resent = service.resend(household.id, firstIssued.id, owner)

        assertThat(resent.token).isNotEqualTo(firstIssued.token)
        assertThat(resent.emailSent).isTrue()

        val reloaded = invitations.findById(firstIssued.id).orElseThrow()
        assertThat(reloaded.tokenHash).isEqualTo(SecureTokens.hash(resent.token))

        val mail = mailer.sent.last { it.to == recipientEmail }
        assertThat(mail.body).contains("${props.publicUrl.trimEnd('/')}/register?invite=${resent.token}")
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
        val email = "g${System.nanoTime()}@example.com"
        val guest = users.save(User(email = email, passwordHash = "x", locale = "en"))
        val issued = service.issue(household.id, CreateInvitationRequest(email = email, role = HouseholdRole.member), owner)

        service.accept(issued.token, guest)

        val reloaded = invitations.findById(issued.id).orElseThrow()
        assertThat(reloaded.acceptedAt).isNotNull
        assertThat(reloaded.acceptedByUserId).isEqualTo(guest.id)

        val membership = members.findByIdHouseholdIdAndIdUserId(household.id, guest.id)
        assertThat(membership).isNotNull
        assertThat(membership!!.role).isEqualTo(HouseholdRole.member)
    }

    @Test
    fun `accept with mismatching email throws INVITATION_EMAIL_MISMATCH`() {
        val (owner, household) = seedOwnerAndHousehold()
        val targetEmail = "invited-${System.nanoTime()}@example.com"
        val otherUser = users.save(User(email = "other-${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val issued = service.issue(household.id, CreateInvitationRequest(email = targetEmail, role = HouseholdRole.member), owner)

        org.junit.jupiter.api.assertThrows<com.sephilabs.sharedledger.common.errors.AppException> {
            service.accept(issued.token, otherUser)
        }.also {
            assertThat(it.code).isEqualTo("INVITATION_EMAIL_MISMATCH")
        }
    }

    @Test
    fun `validateToken with mismatching email throws INVITATION_EMAIL_MISMATCH`() {
        val (owner, household) = seedOwnerAndHousehold()
        val targetEmail = "invited-${System.nanoTime()}@example.com"
        val issued = service.issue(household.id, CreateInvitationRequest(email = targetEmail, role = HouseholdRole.member), owner)

        org.junit.jupiter.api.assertThrows<com.sephilabs.sharedledger.common.errors.AppException> {
            service.validateToken(issued.token, "wrong-${System.nanoTime()}@example.com")
        }.also {
            assertThat(it.code).isEqualTo("INVITATION_EMAIL_MISMATCH")
        }

        // Case-insensitive match succeeds without throwing exception
        service.validateToken(issued.token, targetEmail.uppercase())
    }

    @Test
    fun `consumeIfPresent (registration path) marks acceptedAt and creates membership`() {
        val (owner, household) = seedOwnerAndHousehold()
        val email = "n${System.nanoTime()}@example.com"
        val newcomer = users.save(User(email = email, passwordHash = "x", locale = "en"))
        val issued = service.issue(household.id, CreateInvitationRequest(email = email, role = HouseholdRole.owner), owner)

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
