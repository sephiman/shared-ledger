package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.household.RequireHouseholdOwner
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/banks/rules")
class CategorizationRuleController(
    private val service: CategorizationService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<CategorizationRuleDto> = service.list(householdId)

    @RequireHouseholdOwner
    @PostMapping
    fun create(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: CategorizationRuleRequest,
    ): CategorizationRuleDto = service.create(householdId, body, currentUser.requireUser())

    @RequireHouseholdOwner
    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: CategorizationRuleRequest,
    ): CategorizationRuleDto = service.update(householdId, id, body)

    @RequireHouseholdOwner
    @DeleteMapping("/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(householdId, id)
        return ResponseEntity.noContent().build()
    }
}
