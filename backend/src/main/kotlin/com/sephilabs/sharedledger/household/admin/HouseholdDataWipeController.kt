package com.sephilabs.sharedledger.household.admin

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.RequireHouseholdOwner
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class HouseholdDataWipeRequest(val confirmation: String?)

@RestController
@RequestMapping("/api/households/{householdId}")
class HouseholdDataWipeController(
    private val service: HouseholdDataWipeService,
    private val currentUser: CurrentUser,
) {

    @RequireHouseholdOwner
    @PostMapping("/data-wipe")
    fun wipe(
        @PathVariable householdId: UUID,
        @RequestBody body: HouseholdDataWipeRequest,
    ): ResponseEntity<Void> {
        if (body.confirmation != "delete") {
            throw AppException.badRequest("WIPE_CONFIRMATION_REQUIRED")
        }
        service.wipe(householdId, currentUser.requireUser().id)
        return ResponseEntity.noContent().build()
    }
}
