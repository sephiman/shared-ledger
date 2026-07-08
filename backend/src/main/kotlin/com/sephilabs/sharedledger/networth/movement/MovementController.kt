package com.sephilabs.sharedledger.networth.movement

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.PageResponse
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.auth.CurrentUser
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
    private val households: HouseholdRepository,
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
        val dto = service.create(householdId, body, currentUser.requireUser())
        return ResponseEntity.status(201).body(dto)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): MovementDto = service.get(householdId, id)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: MovementRequest,
    ): MovementDto = service.update(householdId, id, body, currentUser.requireUser())

    @DeleteMapping("/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(householdId, id, currentUser.requireUser())
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/export.csv")
    fun exportCsv(@PathVariable householdId: UUID): ResponseEntity<String> {
        val csv = service.exportCsv(householdId)
        val household = households.findById(householdId).orElseThrow { AppException.notFound("HOUSEHOLD_NOT_FOUND") }
        val filename = Csv.exportFilename(LocalDate.now(), household.name, "movements")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
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
