package com.sephilabs.sharedledger.identity.emailchange

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class EmailChangeRequest(
    @field:NotBlank(message = "validation.required")
    @field:Email(message = "validation.email.invalid")
    val newEmail: String,

    // The session cookie alone must not be enough to move the account's recovery channel.
    @field:NotBlank(message = "validation.required")
    val currentPassword: String,
)

data class EmailChangeTokenRequest(
    @field:NotBlank(message = "validation.required")
    val token: String,
)

/** `pending` when a confirmation link was mailed, `applied` when the address changed right away. */
data class EmailChangeResponse(val status: String, val email: String)
