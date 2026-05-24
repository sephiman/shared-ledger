package com.sharedledger.recurring

import com.sharedledger.catalog.CategoryRepository
import com.sharedledger.common.Money
import com.sharedledger.common.errors.AppException
import com.sharedledger.identity.user.User
import com.sharedledger.observability.AppMetrics
import com.sharedledger.transaction.Transaction
import com.sharedledger.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class RecurringService(
    private val repository: RecurringTemplateRepository,
    private val categories: CategoryRepository,
    private val transactions: TransactionRepository,
    private val metrics: AppMetrics,
) {

    @Transactional
    fun create(householdId: UUID, request: RecurringTemplateRequest, by: User): RecurringTemplate {
        validate(request)
        val category = categories.findById(request.categoryCode).orElseThrow { AppException.notFound("CATEGORY_NOT_FOUND") }
        if (category.kind != request.direction.name) throw AppException.badRequest("CATEGORY_DIRECTION_MISMATCH")
        val template = RecurringTemplate(
            householdId = householdId,
            direction = request.direction,
            categoryCode = request.categoryCode,
            amount = Money.normalize(request.amount),
            description = request.description,
            cadence = request.cadence,
            dayOfMonth = request.dayOfMonth,
            dayOfWeek = request.dayOfWeek,
            monthOfYear = request.monthOfYear,
            dayOfMonthYearly = request.dayOfMonthYearly,
            startDate = request.startDate,
            endDate = request.endDate,
            active = request.active,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        return repository.save(template)
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, request: RecurringTemplateRequest, by: User): RecurringTemplate {
        validate(request)
        val template = loadOwn(householdId, id)
        val category = categories.findById(request.categoryCode).orElseThrow { AppException.notFound("CATEGORY_NOT_FOUND") }
        if (category.kind != request.direction.name) throw AppException.badRequest("CATEGORY_DIRECTION_MISMATCH")
        template.direction = request.direction
        template.categoryCode = request.categoryCode
        template.amount = Money.normalize(request.amount)
        template.description = request.description
        template.cadence = request.cadence
        template.dayOfMonth = request.dayOfMonth
        template.dayOfWeek = request.dayOfWeek
        template.monthOfYear = request.monthOfYear
        template.dayOfMonthYearly = request.dayOfMonthYearly
        template.startDate = request.startDate
        template.endDate = request.endDate
        template.active = request.active
        template.updatedByUserId = by.id
        return template
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID, by: User) {
        val template = loadOwn(householdId, id)
        template.deletedAt = Instant.now()
        template.updatedByUserId = by.id
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<RecurringTemplateDto> {
        return repository.findAllByHouseholdId(householdId).map {
            val next = if (it.active) RecurringDateMath.nextOccurrenceAfter(it, LocalDate.now()) else null
            it.toDto(next)
        }
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): RecurringTemplate = loadOwn(householdId, id)

    /**
     * Materialize this template up to today: catches up any pending cadence occurrences AND
     * force-fires one entry dated today regardless of the cadence rule, AND advances
     * `lastMaterializedThrough = today`.
     *
     * Idempotent per (template, date): the unique partial index on (recurring_template_id, occurrence_date)
     * guarantees no duplicates, so calling this twice on the same day inserts nothing the second time.
     * Returns the number of new transactions created.
     */
    @Transactional
    fun fireNow(householdId: UUID, templateId: UUID, by: User): Int {
        val template = loadOwn(householdId, templateId)
        val today = LocalDate.now()
        val cadenceCap = template.endDate?.let { if (it.isBefore(today)) it else today } ?: today
        val from = template.lastMaterializedThrough?.plusDays(1) ?: template.startDate

        // Cadence catch-up over (lastMaterializedThrough, cadenceCap]; plus today (force-fire).
        val cadenceDates =
            if (from.isAfter(cadenceCap)) emptyList()
            else RecurringDateMath.occurrencesInRange(template, from, cadenceCap)
        val datesToFire = (cadenceDates + today).toSortedSet()

        val category = categories.findById(template.categoryCode)
            .orElseThrow { AppException.notFound("CATEGORY_NOT_FOUND") }

        var created = 0
        for (date in datesToFire) {
            if (transactions.existsByRecurringTemplateIdAndOccurrenceDate(template.id, date)) continue
            val tx = Transaction(
                householdId = template.householdId,
                occurrenceDate = date,
                direction = template.direction,
                categoryCode = template.categoryCode,
                amount = template.amount,
                description = template.description,
                recurringTemplateId = template.id,
                createdByUserId = by.id,
                updatedByUserId = by.id,
            )
            transactions.save(tx)
            metrics.recurringMaterialized(template.id.toString())
            metrics.transactionCreated(template.direction.name, category.groupCode ?: "ungrouped")
            created++
        }

        // Marker reflects "materialized through" regardless of path (manual or scheduler).
        template.lastMaterializedThrough = today
        template.updatedByUserId = by.id
        return created
    }

    private fun validate(request: RecurringTemplateRequest) {
        val ok = when (request.cadence) {
            Cadence.weekly -> request.dayOfWeek != null && request.dayOfMonth == null && request.monthOfYear == null && request.dayOfMonthYearly == null
            Cadence.monthly -> request.dayOfMonth != null && request.dayOfWeek == null && request.monthOfYear == null && request.dayOfMonthYearly == null
            Cadence.yearly -> request.monthOfYear != null && request.dayOfMonthYearly != null && request.dayOfWeek == null && request.dayOfMonth == null
        }
        if (!ok) throw AppException.badRequest("RECURRING_CADENCE_FIELDS_INVALID")
    }

    private fun loadOwn(householdId: UUID, id: UUID): RecurringTemplate {
        val t = repository.findById(id).orElseThrow { AppException.notFound("RECURRING_TEMPLATE_NOT_FOUND") }
        if (t.householdId != householdId) throw AppException.notFound("RECURRING_TEMPLATE_NOT_FOUND")
        return t
    }
}
