package com.sephilabs.sharedledger.transaction

import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.PageResponse
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.i18n.Messages
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.notification.NotifyAction
import com.sephilabs.sharedledger.notification.NotifyActor
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.observability.AppMetrics
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Order
import jakarta.persistence.criteria.Root
import org.slf4j.LoggerFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

@Service
class TransactionService(
    private val repository: TransactionRepository,
    private val categoryService: CategoryService,
    private val metrics: AppMetrics,
    private val notifications: NotificationPublisher,
    private val em: EntityManager,
    private val messages: Messages,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(householdId: UUID, request: TransactionRequest, by: User): Transaction {
        val tx = createInternal(householdId, request, by, notify = true)
        log.info(
            "transaction_created tx={} direction={} category={} refund={}",
            tx.id, tx.direction, tx.categoryCode, tx.isRefund,
        )
        return tx
    }

    /** Shared creation logic (category validation, save, metrics) with the notification made optional. The bank
     *  review inbox reuses it: single confirm notifies, batch confirm suppresses per-item and emits one
     *  summary. Public endpoints always go through [create]. */
    @Transactional
    fun createInternal(householdId: UUID, request: TransactionRequest, by: User, notify: Boolean): Transaction {
        val category = categoryService.requireForDirection(householdId, request.categoryCode, request.direction.name)
        validateAmountAndRefund(householdId, request)
        val tx = Transaction(
            householdId = householdId,
            occurrenceDate = request.occurrenceDate,
            direction = request.direction,
            categoryCode = request.categoryCode,
            amount = Money.normalize(request.amount),
            description = request.description,
            isRefund = request.isRefund,
            refundOfTransactionId = request.refundOfTransactionId,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        repository.save(tx)
        metrics.transactionCreated(request.direction.name, category.group ?: "ungrouped")
        if (notify) notifications.transaction(tx, NotifyAction.CREATE, NotifyActor.Human(by.email))
        return tx
    }

    /** Also the supported way to convert a historical row (an `income.reimbursements` entry, say) into a
     *  refund: it is an ordinary edit, so the id, any bank link and the audit trail survive. */
    @Transactional
    fun update(householdId: UUID, id: UUID, request: TransactionRequest, by: User): Transaction {
        val tx = loadOwn(householdId, id)
        categoryService.requireForDirection(householdId, request.categoryCode, request.direction.name)
        validateAmountAndRefund(householdId, request, selfId = id)
        // Turning a refunded expense into a refund would make its own refunds refunds-of-a-refund.
        if (request.isRefund && !tx.isRefund && repository.existsByRefundOfTransactionId(id)) {
            throw AppException.conflict("TX_REFUND_TARGET_HAS_REFUNDS")
        }
        tx.occurrenceDate = request.occurrenceDate
        tx.direction = request.direction
        tx.categoryCode = request.categoryCode
        tx.amount = Money.normalize(request.amount)
        tx.description = request.description
        tx.isRefund = request.isRefund
        tx.refundOfTransactionId = request.refundOfTransactionId
        tx.updatedByUserId = by.id
        notifications.transaction(tx, NotifyAction.UPDATE, NotifyActor.Human(by.email))
        log.info(
            "transaction_updated tx={} direction={} category={} refund={}",
            tx.id, tx.direction, tx.categoryCode, tx.isRefund,
        )
        return tx
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID, by: User) {
        val tx = loadOwn(householdId, id)
        tx.deletedAt = Instant.now()
        tx.updatedByUserId = by.id
        notifications.transaction(tx, NotifyAction.DELETE, NotifyActor.Human(by.email))
        log.info("transaction_deleted tx={}", tx.id)
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): Transaction = loadOwn(householdId, id)

    @Transactional(readOnly = true)
    fun search(
        criteria: TransactionSearchCriteria,
        locale: Locale = LocaleContextHolder.getLocale(),
    ): PageResponse<TransactionDto> {
        val cb = em.criteriaBuilder
        val spec = TransactionSpecs.fromCriteria(criteria)
        val query = cb.createQuery(Transaction::class.java)
        val root = query.from(Transaction::class.java)
        query.where(spec.toPredicate(root, query, cb))
        query.orderBy(buildOrders(cb, root, criteria, locale))

        val items = em.createQuery(query)
            .setFirstResult(criteria.page.coerceAtLeast(0) * criteria.size)
            .setMaxResults(criteria.size)
            .resultList
        val total = countMatching(cb, spec)
        return PageResponse.of(enrich(items), criteria.page, criteria.size, total)
    }

    private fun countMatching(cb: CriteriaBuilder, spec: Specification<Transaction>): Long {
        val countQuery = cb.createQuery(Long::class.javaObjectType)
        val countRoot = countQuery.from(Transaction::class.java)
        countQuery.select(cb.count(countRoot))
        countQuery.where(spec.toPredicate(countRoot, countQuery, cb))
        return em.createQuery(countQuery).singleResult
    }

    private fun buildOrders(
        cb: CriteriaBuilder,
        root: Root<Transaction>,
        criteria: TransactionSearchCriteria,
        locale: Locale,
    ): List<Order> {
        val date = root.get<LocalDate>("occurrenceDate")
        val created = root.get<Instant>("createdAt")
        val id = root.get<UUID>("id")
        return when (criteria.sort) {
            "date_asc" -> listOf(cb.asc(date), cb.asc(created), cb.asc(id))
            "amount_desc", "amount_asc" -> {
                // Sort by signed display amount (income positive, expenses/refunds negated) so the order matches what is read.
                val amount = root.get<BigDecimal>("amount")
                val signed = cb.selectCase<BigDecimal>()
                    .`when`(cb.equal(root.get<Direction>("direction"), Direction.income), amount)
                    .otherwise(cb.neg(amount))
                val primary = if (criteria.sort == "amount_asc") cb.asc(signed) else cb.desc(signed)
                listOf(primary, cb.desc(date), cb.desc(created), cb.desc(id))
            }
            "description_asc", "description_desc" -> {
                val trimmed = cb.trim(cb.coalesce(root.get<String>("description"), ""))
                val isBlank = cb.selectCase<Int>().`when`(cb.equal(trimmed, ""), 1).otherwise(0)
                val lower = cb.lower(trimmed)
                val textOrder = if (criteria.sort == "description_desc") cb.desc(lower) else cb.asc(lower)
                listOf(cb.asc(isBlank), textOrder, cb.desc(date), cb.desc(created), cb.desc(id))
            }
            "category_asc", "category_desc" -> categoryOrders(cb, root, criteria, locale)
            else -> listOf(cb.desc(date), cb.desc(created), cb.desc(id))
        }
    }

    private fun categoryOrders(
        cb: CriteriaBuilder,
        root: Root<Transaction>,
        criteria: TransactionSearchCriteria,
        locale: Locale,
    ): List<Order> {
        val desc = criteria.sort == "category_desc"
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        val orderedCodes = categoryService.listForHousehold(criteria.householdId)
            .sortedWith(compareBy(collator) { cat ->
                if (cat.custom) cat.name else messages.resolve(cat.name, fallback = cat.code, locale = locale)
            })
            .map { it.code }
        val codePath = root.get<String>("categoryCode")
        val primary = if (orderedCodes.isEmpty()) {
            if (desc) cb.desc(codePath) else cb.asc(codePath)
        } else {
            val ranked = if (desc) orderedCodes.asReversed() else orderedCodes
            val rankCase = cb.selectCase<Int>()
            ranked.forEachIndexed { idx, code -> rankCase.`when`(cb.equal(codePath, code), idx) }
            cb.asc(rankCase.otherwise(ranked.size))
        }
        val codeTie = if (desc) cb.desc(codePath) else cb.asc(codePath)
        return listOf(
            primary,
            codeTie,
            cb.desc(root.get<LocalDate>("occurrenceDate")),
            cb.desc(root.get<Instant>("createdAt")),
            cb.desc(root.get<UUID>("id")),
        )
    }

    @Transactional(readOnly = true)
    fun exportCsv(criteria: TransactionSearchCriteria): String {
        val sort = Sort.by(Sort.Order.asc("occurrenceDate"), Sort.Order.asc("createdAt"))
        val items = repository.findAll(TransactionSpecs.fromCriteria(criteria), sort)
        // A refund names its original by natural key: ids aren't exported, so that is the only reference
        // that can survive a round trip. An original outside this export (or since deleted) leaves it blank
        // and the refund re-imports unlinked.
        val originalIds = items.mapNotNull { it.refundOfTransactionId }.distinct()
        val originalKeys = if (originalIds.isEmpty()) {
            emptyMap()
        } else {
            repository.findAllById(originalIds).associateBy({ it.id }, { TransactionKeys.dedupKey(it) })
        }
        val sb = StringBuilder()
        sb.append(Csv.row(
            "date", "direction", "category_code", "amount", "description", "created_at", "updated_at",
            "is_refund", "refund_of_key",
        ))
        for (t in items) {
            sb.append(Csv.row(
                t.occurrenceDate,
                t.direction,
                t.categoryCode,
                Csv.decimal(t.amount),
                t.description,
                t.createdAt,
                t.updatedAt,
                t.isRefund.toString(),
                t.refundOfTransactionId?.let { originalKeys[it] } ?: "",
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

    /** The sign rule the database also enforces, plus the shape of a refund link. Lives here rather than on
     *  the DTO because it is conditional: ordinary transactions are positive, refunds negative. Zero is
     *  never valid. Every writer that goes through create/update is covered; the CSV import validates the
     *  same rules while parsing, and the recurring materialisers only ever write positive non-refunds. */
    private fun validateAmountAndRefund(householdId: UUID, request: TransactionRequest, selfId: UUID? = null) {
        if (!request.isRefund) {
            if (request.amount.signum() <= 0) throw AppException.badRequest("TX_AMOUNT_INVALID")
            if (request.refundOfTransactionId != null) throw AppException.badRequest("TX_REFUND_OF_WITHOUT_REFUND")
            return
        }
        // Refunding income is out of scope: money going back out is an ordinary expense.
        if (request.direction != Direction.expense) throw AppException.badRequest("TX_REFUND_MUST_BE_EXPENSE")
        if (request.amount.signum() >= 0) throw AppException.badRequest("TX_REFUND_AMOUNT_INVALID")

        val originalId = request.refundOfTransactionId ?: return
        if (originalId == selfId) throw AppException.badRequest("TX_REFUND_OF_NOT_FOUND")
        // Soft-deleted originals are invisible here, so a since-deleted target lands on this branch.
        val original = repository.findById(originalId).orElse(null)?.takeIf { it.householdId == householdId }
            ?: throw AppException.badRequest("TX_REFUND_OF_NOT_FOUND")
        if (original.isRefund) throw AppException.badRequest("TX_REFUND_OF_IS_REFUND")
        if (original.direction != Direction.expense) throw AppException.badRequest("TX_REFUND_OF_NOT_EXPENSE")
    }

    /** Resolves each page's refund links in two queries: what came back per original, and the originals the
     *  refund rows point at. Per-row lookups would be N+1 over a 200-row page. */
    private fun enrich(items: List<Transaction>): List<TransactionDto> {
        if (items.isEmpty()) return emptyList()
        val totals = repository.refundTotalsFor(items.map { it.id }).associateBy { it.originalId }
        val originalIds = items.mapNotNull { it.refundOfTransactionId }.distinct()
        val originals = if (originalIds.isEmpty()) {
            emptyMap()
        } else {
            repository.findAllById(originalIds).associateBy({ it.id }, { it.toRefundOfSummary() })
        }
        return items.map { tx ->
            val total = totals[tx.id]
            tx.toDto(
                refundOf = tx.refundOfTransactionId?.let { originals[it] },
                refundedTotal = total?.total,
                refundCount = total?.cnt?.toInt(),
            )
        }
    }
}
