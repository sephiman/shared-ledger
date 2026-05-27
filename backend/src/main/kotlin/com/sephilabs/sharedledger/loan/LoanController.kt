package com.sephilabs.sharedledger.loan

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.RequireHouseholdOwner
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/loans")
class LoanController(
    private val service: LoanService,
    private val materializer: LoanScheduleMaterializer,
    private val currentUser: CurrentUser,
    private val households: HouseholdRepository,
) {

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(name = "status", required = false) status: String?,
        @RequestParam(name = "top", required = false) top: Int?,
    ): LoanListResponse = service.list(householdId, status, top)

    @PostMapping
    fun create(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: LoanRequest,
    ): ResponseEntity<LoanDetail> {
        val loan = service.create(householdId, body, currentUser.requireUser())
        return ResponseEntity.status(201).body(service.get(householdId, loan.id))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): LoanDetail =
        service.get(householdId, id)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: LoanRequest,
    ): LoanDetail {
        service.update(householdId, id, body, currentUser.requireUser())
        return service.get(householdId, id)
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(householdId, id, currentUser.requireUser())
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/settle")
    fun settle(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody(required = false) body: LoanStatusTransitionRequest?,
    ): LoanDetail {
        service.settle(householdId, id, body ?: LoanStatusTransitionRequest(), currentUser.requireUser())
        return service.get(householdId, id)
    }

    @PostMapping("/{id}/write-off")
    fun writeOff(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody(required = false) body: LoanStatusTransitionRequest?,
    ): LoanDetail {
        service.writeOff(householdId, id, body ?: LoanStatusTransitionRequest(), currentUser.requireUser())
        return service.get(householdId, id)
    }

    @PostMapping("/{id}/reopen")
    fun reopen(@PathVariable householdId: UUID, @PathVariable id: UUID): LoanDetail {
        service.reopen(householdId, id, currentUser.requireUser())
        return service.get(householdId, id)
    }

    @PostMapping("/{id}/payments")
    fun registerPayment(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: LoanPaymentRequest,
    ): ResponseEntity<LoanDetail> {
        service.registerPayment(householdId, id, body, currentUser.requireUser())
        return ResponseEntity.status(201).body(service.get(householdId, id))
    }

    @PatchMapping("/{id}/payments/{paymentId}")
    fun updatePayment(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @PathVariable paymentId: UUID,
        @Valid @RequestBody body: LoanPaymentRequest,
    ): LoanDetail {
        service.updatePayment(householdId, id, paymentId, body, currentUser.requireUser())
        return service.get(householdId, id)
    }

    @DeleteMapping("/{id}/payments/{paymentId}")
    fun deletePayment(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @PathVariable paymentId: UUID,
    ): LoanDetail {
        service.deletePayment(householdId, id, paymentId, currentUser.requireUser())
        return service.get(householdId, id)
    }

    @GetMapping("/{id}/payments/split-preview")
    fun previewSplit(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestParam date: LocalDate,
        @RequestParam amount: BigDecimal,
        @RequestParam(required = false) excludePaymentId: UUID?,
    ): PaymentSplitPreview = service.previewSplit(householdId, id, date, amount, excludePaymentId)

    @PutMapping("/{id}/schedule")
    fun upsertSchedule(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: LoanScheduleRequest,
    ): LoanDetail {
        service.upsertSchedule(householdId, id, body, currentUser.requireUser())
        return service.get(householdId, id)
    }

    @PostMapping("/{id}/schedule/pause")
    fun pauseSchedule(@PathVariable householdId: UUID, @PathVariable id: UUID): LoanDetail {
        service.setScheduleActive(householdId, id, false, currentUser.requireUser())
        return service.get(householdId, id)
    }

    @PostMapping("/{id}/schedule/resume")
    fun resumeSchedule(@PathVariable householdId: UUID, @PathVariable id: UUID): LoanDetail {
        service.setScheduleActive(householdId, id, true, currentUser.requireUser())
        return service.get(householdId, id)
    }

    @DeleteMapping("/{id}/schedule")
    fun deleteSchedule(@PathVariable householdId: UUID, @PathVariable id: UUID): LoanDetail {
        service.deleteSchedule(householdId, id)
        return service.get(householdId, id)
    }

    @RequireHouseholdOwner
    @PostMapping("/{id}/schedule/run")
    fun materialize(@PathVariable householdId: UUID, @PathVariable id: UUID): Map<String, Any> {
        val created = materializer.fireNow(householdId, id, currentUser.requireUser(), LocalDate.now())
        return mapOf("created" to created)
    }

    @GetMapping("/export.csv")
    fun exportLoans(@PathVariable householdId: UUID): ResponseEntity<String> {
        val csv = service.exportLoansCsv(householdId)
        val household = households.findById(householdId).orElseThrow { AppException.notFound("HOUSEHOLD_NOT_FOUND") }
        val filename = Csv.exportFilename(LocalDate.now(), household.name, "loans")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(csv)
    }

    @GetMapping("/payments/export.csv")
    fun exportPayments(@PathVariable householdId: UUID): ResponseEntity<String> {
        val csv = service.exportPaymentsCsv(householdId)
        val household = households.findById(householdId).orElseThrow { AppException.notFound("HOUSEHOLD_NOT_FOUND") }
        val filename = Csv.exportFilename(LocalDate.now(), household.name, "loan-payments")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(csv)
    }
}
