package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.common.PageResponse
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.notification.NotifyActor
import com.sephilabs.sharedledger.observability.AppMetrics
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import com.sephilabs.sharedledger.transaction.TransactionRequest
import com.sephilabs.sharedledger.transaction.TransactionService
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The review inbox. Confirming generates a real transaction (reusing
 * [TransactionService.createInternal]) and links it back so the item is never re-ingested. Single
 * confirm notifies normally; batch confirm creates silently and emits one aggregated notification.
 * Reject discards (creates nothing) and the item stays hidden on the next sync.
 */
@Service
class PendingMovementService(
    private val pending: PendingMovementRepository,
    private val connections: BankConnectionRepository,
    private val accounts: BankConnectionAccountRepository,
    private val transactionService: TransactionService,
    private val transactions: TransactionRepository,
    private val categorization: CategorizationService,
    private val notifications: NotificationPublisher,
    private val metrics: AppMetrics,
) {

    @Transactional(readOnly = true)
    fun pendingCount(householdId: UUID): Long =
        pending.countByHouseholdIdAndStatus(householdId, MovementStatus.pending)

    @Transactional(readOnly = true)
    fun list(
        householdId: UUID,
        status: MovementStatus,
        connectionId: UUID?,
        page: Int,
        size: Int,
    ): PageResponse<PendingMovementDto> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 200))
        val result = pending.search(householdId, status, connectionId, pageable)
        val items = result.content
        if (items.isEmpty()) return PageResponse.of(emptyList(), pageable.pageNumber, pageable.pageSize, result.totalElements)

        val connById = connections.findAllByHouseholdIdOrderByCreatedAtAsc(householdId).associateBy { it.id }
        val acctById = items.map { it.accountId }.toSet()
            .mapNotNull { accounts.findById(it).orElse(null) }.associateBy { it.id }
        // Duplicate detection only matters for still-pending items; load the whole comparison
        // window once (not per row) and match in memory.
        val dupIndex = if (status == MovementStatus.pending) buildDuplicateIndex(householdId, items) else emptyMap()

        val dtos = items.map {
            val dup = status == MovementStatus.pending && matchesDuplicate(dupIndex, it)
            it.toDto(connById[it.connectionId], acctById[it.accountId], dup)
        }
        return PageResponse.of(dtos, pageable.pageNumber, pageable.pageSize, result.totalElements)
    }

    @Transactional
    fun confirm(householdId: UUID, id: UUID, request: ConfirmMovementRequest, by: User): PendingMovementDto {
        val movement = requirePending(householdId, id)
        val direction = request.direction ?: movement.direction
        val categoryCode = request.categoryCode ?: movement.suggestedCategoryCode
            ?: throw AppException.badRequest("BANK_CATEGORY_REQUIRED")
        createTransaction(householdId, movement, categoryCode, direction, request.note, by, notify = true)
        markConfirmed(movement, categoryCode, by)
        if (request.saveRule) categorization.learn(householdId, movement.counterparty, categoryCode, direction, by)
        metrics.bankMovementsIngested(1)
        return movement.toDto(connections.findById(movement.connectionId).orElse(null), account(movement), false)
    }

    @Transactional
    fun confirmBatch(householdId: UUID, items: List<ConfirmBatchItem>, by: User): BatchResultDto {
        val byId = items.associateBy { it.id }
        val movements = pending.findAllByIdInAndHouseholdId(byId.keys, householdId)
            .filter { it.status == MovementStatus.pending }
        val skipped = mutableListOf<UUID>()
        var confirmed = 0
        for (movement in movements) {
            val item = byId[movement.id]
            val direction = item?.direction ?: movement.direction
            // Prefer the per-row category the user chose; fall back to a rule's suggestion.
            val categoryCode = item?.categoryCode ?: movement.suggestedCategoryCode
            if (categoryCode.isNullOrBlank()) {
                skipped += movement.id
                continue
            }
            // Silent create; a single aggregated notification is published after the loop.
            createTransaction(householdId, movement, categoryCode, direction, null, by, notify = false)
            markConfirmed(movement, categoryCode, by)
            categorization.learn(householdId, movement.counterparty, categoryCode, direction, by)
            confirmed++
        }
        notifications.bankMovementsConfirmed(householdId, confirmed, NotifyActor.Human(by.email))
        metrics.bankMovementsIngested(confirmed)
        return BatchResultDto(confirmed = confirmed, skipped = skipped)
    }

    @Transactional
    fun reject(householdId: UUID, id: UUID, by: User): PendingMovementDto {
        val movement = requirePending(householdId, id)
        movement.status = MovementStatus.rejected
        movement.processedAt = Instant.now()
        movement.processedByUserId = by.id
        return movement.toDto(connections.findById(movement.connectionId).orElse(null), account(movement), false)
    }

    @Transactional
    fun rejectBatch(householdId: UUID, ids: List<UUID>, by: User): BatchResultDto {
        val movements = pending.findAllByIdInAndHouseholdId(ids, householdId).filter { it.status == MovementStatus.pending }
        movements.forEach {
            it.status = MovementStatus.rejected
            it.processedAt = Instant.now()
            it.processedByUserId = by.id
        }
        return BatchResultDto(rejected = movements.size)
    }

    @Transactional
    fun edit(householdId: UUID, id: UUID, request: EditMovementRequest): PendingMovementDto {
        val movement = requirePending(householdId, id)
        request.suggestedCategoryCode?.let { movement.suggestedCategoryCode = it }
        request.direction?.let { movement.direction = it }
        request.description?.let { movement.description = it }
        return movement.toDto(connections.findById(movement.connectionId).orElse(null), account(movement), isPossibleDuplicate(movement))
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun requirePending(householdId: UUID, id: UUID): PendingMovement {
        val movement = pending.findByIdAndHouseholdId(id, householdId)
            ?: throw AppException.notFound("PENDING_MOVEMENT_NOT_FOUND")
        if (movement.status != MovementStatus.pending) throw AppException.conflict("PENDING_MOVEMENT_ALREADY_PROCESSED")
        return movement
    }

    private fun createTransaction(
        householdId: UUID,
        movement: PendingMovement,
        categoryCode: String,
        direction: Direction,
        note: String?,
        by: User,
        notify: Boolean,
    ) {
        val request = TransactionRequest(
            occurrenceDate = movement.bookingDate,
            direction = direction,
            categoryCode = categoryCode,
            amount = movement.amount,
            description = (note ?: movementDescription(movement))?.take(500),
        )
        val tx = transactionService.createInternal(householdId, request, by, notify)
        movement.createdTransactionId = tx.id
    }

    private fun markConfirmed(movement: PendingMovement, categoryCode: String, by: User) {
        movement.status = MovementStatus.confirmed
        movement.suggestedCategoryCode = categoryCode
        movement.processedAt = Instant.now()
        movement.processedByUserId = by.id
    }

    private fun account(movement: PendingMovement): BankConnectionAccount? =
        accounts.findById(movement.accountId).orElse(null)

    /** A readable description for the generated transaction from the raw bank fields. */
    private fun movementDescription(m: PendingMovement): String? {
        val parts = listOfNotNull(m.counterparty?.trim()?.ifBlank { null }, m.description?.trim()?.ifBlank { null })
        return parts.distinct().joinToString(" – ").ifBlank { null }
    }

    /**
     * Possible-duplicate against manual transactions: same direction + amount within a few days
     * (closed decision #3 — warn only, never auto-discard). Single-movement variant for the
     * confirm/edit paths; the list path uses [buildDuplicateIndex] to avoid one query per row.
     */
    private fun isPossibleDuplicate(m: PendingMovement): Boolean {
        val window = transactions.findByHouseholdIdAndOccurrenceDateBetween(
            m.householdId, m.bookingDate.minusDays(DUPLICATE_WINDOW_DAYS), m.bookingDate.plusDays(DUPLICATE_WINDOW_DAYS),
        )
        return window.any { it.direction == m.direction && it.amount.compareTo(m.amount) == 0 }
    }

    /** direction+amount -> the transaction dates in the comparison window, loaded in one query. */
    private fun buildDuplicateIndex(householdId: UUID, items: List<PendingMovement>): Map<String, List<LocalDate>> {
        val from = items.minOf { it.bookingDate }.minusDays(DUPLICATE_WINDOW_DAYS)
        val to = items.maxOf { it.bookingDate }.plusDays(DUPLICATE_WINDOW_DAYS)
        return transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, from, to)
            .groupBy { dupKey(it.direction, it.amount) }
            .mapValues { entry -> entry.value.map { it.occurrenceDate } }
    }

    private fun matchesDuplicate(index: Map<String, List<LocalDate>>, m: PendingMovement): Boolean {
        val dates = index[dupKey(m.direction, m.amount)] ?: return false
        val lo = m.bookingDate.minusDays(DUPLICATE_WINDOW_DAYS)
        val hi = m.bookingDate.plusDays(DUPLICATE_WINDOW_DAYS)
        return dates.any { !it.isBefore(lo) && !it.isAfter(hi) }
    }

    private fun dupKey(direction: Direction, amount: BigDecimal): String =
        "$direction|${amount.stripTrailingZeros().toPlainString()}"

    private fun PendingMovement.toDto(
        connection: BankConnection?,
        account: BankConnectionAccount?,
        possibleDuplicate: Boolean,
    ): PendingMovementDto = PendingMovementDto(
        id = id,
        connectionId = connectionId,
        connectionLabel = connection?.label,
        aspspName = connection?.aspspName ?: "",
        accountId = accountId,
        accountName = account?.name ?: account?.ibanMasked,
        bookingDate = bookingDate,
        valueDate = valueDate,
        direction = direction,
        amount = amount,
        originalAmount = originalAmount,
        originalCurrency = originalCurrency,
        counterparty = counterparty,
        description = description,
        reference = reference,
        status = status,
        suggestedCategoryCode = suggestedCategoryCode,
        createdTransactionId = createdTransactionId,
        possibleDuplicate = possibleDuplicate,
    )

    private companion object {
        const val DUPLICATE_WINDOW_DAYS = 3L
    }
}
