package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.common.PageResponse
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/banks/pending")
class PendingMovementController(
    private val service: PendingMovementService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "pending") status: MovementStatus,
        @RequestParam(required = false) connectionId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): PageResponse<PendingMovementDto> = service.list(householdId, status, connectionId, page, size)

    @GetMapping("/count")
    fun count(@PathVariable householdId: UUID): PendingCountDto =
        PendingCountDto(service.pendingCount(householdId))

    @PostMapping("/{id}/confirm")
    fun confirm(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: ConfirmMovementRequest,
    ): PendingMovementDto = service.confirm(householdId, id, body, currentUser.requireUser())

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable householdId: UUID, @PathVariable id: UUID): PendingMovementDto =
        service.reject(householdId, id, currentUser.requireUser())

    @PatchMapping("/{id}")
    fun edit(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: EditMovementRequest,
    ): PendingMovementDto = service.edit(householdId, id, body)

    @PostMapping("/confirm-batch")
    fun confirmBatch(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: ConfirmBatchRequest,
    ): BatchResultDto = service.confirmBatch(householdId, body.items, currentUser.requireUser())

    @PostMapping("/reject-batch")
    fun rejectBatch(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: BatchIdsRequest,
    ): BatchResultDto = service.rejectBatch(householdId, body.ids, currentUser.requireUser())
}
