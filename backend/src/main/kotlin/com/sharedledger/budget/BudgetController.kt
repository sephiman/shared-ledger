package com.sharedledger.budget

import com.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/budgets")
class BudgetController(
    private val service: BudgetService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam year: Short,
        @RequestParam(required = false) month: Short?,
    ): List<BudgetDto> = if (month != null) service.listMonth(householdId, year, month) else service.listYear(householdId, year)

    @PutMapping
    fun upsert(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: BudgetUpsertRequest,
    ): List<BudgetDto> = service.upsert(householdId, body, currentUser.requireUser())

    @DeleteMapping("/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(householdId, id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/month-summary")
    fun monthSummary(
        @PathVariable householdId: UUID,
        @RequestParam year: Short,
        @RequestParam month: Short,
    ): MonthSummaryResponse = service.monthSummary(householdId, year, month)
}
