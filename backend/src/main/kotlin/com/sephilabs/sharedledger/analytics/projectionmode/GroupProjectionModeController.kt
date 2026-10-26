package com.sephilabs.sharedledger.analytics.projectionmode

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ProjectionModeRequest(val mode: ProjectionMode)

// Any member may change a mode: it shapes a shared estimate, like the rest of the household's analytics.
@RestController
@RequestMapping("/api/households/{householdId}/projection-modes")
class GroupProjectionModeController(private val service: GroupProjectionModeService) {

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<GroupProjectionModeDto> = service.list(householdId)

    @PutMapping("/{groupCode}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable groupCode: String,
        @RequestBody body: ProjectionModeRequest,
    ): GroupProjectionModeDto = service.set(householdId, groupCode, body.mode)
}
