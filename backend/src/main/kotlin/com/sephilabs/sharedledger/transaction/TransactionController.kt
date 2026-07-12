package com.sephilabs.sharedledger.transaction

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.PageResponse
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.getOrThrow
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
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
@RequestMapping("/api/households/{householdId}/transactions")
class TransactionController(
    private val service: TransactionService,
    private val currentUser: CurrentUser,
    private val households: HouseholdRepository,
) {

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) direction: Direction?,
        @RequestParam(required = false) categoryCode: String?,
        @RequestParam(required = false) categoryGroup: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(defaultValue = "date_desc") sort: String,
    ): PageResponse<TransactionDto> {
        return service.search(
            TransactionSearchCriteria(
                householdId = householdId,
                from = from,
                to = to,
                direction = direction,
                categoryCode = categoryCode,
                categoryGroup = categoryGroup,
                page = page,
                size = size.coerceIn(1, 200),
                sort = sort,
            )
        )
    }

    @PostMapping
    fun create(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: TransactionRequest,
    ): ResponseEntity<TransactionDto> {
        val tx = service.create(householdId, body, currentUser.requireUser())
        return ResponseEntity.status(201).body(tx.toDto())
    }

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): TransactionDto =
        service.get(householdId, id).toDto()

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: TransactionRequest,
    ): TransactionDto = service.update(householdId, id, body, currentUser.requireUser()).toDto()

    @DeleteMapping("/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(householdId, id, currentUser.requireUser())
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/quick-chips")
    fun quickChips(@PathVariable householdId: UUID): List<QuickChip> =
        service.quickChips(householdId, currentUser.requireUser())

    @GetMapping("/export.csv")
    fun exportCsv(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) direction: Direction?,
        @RequestParam(required = false) categoryCode: String?,
        @RequestParam(required = false) categoryGroup: String?,
    ): ResponseEntity<String> {
        val csv = service.exportCsv(
            TransactionSearchCriteria(
                householdId = householdId,
                from = from,
                to = to,
                direction = direction,
                categoryCode = categoryCode,
                categoryGroup = categoryGroup,
                page = 0,
                size = Int.MAX_VALUE,
                sort = "date_asc",
            )
        )
        return Csv.download(households.getOrThrow(householdId).name, "transactions", csv)
    }
}
