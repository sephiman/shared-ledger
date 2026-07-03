package com.sephilabs.sharedledger.networth.snapshot

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.RequireHouseholdOwner
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/snapshots")
class SnapshotController(
    private val service: SnapshotService,
    private val autoSnapshots: AutoSnapshotService,
    private val currentUser: CurrentUser,
    private val households: HouseholdRepository,
) {

    @GetMapping("/auto-settings")
    fun autoSettings(@PathVariable householdId: UUID): AutoSnapshotSettingsDto =
        autoSnapshots.getOrCreate(householdId).toDto()

    @RequireHouseholdOwner
    @PutMapping("/auto-settings")
    fun updateAutoSettings(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: AutoSnapshotSettingsRequest,
    ): AutoSnapshotSettingsDto =
        autoSnapshots.update(householdId, body.enabled, body.frequency, currentUser.requireUser()).toDto()

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): List<SnapshotDto> {
        val fromD = from ?: LocalDate.of(1970, 1, 1)
        val toD = to ?: LocalDate.now()
        return service.list(householdId, fromD, toD)
    }

    @GetMapping("/latest")
    fun latest(@PathVariable householdId: UUID): SnapshotDto =
        service.latest(householdId) ?: throw AppException.notFound("SNAPSHOT_NOT_FOUND")

    @GetMapping("/as-of")
    fun asOf(
        @PathVariable householdId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): SnapshotDto = service.asOf(householdId, date) ?: throw AppException.notFound("SNAPSHOT_NOT_FOUND")

    @GetMapping("/previous-for-prefill")
    fun prefill(@PathVariable householdId: UUID): PrefillView = service.prefill(householdId)

    @PostMapping
    fun create(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: SnapshotRequest,
    ): ResponseEntity<SnapshotDto> {
        val by = currentUser.requireUser()
        val dto = service.create(householdId, body, by)
        return ResponseEntity.status(201).body(dto)
    }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: SnapshotRequest,
    ): SnapshotDto {
        val by = currentUser.requireUser()
        return service.update(householdId, id, body, by)
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(householdId, id, currentUser.requireUser())
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/export.csv")
    fun exportCsv(@PathVariable householdId: UUID): ResponseEntity<String> {
        val csv = service.exportCsv(householdId)
        val household = households.findById(householdId).orElseThrow { AppException.notFound("HOUSEHOLD_NOT_FOUND") }
        val filename = Csv.exportFilename(LocalDate.now(), household.name, "snapshots")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(csv)
    }
}
