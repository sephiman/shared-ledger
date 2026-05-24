package com.sharedledger.household.invitation

import com.sharedledger.household.HouseholdRole
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

data class CreateInvitationRequest(
    @field:Email(message = "validation.email.invalid")
    val email: String? = null,

    @field:NotNull(message = "validation.required")
    val role: HouseholdRole = HouseholdRole.member,
)

data class IssuedInvitationResponse(
    val id: UUID,
    val token: String,
    val role: HouseholdRole,
    val email: String?,
    val expiresAt: Instant,
)

data class InvitationListItem(
    val id: UUID,
    val role: HouseholdRole,
    val email: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val accepted: Boolean,
)

data class PublicInvitationView(
    val householdName: String,
    val role: HouseholdRole,
    val expiresAt: Instant,
)
