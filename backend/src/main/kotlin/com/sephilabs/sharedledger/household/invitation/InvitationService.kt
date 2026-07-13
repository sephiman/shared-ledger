package com.sephilabs.sharedledger.household.invitation

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.observability.AppMetrics
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class InvitationService(
    private val invitations: HouseholdInvitationRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val props: AppProperties,
    private val metrics: AppMetrics,
) {

    @Transactional
    fun issue(householdId: UUID, request: CreateInvitationRequest, issuedBy: User): IssuedInvitationResponse {
        val token = InvitationTokens.generate()
        val tokenHash = InvitationTokens.hash(token)
        val expiresAt = Instant.now().plus(props.invitations.ttlDays, ChronoUnit.DAYS)
        val invitation = HouseholdInvitation(
            householdId = householdId,
            email = request.email?.lowercase(),
            tokenHash = tokenHash,
            role = request.role,
            createdByUserId = issuedBy.id,
            updatedByUserId = issuedBy.id,
            expiresAt = expiresAt,
        )
        invitations.save(invitation)
        metrics.invitationIssued(request.role.name)
        return IssuedInvitationResponse(invitation.id, token, request.role, invitation.email, expiresAt)
    }

    @Transactional(readOnly = true)
    fun listActive(householdId: UUID): List<InvitationListItem> =
        invitations.findAllByHouseholdIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(householdId).map {
            InvitationListItem(
                id = it.id,
                role = it.role,
                email = it.email,
                createdAt = it.createdAt,
                expiresAt = it.expiresAt,
                accepted = it.acceptedAt != null,
            )
        }

    @Transactional
    fun revoke(householdId: UUID, invitationId: UUID, by: User) {
        val invitation = invitations.findById(invitationId).orElseThrow { AppException.notFound("INVITATION_INVALID") }
        if (invitation.householdId != householdId) throw AppException.notFound("INVITATION_INVALID")
        if (invitation.acceptedAt != null) throw AppException.conflict("INVITATION_ALREADY_ACCEPTED")
        invitation.revokedAt = Instant.now()
        invitation.updatedByUserId = by.id
    }

    @Transactional(readOnly = true)
    fun lookupPublic(token: String): PublicInvitationView {
        val invitation = invitations.findByTokenHash(InvitationTokens.hash(token))
            ?: throw AppException.notFound("INVITATION_INVALID")
        validateActive(invitation)
        val household = households.findById(invitation.householdId).orElseThrow { AppException.notFound("INVITATION_INVALID") }
        return PublicInvitationView(household.name, invitation.role, invitation.expiresAt)
    }

    @Transactional
    fun accept(token: String, acceptingUser: User) {
        val invitation = invitations.findByTokenHash(InvitationTokens.hash(token))
            ?: throw AppException.notFound("INVITATION_INVALID")
        validateActive(invitation)
        val memberId = HouseholdMemberId(invitation.householdId, acceptingUser.id)
        if (members.findByIdHouseholdIdAndIdUserId(invitation.householdId, acceptingUser.id) == null) {
            members.save(HouseholdMember(memberId, invitation.role))
        }
        invitation.acceptedAt = Instant.now()
        invitation.acceptedByUserId = acceptingUser.id
        invitation.updatedByUserId = acceptingUser.id
        metrics.invitationAccepted(invitation.role.name)
    }

    @Transactional
    fun consumeIfPresent(token: String, acceptingUser: User): UUID {
        val invitation = invitations.findByTokenHash(InvitationTokens.hash(token))
            ?: throw AppException.badRequest("INVITATION_INVALID")
        validateActive(invitation)
        val memberId = HouseholdMemberId(invitation.householdId, acceptingUser.id)
        members.save(HouseholdMember(memberId, invitation.role))
        invitation.acceptedAt = Instant.now()
        invitation.acceptedByUserId = acceptingUser.id
        invitation.updatedByUserId = acceptingUser.id
        metrics.invitationAccepted(invitation.role.name)
        return invitation.householdId
    }

    /** Validate a token without consuming it — used during registration before any user lookup. */
    @Transactional(readOnly = true)
    fun validateToken(token: String) {
        val invitation = invitations.findByTokenHash(InvitationTokens.hash(token))
            ?: throw AppException.badRequest("INVITATION_INVALID")
        validateActive(invitation)
    }

    private fun validateActive(invitation: HouseholdInvitation) {
        val now = Instant.now()
        if (invitation.revokedAt != null) throw AppException.badRequest("INVITATION_REVOKED")
        if (invitation.acceptedAt != null) throw AppException.conflict("INVITATION_ALREADY_ACCEPTED")
        if (invitation.expiresAt.isBefore(now)) throw AppException.badRequest("INVITATION_EXPIRED")
    }
}
