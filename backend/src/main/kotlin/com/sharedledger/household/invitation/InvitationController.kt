package com.sharedledger.household.invitation

import com.sharedledger.household.RequireHouseholdOwner
import com.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping
class InvitationController(
    private val service: InvitationService,
    private val currentUser: CurrentUser,
) {

    @RequireHouseholdOwner
    @GetMapping("/api/households/{householdId}/invitations")
    fun list(@PathVariable householdId: UUID): List<InvitationListItem> = service.listActive(householdId)

    @RequireHouseholdOwner
    @PostMapping("/api/households/{householdId}/invitations")
    fun issue(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: CreateInvitationRequest,
    ): ResponseEntity<IssuedInvitationResponse> {
        val issuer = currentUser.requireUser()
        return ResponseEntity.status(201).body(service.issue(householdId, body, issuer))
    }

    @RequireHouseholdOwner
    @DeleteMapping("/api/households/{householdId}/invitations/{invitationId}")
    fun revoke(@PathVariable householdId: UUID, @PathVariable invitationId: UUID): ResponseEntity<Void> {
        val by = currentUser.requireUser()
        service.revoke(householdId, invitationId, by)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/api/invitations/{token}")
    fun publicLookup(@PathVariable token: String): PublicInvitationView = service.lookupPublic(token)

    @PostMapping("/api/invitations/{token}/accept")
    fun accept(@PathVariable token: String): Map<String, String> {
        val user = currentUser.requireUser()
        service.accept(token, user)
        return mapOf("status" to "ok")
    }
}
