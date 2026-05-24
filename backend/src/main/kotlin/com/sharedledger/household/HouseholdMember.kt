package com.sharedledger.household

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

enum class HouseholdRole { owner, member }

@Embeddable
data class HouseholdMemberId(
    @Column(name = "household_id") var householdId: UUID = UUID.randomUUID(),
    @Column(name = "user_id") var userId: UUID = UUID.randomUUID(),
) : Serializable

@Entity
@Table(name = "household_members")
class HouseholdMember(
    @EmbeddedId
    var id: HouseholdMemberId,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    var role: HouseholdRole,

    @Column(name = "joined_at", nullable = false)
    var joinedAt: Instant = Instant.now(),
)
