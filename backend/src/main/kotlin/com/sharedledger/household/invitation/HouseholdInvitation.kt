package com.sharedledger.household.invitation

import com.sharedledger.common.TimestampedEntity
import com.sharedledger.household.HouseholdRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "household_invitations")
class HouseholdInvitation(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false)
    var householdId: UUID,

    @Column(name = "email")
    var email: String? = null,

    @Column(name = "token_hash", nullable = false, unique = true)
    var tokenHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    var role: HouseholdRole,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null,

    @Column(name = "accepted_by_user_id")
    var acceptedByUserId: UUID? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
) : TimestampedEntity()
