package com.sharedledger.fire

import com.sharedledger.household.RequireHouseholdOwner
import com.sharedledger.identity.auth.CurrentUser
import com.sharedledger.observability.AppMetrics
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/fire")
class FireController(
    private val service: FireService,
    private val currentUser: CurrentUser,
    private val metrics: AppMetrics,
) {

    @GetMapping("/settings")
    fun get(@PathVariable householdId: UUID): FireSettingsDto = with(service) { getOrCreate(householdId).toDto() }

    @RequireHouseholdOwner
    @PutMapping("/settings")
    fun update(@PathVariable householdId: UUID, @Valid @RequestBody body: FireSettingsRequest): FireSettingsDto {
        val by = currentUser.requireUser()
        return with(service) { update(householdId, body, by).toDto() }
    }

    @GetMapping("/projection")
    fun projection(@PathVariable householdId: UUID): FireProjectionResponse =
        metrics.analyticsTimer("fire_projection").record<FireProjectionResponse> {
            service.project(householdId)
        }
}
