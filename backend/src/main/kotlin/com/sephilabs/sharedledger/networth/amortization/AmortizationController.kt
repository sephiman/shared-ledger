package com.sephilabs.sharedledger.networth.amortization

import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.notification.NotifyActor
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.*

@RestController
@RequestMapping("/api/households/{householdId}/liabilities/{liabilityId}/amortization")
class AmortizationController(
    private val parts: AmortizationPartRepository,
    private val revisions: AmortizationRateRevisionRepository,
    private val prepayments: AmortizationPrepaymentRepository,
    private val scheduleService: AmortizationScheduleService,
    private val notifications: NotificationPublisher,
    private val currentUser: CurrentUser,
) {

    @GetMapping("/schedule")
    fun schedule(@PathVariable householdId: UUID, @PathVariable liabilityId: UUID): LiabilityScheduleDto =
        scheduleService.schedule(householdId, liabilityId)

    // ---- Parts ----

    @GetMapping("/parts")
    fun listParts(@PathVariable householdId: UUID, @PathVariable liabilityId: UUID): List<PartDto> {
        scheduleService.loadOwn(householdId, liabilityId)
        return parts.findAllByLiabilityIdOrderByStartDateAscCreatedAtAsc(liabilityId).map { it.toDto() }
    }

    @PostMapping("/parts")
    @Transactional
    fun createPart(
        @PathVariable householdId: UUID,
        @PathVariable liabilityId: UUID,
        @Valid @RequestBody body: PartRequest,
    ): ResponseEntity<PartDto> {
        val by = currentUser.requireUser()
        scheduleService.loadOwn(householdId, liabilityId)
        validatePart(body)
        val part = AmortizationPart(
            liabilityId = liabilityId,
            label = body.label?.trim(),
            method = body.method,
            startMode = body.startMode,
            originalPrincipal = Money.normalize(body.originalPrincipal),
            annualRate = body.annualRate,
            termMonths = effectiveTerm(body),
            instalment = body.instalment?.let { Money.normalize(it) },
            startDate = body.startDate,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        parts.save(part)
        return ResponseEntity.status(201).body(part.toDto())
    }

    @PatchMapping("/parts/{partId}")
    @Transactional
    fun updatePart(
        @PathVariable householdId: UUID,
        @PathVariable liabilityId: UUID,
        @PathVariable partId: UUID,
        @Valid @RequestBody body: PartRequest,
    ): PartDto {
        val by = currentUser.requireUser()
        scheduleService.loadOwn(householdId, liabilityId)
        validatePart(body)
        val part = scheduleService.ownPart(liabilityId, partId)
        part.label = body.label?.trim()
        part.method = body.method
        part.startMode = body.startMode
        part.originalPrincipal = Money.normalize(body.originalPrincipal)
        part.annualRate = body.annualRate
        part.termMonths = effectiveTerm(body)
        part.instalment = body.instalment?.let { Money.normalize(it) }
        part.startDate = body.startDate
        // An anchor only makes sense in origin mode; clear it if the part leaves origin mode.
        if (body.startMode != StartMode.origin) {
            part.anchorDate = null
            part.anchorBalance = null
        }
        part.updatedByUserId = by.id
        return part.toDto()
    }

    @PostMapping("/parts/{partId}/anchor")
    @Transactional
    fun reAnchor(
        @PathVariable householdId: UUID,
        @PathVariable liabilityId: UUID,
        @PathVariable partId: UUID,
        @Valid @RequestBody body: AnchorRequest,
    ): PartDto {
        val by = currentUser.requireUser()
        scheduleService.loadOwn(householdId, liabilityId)
        val part = scheduleService.ownPart(liabilityId, partId)
        if (part.startMode != StartMode.origin) throw AppException.badRequest("AMORTIZATION_ANCHOR_ORIGIN_ONLY")
        if (body.anchorBalance < BigDecimal.ZERO) throw AppException.badRequest("AMORTIZATION_ANCHOR_INVALID")
        part.anchorDate = body.anchorDate
        part.anchorBalance = Money.normalize(body.anchorBalance)
        part.updatedByUserId = by.id
        return part.toDto()
    }

    @DeleteMapping("/parts/{partId}")
    @Transactional
    fun deletePart(
        @PathVariable householdId: UUID,
        @PathVariable liabilityId: UUID,
        @PathVariable partId: UUID,
    ): ResponseEntity<Void> {
        val by = currentUser.requireUser()
        scheduleService.loadOwn(householdId, liabilityId)
        val part = scheduleService.ownPart(liabilityId, partId)
        part.deletedAt = Instant.now()
        part.updatedByUserId = by.id
        return ResponseEntity.noContent().build()
    }

    // ---- Rate revisions ----

    @PostMapping("/parts/{partId}/revisions")
    @Transactional
    fun addRevision(
        @PathVariable householdId: UUID,
        @PathVariable liabilityId: UUID,
        @PathVariable partId: UUID,
        @Valid @RequestBody body: RevisionRequest,
    ): ResponseEntity<RevisionDto> {
        val by = currentUser.requireUser()
        scheduleService.loadOwn(householdId, liabilityId)
        scheduleService.ownPart(liabilityId, partId)
        val revision = AmortizationRateRevision(
            partId = partId,
            effectiveDate = body.effectiveDate,
            annualRate = body.annualRate,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        revisions.save(revision)
        return ResponseEntity.status(201).body(RevisionDto(revision.id, revision.effectiveDate, revision.annualRate))
    }

    @DeleteMapping("/parts/{partId}/revisions/{revisionId}")
    @Transactional
    fun deleteRevision(
        @PathVariable householdId: UUID,
        @PathVariable liabilityId: UUID,
        @PathVariable partId: UUID,
        @PathVariable revisionId: UUID,
    ): ResponseEntity<Void> {
        val by = currentUser.requireUser()
        scheduleService.loadOwn(householdId, liabilityId)
        scheduleService.ownPart(liabilityId, partId)
        val revision = revisions.findById(revisionId).orElseThrow { AppException.notFound("AMORTIZATION_REVISION_NOT_FOUND") }
        if (revision.partId != partId) throw AppException.notFound("AMORTIZATION_REVISION_NOT_FOUND")
        revision.deletedAt = Instant.now()
        revision.updatedByUserId = by.id
        return ResponseEntity.noContent().build()
    }

    // ---- Prepayments ----

    @PostMapping("/parts/{partId}/simulate")
    fun simulate(
        @PathVariable householdId: UUID,
        @PathVariable liabilityId: UUID,
        @PathVariable partId: UUID,
        @Valid @RequestBody body: SimulationRequest,
    ): SimulationResultDto = scheduleService.simulate(householdId, liabilityId, partId, body)

    @PostMapping("/parts/{partId}/prepayments")
    @Transactional
    fun recordPrepayment(
        @PathVariable householdId: UUID,
        @PathVariable liabilityId: UUID,
        @PathVariable partId: UUID,
        @Valid @RequestBody body: PrepaymentRequest,
    ): ResponseEntity<PrepaymentDto> {
        val by = currentUser.requireUser()
        val liability = scheduleService.loadOwn(householdId, liabilityId)
        scheduleService.ownPart(liabilityId, partId)
        if (body.amount <= BigDecimal.ZERO) throw AppException.badRequest("AMORTIZATION_PREPAYMENT_INVALID")
        val prepayment = AmortizationPrepayment(
            partId = partId,
            prepaymentDate = body.prepaymentDate,
            amount = Money.normalize(body.amount),
            mode = body.mode,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        prepayments.save(prepayment)
        // New balance after the operation → emit the existing prepayment notification.
        val newBalance = scheduleService.balanceAt(liabilityId, LocalDate.now()) ?: Money.normalize(BigDecimal.ZERO)
        notifications.amortizationPrepayment(
            householdId, liability.name, prepayment.amount, newBalance, prepayment.prepaymentDate, NotifyActor.Human(by.email),
        )
        return ResponseEntity.status(201).body(
            PrepaymentDto(prepayment.id, prepayment.partId, prepayment.prepaymentDate, prepayment.amount, prepayment.mode),
        )
    }

    @DeleteMapping("/parts/{partId}/prepayments/{prepaymentId}")
    @Transactional
    fun deletePrepayment(
        @PathVariable householdId: UUID,
        @PathVariable liabilityId: UUID,
        @PathVariable partId: UUID,
        @PathVariable prepaymentId: UUID,
    ): ResponseEntity<Void> {
        val by = currentUser.requireUser()
        scheduleService.loadOwn(householdId, liabilityId)
        scheduleService.ownPart(liabilityId, partId)
        val prepayment = prepayments.findById(prepaymentId).orElseThrow { AppException.notFound("AMORTIZATION_PREPAYMENT_NOT_FOUND") }
        if (prepayment.partId != partId) throw AppException.notFound("AMORTIZATION_PREPAYMENT_NOT_FOUND")
        prepayment.deletedAt = Instant.now()
        prepayment.updatedByUserId = by.id
        return ResponseEntity.noContent().build()
    }

    private fun validatePart(body: PartRequest) {
        if (body.originalPrincipal <= BigDecimal.ZERO) throw AppException.badRequest("AMORTIZATION_PRINCIPAL_INVALID")
        if (body.annualRate < BigDecimal.ZERO) throw AppException.badRequest("AMORTIZATION_RATE_INVALID")
        if (body.termMonths != null && body.termMonths <= 0) throw AppException.badRequest("AMORTIZATION_TERM_INVALID")
        if (body.endDate != null && !body.endDate.isAfter(body.startDate)) throw AppException.badRequest("AMORTIZATION_END_DATE_INVALID")
        val hasTerm = body.termMonths != null || body.endDate != null
        when (body.method) {
            AmortizationMethod.french ->
                if (!hasTerm && (body.instalment == null || body.instalment <= BigDecimal.ZERO)) {
                    throw AppException.badRequest("AMORTIZATION_TERM_OR_INSTALMENT_REQUIRED")
                }
            AmortizationMethod.german, AmortizationMethod.zero, AmortizationMethod.interest_only ->
                if (!hasTerm) throw AppException.badRequest("AMORTIZATION_TERM_REQUIRED")
        }
    }

    /** The stored term: an explicit term wins; otherwise an end date is converted to whole months from the start. */
    private fun effectiveTerm(body: PartRequest): Int? {
        body.termMonths?.let { return it }
        val end = body.endDate ?: return null
        return ChronoUnit.MONTHS.between(YearMonth.from(body.startDate), YearMonth.from(end)).toInt()
    }

    private fun AmortizationPart.toDto() =
        PartDto(id, label, method, originalPrincipal, annualRate, termMonths, instalment, startDate)
}
