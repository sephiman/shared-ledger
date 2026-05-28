package com.sephilabs.sharedledger.recurring

import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.notification.NotifyActor
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.observability.AppMetrics
import com.sephilabs.sharedledger.transaction.Transaction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class RecurringService(
    private val repository: RecurringTemplateRepository,
    private val categoryService: CategoryService,
    private val transactions: TransactionRepository,
    private val metrics: AppMetrics,
    private val notifications: NotificationPublisher,
) {

    @Transactional
    fun create(householdId: UUID, request: RecurringTemplateRequest, by: User): RecurringTemplate {
        validate(request)
        categoryService.requireForDirection(householdId, request.categoryCode, request.direction.name)
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
        categoryService.requireForDirection(householdId, request.categoryCode, request.direction.name)
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

    @Transactional(readOnly = true)
    fun exportCsv(householdId: UUID): String {
        val all = repository.findAllByHouseholdId(householdId)
            .sortedWith(compareBy({ it.startDate }, { it.id.toString() }))
        val sb = StringBuilder()
        sb.append(
            Csv.row(
                "direction", "category_code", "amount", "description", "cadence",
                "day_of_week", "day_of_month", "month_of_year", "day_of_month_yearly",
                "start_date", "end_date", "active",
            ),
        )
        for (t in all) {
            sb.append(
                Csv.row(
                    t.direction.name,
                    t.categoryCode,
                    Csv.decimal(t.amount),
                    t.description,
                    t.cadence.name,
                    t.dayOfWeek?.toString().orEmpty(),
                    t.dayOfMonth?.toString().orEmpty(),
                    t.monthOfYear?.toString().orEmpty(),
                    t.dayOfMonthYearly?.toString().orEmpty(),
                    t.startDate,
                    t.endDate?.toString().orEmpty(),
                    if (t.active) "true" else "false",
                ),
            )
        }
        return sb.toString()
    }

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
        // Null watermark falls back to the entity's last edit, not template.startDate,
        // so a backdated startDate never triggers retroactive back-fill.
        val from = template.lastMaterializedThrough?.plusDays(1)
            ?: template.updatedAt.atZone(ZoneId.systemDefault()).toLocalDate()

        // Cadence catch-up over (lastMaterializedThrough, cadenceCap]; plus today (force-fire).
        val cadenceDates =
            if (from.isAfter(cadenceCap)) emptyList()
            else RecurringDateMath.occurrencesInRange(template, from, cadenceCap)
        val datesToFire = (cadenceDates + today).toSortedSet()

        val category = categoryService.find(template.householdId, template.categoryCode)
            ?: throw AppException.notFound("CATEGORY_NOT_FOUND")

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
            metrics.transactionCreated(template.direction.name, category.group ?: "ungrouped")
            created++
        }

        // Marker reflects "materialized through" regardless of path (manual or scheduler).
        template.lastMaterializedThrough = today
        template.updatedByUserId = by.id
        notifications.recurringTransactions(template, created, NotifyActor.Human(by.email))
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
