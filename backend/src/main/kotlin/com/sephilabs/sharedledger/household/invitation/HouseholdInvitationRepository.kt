package com.sephilabs.sharedledger.household.invitation

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HouseholdInvitationRepository : JpaRepository<HouseholdInvitation, UUID> {

    fun findByTokenHash(tokenHash: String): HouseholdInvitation?

    fun findAllByHouseholdIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(
        householdId: UUID,
    ): List<HouseholdInvitation>
}
