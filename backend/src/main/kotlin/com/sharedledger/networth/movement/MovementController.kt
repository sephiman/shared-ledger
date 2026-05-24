package com.sharedledger.networth.movement

import com.sharedledger.common.PageResponse
import com.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/movements")
class MovementController(
    private val service: MovementService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) type: MovementType?,
        @RequestParam(required = false) assetClassCode: String?,
        @RequestParam(required = false) liabilityId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): PageResponse<MovementDto> = service.search(
        MovementSearchCriteria(householdId, from, to, type, assetClassCode, liabilityId, page, size.coerceIn(1, 200))
    )

    @PostMapping
    fun create(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: MovementRequest,
    ): ResponseEntity<MovementDto> {
        val m = service.create(householdId, body, currentUser.requireUser())
        return ResponseEntity.status(201).body(m.toDto())
    }

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): MovementDto = service.get(householdId, id)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: MovementRequest,
    ): MovementDto = service.update(householdId, id, body, currentUser.requireUser()).toDto()

    @DeleteMapping("/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(householdId, id, currentUser.requireUser())
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/export.csv")
    fun exportCsv(@PathVariable householdId: UUID): ResponseEntity<String> {
        val csv = service.exportCsv(householdId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"movements.csv\"")
            .body(csv)
    }

    @GetMapping("/cumulative")
    fun cumulative(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "asset_class") groupBy: String,
    ): List<CumulativeBucket> {
        val fromD = from ?: LocalDate.of(1970, 1, 1)
        val toD = to ?: LocalDate.now()
        return service.cumulative(householdId, fromD, toD, groupBy)
    }
}
