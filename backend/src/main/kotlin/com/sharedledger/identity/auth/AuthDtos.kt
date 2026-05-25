package com.sharedledger.identity.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class LoginRequest(
    val email: String,
    val password: String,
    val rememberMe: Boolean = true,
)

data class PasswordChangeRequest(
    @field:NotBlank(message = "validation.required")
    val currentPassword: String,

    @field:Size(min = 8, message = "validation.password.length")
    val newPassword: String,
)

data class MeUpdateRequest(
    @field:Pattern(regexp = "en|es", message = "validation.invalid")
    val locale: String,
)

data class DefaultHouseholdRequest(
    val householdId: UUID,
)

data class HouseholdMembershipDto(
    val householdId: UUID,
    val name: String,
    val currency: String,
    val role: String,
)

data class MeResponse(
    val id: UUID,
    val email: String,
    val locale: String,
    val defaultHouseholdId: UUID?,
    val households: List<HouseholdMembershipDto>,
)

data class RegisterRequest(
    @field:Email(message = "validation.email.invalid")
    val email: String,

    @field:Size(min = 8, message = "validation.password.length")
    val password: String,

    @field:Pattern(regexp = "en|es", message = "validation.invalid")
    val locale: String = "en",

    val invitationToken: String? = null,

    val household: NewHouseholdInput? = null,
)

data class NewHouseholdInput(
    @field:NotBlank(message = "validation.required")
    val name: String,
    @field:NotBlank(message = "validation.required")
    val currency: String,
    @field:Pattern(regexp = "en|es", message = "validation.invalid")
    val defaultLocale: String = "en",
)
