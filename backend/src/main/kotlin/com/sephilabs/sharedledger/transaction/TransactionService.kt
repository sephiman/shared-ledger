package com.sephilabs.sharedledger.transaction

import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.PageResponse
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.notification.NotifyAction
import com.sephilabs.sharedledger.notification.NotifyActor
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.observability.AppMetrics
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class TransactionService(
    private val repository: TransactionRepository,
    private val categoryService: CategoryService,
    private val metrics: AppMetrics,
    private val notifications: NotificationPublisher,
) {

    @Transactional
    fun create(householdId: UUID, request: TransactionRequest, by: User): Transaction =
        createInternal(householdId, request, by, notify = true)

    /** Shared creation logic (category validation, save, metrics) with the notification made optional. The bank
     *  review inbox reuses it: single confirm notifies, batch confirm suppresses per-item and emits one
     *  summary. Public endpoints always go through [create]. */
    @Transactional
    fun createInternal(householdId: UUID, request: TransactionRequest, by: User, notify: Boolean): Transaction {
        val category = categoryService.requireForDirection(householdId, request.categoryCode, request.direction.name)
        val tx = Transaction(
            householdId = householdId,
            occurrenceDate = request.occurrenceDate,
            direction = request.direction,
            categoryCode = request.categoryCode,
            amount = Money.normalize(request.amount),
            description = request.description,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        repository.save(tx)
        metrics.transactionCreated(request.direction.name, category.group ?: "ungrouped")
        if (notify) notifications.transaction(tx, NotifyAction.CREATE, NotifyActor.Human(by.email))
        return tx
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, request: TransactionRequest, by: User): Transaction {
        val tx = loadOwn(householdId, id)
        categoryService.requireForDirection(householdId, request.categoryCode, request.direction.name)
        tx.occurrenceDate = request.occurrenceDate
        tx.direction = request.direction
        tx.categoryCode = request.categoryCode
        tx.amount = Money.normalize(request.amount)
        tx.description = request.description
        tx.updatedByUserId = by.id
        notifications.transaction(tx, NotifyAction.UPDATE, NotifyActor.Human(by.email))
        return tx
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID, by: User) {
        val tx = loadOwn(householdId, id)
        tx.deletedAt = Instant.now()
        tx.updatedByUserId = by.id
        notifications.transaction(tx, NotifyAction.DELETE, NotifyActor.Human(by.email))
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): Transaction = loadOwn(householdId, id)

    @Transactional(readOnly = true)
    fun search(criteria: TransactionSearchCriteria): PageResponse<TransactionDto> {
        val sort = when (criteria.sort) {
            "amount_desc" -> Sort.by(Sort.Order.desc("amount"), Sort.Order.desc("occurrenceDate"))
            "amount_asc"  -> Sort.by(Sort.Order.asc("amount"), Sort.Order.desc("occurrenceDate"))
            "date_asc"    -> Sort.by(Sort.Order.asc("occurrenceDate"), Sort.Order.asc("createdAt"))
            else          -> Sort.by(Sort.Order.desc("occurrenceDate"), Sort.Order.desc("createdAt"))
        }
        val pageable = PageRequest.of(criteria.page, criteria.size, sort)
        val page = repository.findAll(TransactionSpecs.fromCriteria(criteria), pageable)
        return PageResponse.of(page.content.map { it.toDto() }, criteria.page, criteria.size, page.totalElements)
    }

    @Transactional(readOnly = true)
    fun exportCsv(criteria: TransactionSearchCriteria): String {
        val sort = Sort.by(Sort.Order.asc("occurrenceDate"), Sort.Order.asc("createdAt"))
        val items = repository.findAll(TransactionSpecs.fromCriteria(criteria), sort)
        val sb = StringBuilder()
        sb.append(Csv.row("date", "direction", "category_code", "amount", "description", "created_at", "updated_at"))
        for (t in items) {
            sb.append(Csv.row(
                t.occurrenceDate,
                t.direction,
                t.categoryCode,
                Csv.decimal(t.amount),
                t.description,
                t.createdAt,
                t.updatedAt,
            ))
        }
        return sb.toString()
    }

    @Transactional(readOnly = true)
    fun quickChips(householdId: UUID, user: User, limit: Int = 8): List<QuickChip> {
        val from = LocalDate.now().minus(30, ChronoUnit.DAYS)
        return repository.topCategoriesForUser(householdId, user.id, from)
            .take(limit)
            .map { row -> QuickChip(row[0] as String, (row[1] as Number).toLong()) }
    }

    private fun loadOwn(householdId: UUID, id: UUID): Transaction {
        val tx = repository.findById(id).orElseThrow { AppException.notFound("TRANSACTION_NOT_FOUND") }
        if (tx.householdId != householdId) throw AppException.notFound("TRANSACTION_NOT_FOUND")
        return tx
    }
}
