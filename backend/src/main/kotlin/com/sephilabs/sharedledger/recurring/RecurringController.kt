package com.sephilabs.sharedledger.recurring

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.getOrThrow
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
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/recurring-templates")
class RecurringController(
    private val service: RecurringService,
    private val currentUser: CurrentUser,
    private val households: HouseholdRepository,
) {

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<RecurringTemplateDto> = service.list(householdId)

    @PostMapping
    fun create(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: RecurringTemplateRequest,
    ): ResponseEntity<RecurringTemplateDto> {
        val template = service.create(householdId, body, currentUser.requireUser())
        // Freshly created: nothing has fired yet, so lastFired is null by construction.
        return ResponseEntity.status(201).body(template.toDto(RecurringDateMath.nextOccurrenceAfter(template, LocalDate.now()), null))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): RecurringTemplateDto {
        val t = service.get(householdId, id)
        return t.toDto(RecurringDateMath.nextOccurrenceAfter(t, LocalDate.now()), service.lastFired(t.id))
    }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: RecurringTemplateRequest,
    ): RecurringTemplateDto {
        val t = service.update(householdId, id, body, currentUser.requireUser())
        return t.toDto(RecurringDateMath.nextOccurrenceAfter(t, LocalDate.now()), service.lastFired(t.id))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(householdId, id, currentUser.requireUser())
        return ResponseEntity.noContent().build()
    }

    @RequireHouseholdOwner
    @PostMapping("/{id}/run")
    fun materialize(@PathVariable householdId: UUID, @PathVariable id: UUID): Map<String, Any> {
        val created = service.fireNow(householdId, id, currentUser.requireUser())
        return mapOf("created" to created)
    }

    @GetMapping("/export.csv")
    fun exportCsv(@PathVariable householdId: UUID): ResponseEntity<String> {
        val csv = service.exportCsv(householdId)
        return Csv.download(households.getOrThrow(householdId).name, "recurring-templates", csv)
    }
}
