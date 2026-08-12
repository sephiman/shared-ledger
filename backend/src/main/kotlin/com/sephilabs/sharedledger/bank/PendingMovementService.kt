package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.PageResponse
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.networth.movement.MovementRequest
import com.sephilabs.sharedledger.networth.movement.MovementService
import com.sephilabs.sharedledger.notification.NotificationPublisher
import com.sephilabs.sharedledger.notification.NotifyActor
import com.sephilabs.sharedledger.observability.AppMetrics
import com.sephilabs.sharedledger.transaction.*
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.abs

/** The review inbox. Confirming generates a real transaction (via [TransactionService.createInternal])
 *  and links it back so the item is never re-ingested; single confirm notifies normally, batch confirm
 *  emits one aggregated notification. Reject discards and creates nothing. Possible-duplicate items also
 *  offer [replace], which reconciles with the existing transaction instead of adding a second one; any item
 *  can be [split] into several transactions, and several items [merge]d into one. */
@Service
class PendingMovementService(
    private val pending: PendingMovementRepository,
    private val links: PendingMovementTransactionRepository,
    private val connections: BankConnectionRepository,
    private val accounts: BankConnectionAccountRepository,
    private val transactionService: TransactionService,
    private val movementService: MovementService,
    private val transactions: TransactionRepository,
    private val categorization: CategorizationService,
    private val notifications: NotificationPublisher,
    private val metrics: AppMetrics,
) {

    @Transactional(readOnly = true)
    fun pendingCount(householdId: UUID): Long =
        pending.countByHouseholdIdAndStatus(householdId, MovementStatus.pending)

    /** Pending count plus its per-connection breakdown (largest inbox first) for the Home tile. */
    @Transactional(readOnly = true)
    fun pendingCounts(householdId: UUID): PendingCountDto {
        val rows = pending.countByHouseholdIdAndStatusGroupedByConnection(householdId, MovementStatus.pending)
        if (rows.isEmpty()) return PendingCountDto(0)
        val connectionsById = connections.findAllByHouseholdIdOrderByCreatedAtAsc(householdId).associateBy { it.id }
        val byConnection = rows
            .map { row ->
                val connection = connectionsById[row.connectionId]
                PendingConnectionCountDto(
                    connectionId = row.connectionId,
                    label = connection?.label ?: connection?.aspspName ?: "?",
                    count = row.count,
                )
            }
            .sortedWith(compareByDescending<PendingConnectionCountDto> { it.count }.thenBy { it.label })
        return PendingCountDto(byConnection.sumOf { it.count }, byConnection)
    }

    @Transactional(readOnly = true)
    fun list(
        householdId: UUID,
        status: MovementStatus,
        connectionId: UUID?,
        search: String?,
        categorisation: PendingCategorisation?,
        duplicatesOnly: Boolean,
        page: Int,
        size: Int,
    ): PageResponse<PendingMovementDto> {
        val pageNum = page.coerceAtLeast(0)
        val pageSize = size.coerceIn(1, 200)
        // Pre-lowercase + wrap for the case-insensitive LIKE; blank search means "no text filter".
        val searchTerm = search?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "%$it%" }
        val categorized: Boolean? = when (categorisation) {
            PendingCategorisation.categorized -> true
            PendingCategorisation.uncategorized -> false
            null -> null
        }

        // "Only possible duplicates" is a computed, cross-table flag (only meaningful for pending) that
        // can't be expressed in SQL. When requested, load the whole filtered set, flag it in memory,
        // then paginate the survivors here so the filter still covers the full dataset.
        if (duplicatesOnly && status == MovementStatus.pending) {
            val all = pending.search(householdId, status, connectionId, searchTerm, categorized, Pageable.unpaged()).content
            if (all.isEmpty()) return PageResponse.of(emptyList(), pageNum, pageSize, 0)
            val dupIndex = buildDuplicateIndex(householdId, all)
            val duplicates = all.filter { matchesDuplicate(dupIndex, it) }
            val pageItems = duplicates.drop(pageNum * pageSize).take(pageSize)
            return PageResponse.of(buildDtos(householdId, pageItems, status, dupIndex), pageNum, pageSize, duplicates.size.toLong())
        }

        val pageable = PageRequest.of(pageNum, pageSize)
        val result = pending.search(householdId, status, connectionId, searchTerm, categorized, pageable)
        val items = result.content
        if (items.isEmpty()) return PageResponse.of(emptyList(), pageNum, pageSize, result.totalElements)
        // Duplicate detection only matters for still-pending items; load the whole comparison
        // window once (not per row) and match in memory.
        val dupIndex = if (status == MovementStatus.pending) buildDuplicateIndex(householdId, items) else emptyMap()
        return PageResponse.of(buildDtos(householdId, items, status, dupIndex), pageNum, pageSize, result.totalElements)
    }

    /** Resolve the connection/account for each row (batched) and map to DTOs, flagging duplicates. */
    private fun buildDtos(
        householdId: UUID,
        items: List<PendingMovement>,
        status: MovementStatus,
        dupIndex: Map<String, List<Transaction>>,
    ): List<PendingMovementDto> {
        if (items.isEmpty()) return emptyList()
        val connById = connections.findAllByHouseholdIdOrderByCreatedAtAsc(householdId).associateBy { it.id }
        val acctById = items.map { it.accountId }.toSet()
            .mapNotNull { accounts.findById(it).orElse(null) }.associateBy { it.id }
        // Only confirmed items carry links, so the pending hot path skips this query.
        val linksById = if (status == MovementStatus.confirmed) {
            links.findAllByPendingMovementIdIn(items.map { it.id })
                .groupBy({ it.pendingMovementId }, { it.transactionId })
        } else {
            emptyMap()
        }
        return items.map {
            val dup = status == MovementStatus.pending && matchesDuplicate(dupIndex, it)
            it.toDto(connById[it.connectionId], acctById[it.accountId], dup, linksById[it.id] ?: emptyList())
        }
    }

    @Transactional
    fun confirm(householdId: UUID, id: UUID, request: ConfirmMovementRequest, by: User): PendingMovementDto {
        val movement = requirePending(householdId, id)
        val direction = request.direction ?: movement.direction
        val categoryCode = request.categoryCode ?: movement.suggestedCategoryCode
            ?: throw AppException.badRequest("BANK_CATEGORY_REQUIRED")
        val tx = createTransaction(householdId, movement, categoryCode, direction, request.note, by, notify = true)
        movement.createdTransactionId = tx.id
        markConfirmed(movement, categoryCode, by)
        if (request.saveRule) categorization.learn(householdId, movement, categoryCode, direction, by)
        metrics.bankMovementsIngested(1)
        return movement.toDto(connections.findById(movement.connectionId).orElse(null), account(movement), false)
    }

    /** Confirm as a net-worth movement: reuses the item's date and amount, delegates to
     *  [MovementService.create], links via [PendingMovement.createdMovementId]. No category and no
     *  rule-learning — those are transaction concepts. Single-row only; batch confirm stays transaction-only. */
    @Transactional
    fun confirmAsMovement(householdId: UUID, id: UUID, request: ConfirmAsMovementRequest, by: User): PendingMovementDto {
        val movement = requirePending(householdId, id)
        createMovement(householdId, movement, request, by)
        markConfirmed(movement, categoryCode = null, by)
        metrics.bankMovementsIngested(1)
        return movement.toDto(connections.findById(movement.connectionId).orElse(null), account(movement), false)
    }

    /** Confirm an income item as a refund instead of income: money coming back for a past purchase becomes
     *  a negative expense on the item's date, netting the category it came from rather than inflating
     *  income. Optionally linked to the original expense. Composes its own request because [createTransaction]
     *  assumes a positive amount and the item's own direction. Deliberately no rule learning — a refund's
     *  counterparty says nothing about what the household usually spends there. */
    @Transactional
    fun confirmAsRefund(householdId: UUID, id: UUID, request: ConfirmAsRefundRequest, by: User): PendingMovementDto {
        val movement = requirePending(householdId, id)
        // Money going back out is an ordinary expense, not a refund; only credits can be refunds.
        if (movement.direction != Direction.income) throw AppException.badRequest("BANK_REFUND_REQUIRES_INCOME")
        val tx = transactionService.createInternal(
            householdId,
            TransactionRequest(
                occurrenceDate = movement.bookingDate,
                direction = Direction.expense,
                categoryCode = request.categoryCode,
                amount = movement.amount.negate(),
                description = (request.note?.takeIf { it.isNotBlank() } ?: movementDescription(movement))?.take(500),
                isRefund = true,
                refundOfTransactionId = request.refundOfTransactionId,
            ),
            by,
            notify = true,
        )
        movement.createdTransactionId = tx.id
        link(movement, tx.id)
        markConfirmed(movement, request.categoryCode, by)
        metrics.bankMovementsIngested(1)
        return movement.toDto(connections.findById(movement.connectionId).orElse(null), account(movement), false)
    }

    /** Replace targets for an item, closest booking date first. Resolved fresh (not off the inbox page) so a
     *  since-deleted or since-linked target surfaces in the dialog; already-linked ones come back flagged. */
    @Transactional(readOnly = true)
    fun duplicateCandidates(householdId: UUID, id: UUID): List<DuplicateCandidateDto> {
        val movement = pending.findByIdAndHouseholdId(id, householdId)
            ?: throw AppException.notFound("PENDING_MOVEMENT_NOT_FOUND")
        val candidates = candidatesFor(buildDuplicateIndex(householdId, listOf(movement)), movement)
        if (candidates.isEmpty()) return emptyList()
        val linked = links.findLinkedTransactionIds(householdId, candidates.map { it.id }).toSet()
        return candidates
            .sortedWith(
                compareBy<Transaction> { abs(ChronoUnit.DAYS.between(movement.bookingDate, it.occurrenceDate)) }
                    .thenBy { it.occurrenceDate },
            )
            .map {
                DuplicateCandidateDto(
                    transactionId = it.id,
                    occurrenceDate = it.occurrenceDate,
                    direction = it.direction,
                    categoryCode = it.categoryCode,
                    amount = it.amount,
                    description = it.description,
                    recurringTemplateId = it.recurringTemplateId,
                    bankLinked = it.id in linked,
                )
            }
    }

    /** Reconcile a possible duplicate: the existing transaction is updated in place (no delete+create) to the
     *  bank's date, amount and description and becomes this item's [PendingMovement.createdTransactionId].
     *  Going through [TransactionService.update] keeps the category validation, `updatedBy` and the UPDATE
     *  notification identical to a manual edit, and leaves the recurring-template link alone. */
    @Transactional
    fun replace(householdId: UUID, id: UUID, request: ReplaceTransactionRequest, by: User): PendingMovementDto {
        val movement = requirePending(householdId, id)
        // Soft-deleted rows are invisible here, so a meanwhile-deleted target lands on this branch. Locked
        // because the link check below is the only thing left guarding the target once V033 relaxed the
        // unique index: two concurrent replaces would otherwise both find it unlinked.
        val tx = transactions.findByIdForUpdate(request.transactionId)?.takeIf { it.householdId == householdId }
            ?: throw AppException.notFound("REPLACE_TARGET_NOT_FOUND")
        // Matched on the *movement's* direction, so re-picking the category can't widen the target set.
        if (!isCandidate(movement, tx)) throw AppException.conflict("REPLACE_TARGET_NOT_A_MATCH")
        // Covers transactions produced by a split or a merge too, which created_transaction_id can't record.
        if (links.existsByTransactionIdAndPendingMovementIdNot(tx.id, movement.id)) {
            throw AppException.conflict("TRANSACTION_ALREADY_BANK_LINKED")
        }
        // Re-dating a materialized occurrence onto a sibling's date would break the
        // (recurring_template_id, occurrence_date) unique index; refuse rather than silently detach.
        val templateId = tx.recurringTemplateId
        if (templateId != null && tx.occurrenceDate != movement.bookingDate &&
            transactions.existsByRecurringTemplateIdAndOccurrenceDate(templateId, movement.bookingDate)
        ) {
            throw AppException.conflict("REPLACE_TEMPLATE_DATE_CONFLICT")
        }

        val categoryCode = request.categoryCode?.takeIf { it.isNotBlank() } ?: tx.categoryCode
        val description = request.description?.takeIf { it.isNotBlank() } ?: movementDescription(movement)
        transactionService.update(
            householdId,
            tx.id,
            TransactionRequest(
                occurrenceDate = movement.bookingDate,
                direction = request.direction ?: tx.direction,
                categoryCode = categoryCode,
                amount = movement.amount,
                description = description?.take(500),
            ),
            by,
        )
        movement.createdTransactionId = tx.id
        link(movement, tx.id)
        markConfirmed(movement, categoryCode, by)
        metrics.bankMovementsIngested(1)
        return movement.toDto(connections.findById(movement.connectionId).orElse(null), account(movement), false)
    }

    /** One transaction per part, all on the movement's booking date and sharing one direction; the parts
     *  must sum to the movement's amount to the cent. Links every created transaction and leaves
     *  [PendingMovement.createdTransactionId] null. Deliberately no rule learning — one counterparty
     *  mapping to several categories is what a learned rule cannot express. */
    @Transactional
    fun split(householdId: UUID, id: UUID, request: SplitMovementRequest, by: User): PendingMovementDto {
        val movement = requirePending(householdId, id)
        val parts = request.parts
        // A single part is an ordinary confirm, not a split.
        if (parts.size < 2) throw AppException.badRequest("BANK_SPLIT_TOO_FEW_PARTS")
        val direction = request.direction ?: movement.direction

        // Validated up front so a bad part never leaves earlier ones created and counted.
        val amounts = parts.map { Money.normalize(it.amount) }
        if (amounts.any { it.signum() <= 0 }) throw AppException.badRequest("BANK_SPLIT_AMOUNT_INVALID")
        if (parts.any { it.categoryCode.isBlank() }) throw AppException.badRequest("BANK_CATEGORY_REQUIRED")
        val sum = amounts.reduce(BigDecimal::add)
        if (sum.compareTo(movement.amount) != 0) throw AppException.badRequest("BANK_SPLIT_TOTAL_MISMATCH")

        // Silent creates; one aggregated notification below instead of N.
        parts.forEachIndexed { index, part ->
            createTransaction(
                householdId = householdId,
                movement = movement,
                categoryCode = part.categoryCode,
                direction = direction,
                note = part.description,
                by = by,
                notify = false,
                amount = amounts[index],
            )
        }
        markConfirmed(movement, categoryCode = null, by)
        notifications.bankMovementSplit(householdId, parts.size, movement.amount, NotifyActor.Human(by.email))
        metrics.bankMovementsIngested(1)
        return movement.toDto(
            connections.findById(movement.connectionId).orElse(null),
            account(movement),
            false,
            links.findAllByPendingMovementId(movement.id).map { it.transactionId },
        )
    }

    /** The inverse of [split]: N items that are really one purchase become ONE transaction, each linked to
     *  it. The amount is their signed net (incomes add, expenses subtract) and the direction is the sign of
     *  that net, so a charge and its partial refund collapse into what actually left the account — never
     *  requested, because a merge can't invent or drop money. [PendingMovement.createdTransactionId] stays
     *  null as in a split — the join table is the record, and it is what stops the merged transaction being
     *  Replace-claimed later. Deliberately no rule learning: several counterparties funding one purchase is
     *  not a counterparty -> category mapping. All-or-nothing — unlike [confirmBatch] a bad item aborts the
     *  whole merge instead of being skipped. */
    @Transactional
    fun merge(householdId: UUID, request: MergeMovementsRequest, by: User): MergeResultDto {
        val categoryCode = request.categoryCode.trim()
        if (categoryCode.isBlank()) throw AppException.badRequest("BANK_CATEGORY_REQUIRED")
        val movements = lockForMerge(householdId, request.items)
        val net = signedNet(movements, request.items)
        // Nothing left to record. The dialog offers cancelOut instead; this keeps the API honest.
        if (net.signum() == 0) throw AppException.badRequest("BANK_MERGE_NETS_TO_ZERO")
        val direction = if (net.signum() > 0) Direction.income else Direction.expense

        val description = (
            request.description?.takeIf { it.isNotBlank() }
                ?: movements.mapNotNull { movementDescription(it) }.distinct().joinToString(" + ").ifBlank { null }
            )?.take(500)

        // Silent create; one aggregated notification below instead of a card for a transaction that is
        // only half the story. A category/direction mismatch surfaces here — against the *netted*
        // direction, the only one the transaction will have — before anything is linked.
        val tx = transactionService.createInternal(
            householdId,
            TransactionRequest(
                occurrenceDate = request.date ?: movements.first().bookingDate,
                direction = direction,
                categoryCode = categoryCode,
                amount = net.abs(),
                description = description,
            ),
            by,
            notify = false,
        )
        movements.forEach {
            link(it, tx.id)
            markConfirmed(it, categoryCode, by)
        }
        notifications.bankMovementsMerged(householdId, movements.size, tx.amount, NotifyActor.Human(by.email))
        metrics.bankMovementsIngested(movements.size)
        return MergeResultDto(transactionId = tx.id, mergedCount = movements.size)
    }

    /** The other outcome of a merge: the items cancel each other out exactly, so there is no transaction to
     *  create and they are all rejected instead. Ordinary rejected items — restorable, creating nothing and
     *  linking nothing. Atomic like [merge]: an item processed meanwhile aborts the lot rather than leaving
     *  half a cancelling pair resolved. */
    @Transactional
    fun cancelOut(householdId: UUID, request: CancelOutRequest, by: User): BatchResultDto {
        val movements = lockForMerge(householdId, request.items)
        if (signedNet(movements, request.items).signum() != 0) {
            throw AppException.badRequest("BANK_MERGE_NOT_CANCELLING_OUT")
        }
        movements.forEach { markRejected(it, by) }
        return BatchResultDto(rejected = movements.size)
    }

    @Transactional
    fun confirmBatch(householdId: UUID, items: List<ConfirmBatchItem>, by: User): BatchResultDto {
        val byId = items.associateBy { it.id }
        val movements = pending.findAllByIdInAndHouseholdIdForUpdate(byId.keys, householdId)
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
            val tx = createTransaction(householdId, movement, categoryCode, direction, item?.note, by, notify = false)
            movement.createdTransactionId = tx.id
            markConfirmed(movement, categoryCode, by)
            categorization.learn(householdId, movement, categoryCode, direction, by)
            confirmed++
        }
        notifications.bankMovementsConfirmed(householdId, confirmed, NotifyActor.Human(by.email))
        metrics.bankMovementsIngested(confirmed)
        return BatchResultDto(confirmed = confirmed, skipped = skipped)
    }

    @Transactional
    fun reject(householdId: UUID, id: UUID, by: User): PendingMovementDto {
        val movement = requirePending(householdId, id)
        markRejected(movement, by)
        return movement.toDto(connections.findById(movement.connectionId).orElse(null), account(movement), false)
    }

    @Transactional
    fun rejectBatch(householdId: UUID, ids: List<UUID>, by: User): BatchResultDto {
        val movements = pending.findAllByIdInAndHouseholdId(ids, householdId).filter { it.status == MovementStatus.pending }
        movements.forEach { markRejected(it, by) }
        return BatchResultDto(rejected = movements.size)
    }

    /** Send a rejected item back to the inbox. Safe because reject creates nothing. Confirmed items are
     *  terminal (a transaction exists) and cannot be restored this way. */
    @Transactional
    fun restore(householdId: UUID, id: UUID): PendingMovementDto {
        val movement = pending.findByIdAndHouseholdId(id, householdId)
            ?: throw AppException.notFound("PENDING_MOVEMENT_NOT_FOUND")
        if (movement.status != MovementStatus.rejected) throw AppException.conflict("PENDING_MOVEMENT_NOT_REJECTED")
        restoreToPending(movement)
        return movement.toDto(connections.findById(movement.connectionId).orElse(null), account(movement), isPossibleDuplicate(movement))
    }

    @Transactional
    fun restoreBatch(householdId: UUID, ids: List<UUID>): BatchResultDto {
        val movements = pending.findAllByIdInAndHouseholdId(ids, householdId).filter { it.status == MovementStatus.rejected }
        movements.forEach { restoreToPending(it) }
        return BatchResultDto(restored = movements.size)
    }

    /** Re-run the rules over still-pending, *uncategorized* movements, filling each match's suggested
     *  category. Never overwrites an existing suggestion and confirms nothing — for movements ingested
     *  before the rules existed. */
    @Transactional
    fun applyRulesToPending(householdId: UUID): ApplyRulesResultDto {
        val rules = categorization.orderedRules(householdId)
        if (rules.isEmpty()) return ApplyRulesResultDto(categorized = 0)
        val movements = pending.findAllByHouseholdIdAndStatus(householdId, MovementStatus.pending)
            .filter { it.suggestedCategoryCode.isNullOrBlank() }
        var categorized = 0
        for (movement in movements) {
            val rule = categorization.matchRule(rules, movement) ?: continue
            movement.suggestedCategoryCode = rule.categoryCode
            movement.direction = rule.direction
            categorized++
        }
        return ApplyRulesResultDto(categorized = categorized)
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

    // Row-locked so two concurrent confirms of the same item can't both pass the status check and
    // each generate a transaction/movement — the loser blocks here, then re-reads status=confirmed.
    private fun requirePending(householdId: UUID, id: UUID): PendingMovement {
        val movement = pending.findByIdAndHouseholdIdForUpdate(id, householdId)
            ?: throw AppException.notFound("PENDING_MOVEMENT_NOT_FOUND")
        if (movement.status != MovementStatus.pending) throw AppException.conflict("PENDING_MOVEMENT_ALREADY_PROCESSED")
        return movement
    }

    /** Creates one transaction and records the link; single-transaction callers also set
     *  [PendingMovement.createdTransactionId]. Blank [note] falls back to the bank description. */
    private fun createTransaction(
        householdId: UUID,
        movement: PendingMovement,
        categoryCode: String,
        direction: Direction,
        note: String?,
        by: User,
        notify: Boolean,
        amount: BigDecimal = movement.amount,
    ): Transaction {
        val request = TransactionRequest(
            occurrenceDate = movement.bookingDate,
            direction = direction,
            categoryCode = categoryCode,
            amount = amount,
            description = (note?.takeIf { it.isNotBlank() } ?: movementDescription(movement))?.take(500),
        )
        val tx = transactionService.createInternal(householdId, request, by, notify)
        link(movement, tx.id)
        return tx
    }

    private fun link(movement: PendingMovement, transactionId: UUID) {
        links.save(PendingMovementTransaction(pendingMovementId = movement.id, transactionId = transactionId))
    }

    /** The selection both merge outcomes work on, row-locked as a batch confirm so a concurrent confirm or
     *  reject of any item serialises here. Sorted by booking date then id, which is what makes the date
     *  default and the joined description deterministic. Refuses anything that isn't a whole mergeable set:
     *  merging is all-or-nothing, so a missing or already-processed item aborts instead of being skipped. */
    private fun lockForMerge(householdId: UUID, items: List<MergeItemRequest>): List<PendingMovement> {
        val ids = items.map { it.id }.distinct()
        if (ids.size < 2) throw AppException.badRequest("BANK_MERGE_TOO_FEW_ITEMS")
        val movements = pending.findAllByIdInAndHouseholdIdForUpdate(ids, householdId)
            .sortedWith(compareBy({ it.bookingDate }, { it.id }))
        if (movements.size != ids.size) throw AppException.notFound("PENDING_MOVEMENT_NOT_FOUND")
        if (movements.any { it.status != MovementStatus.pending }) {
            throw AppException.conflict("PENDING_MOVEMENT_ALREADY_PROCESSED")
        }
        return movements
    }

    /** Incomes add, expenses subtract. The request's per-item direction wins over the stored one so an
     *  inbox row flipped before merging (a refund the bank booked as a charge) counts the way it reads. */
    private fun signedNet(movements: List<PendingMovement>, items: List<MergeItemRequest>): BigDecimal {
        val requested = items.associate { it.id to it.direction }
        return movements.fold(BigDecimal.ZERO) { net, m ->
            val direction = requested[m.id] ?: m.direction
            if (direction == Direction.income) net.add(m.amount) else net.subtract(m.amount)
        }
    }

    private fun createMovement(
        householdId: UUID,
        movement: PendingMovement,
        request: ConfirmAsMovementRequest,
        by: User,
    ) {
        // MovementService.create() validates the type/target pairing (MOVEMENT_TARGET_INVALID).
        val created = movementService.create(
            householdId,
            MovementRequest(
                movementDate = movement.bookingDate,
                type = request.type,
                assetClassCode = request.assetClassCode,
                liabilityId = request.liabilityId,
                amount = movement.amount,
                description = (request.note ?: movementDescription(movement))?.take(500),
            ),
            by,
        )
        movement.createdMovementId = created.id
    }

    private fun markRejected(movement: PendingMovement, by: User) {
        movement.status = MovementStatus.rejected
        movement.processedAt = Instant.now()
        movement.processedByUserId = by.id
    }

    private fun restoreToPending(movement: PendingMovement) {
        movement.status = MovementStatus.pending
        movement.processedAt = null
        movement.processedByUserId = null
    }

    // categoryCode is null when confirming as a net-worth movement (movements have no category);
    // in that case the existing suggestedCategoryCode is left untouched.
    private fun markConfirmed(movement: PendingMovement, categoryCode: String?, by: User) {
        movement.status = MovementStatus.confirmed
        if (categoryCode != null) movement.suggestedCategoryCode = categoryCode
        movement.processedAt = Instant.now()
        movement.processedByUserId = by.id
    }

    private fun account(movement: PendingMovement): BankConnectionAccount? =
        accounts.findById(movement.accountId).orElse(null)

    private fun movementDescription(m: PendingMovement): String? {
        val parts = listOfNotNull(m.counterparty?.trim()?.ifBlank { null }, m.description?.trim()?.ifBlank { null })
        return parts.distinct().joinToString(" – ").ifBlank { null }
    }

    /** Possible-duplicate against manual transactions: same direction + amount within a few days (warn only,
     *  never auto-discard). The list path uses [buildDuplicateIndex] to avoid one query per row. */
    private fun isPossibleDuplicate(m: PendingMovement): Boolean =
        candidatesFor(buildDuplicateIndex(m.householdId, listOf(m)), m).isNotEmpty()

    /** The duplicate rule in one place — shared by the list flag, the candidate lookup and [replace]'s guard. */
    private fun isCandidate(m: PendingMovement, tx: Transaction): Boolean =
        tx.direction == m.direction &&
            tx.amount.compareTo(m.amount) == 0 &&
            abs(ChronoUnit.DAYS.between(m.bookingDate, tx.occurrenceDate)) <= DUPLICATE_WINDOW_DAYS

    /** direction+amount -> the transactions in the comparison window, loaded in one query. */
    private fun buildDuplicateIndex(householdId: UUID, items: List<PendingMovement>): Map<String, List<Transaction>> {
        val from = items.minOf { it.bookingDate }.minusDays(DUPLICATE_WINDOW_DAYS)
        val to = items.maxOf { it.bookingDate }.plusDays(DUPLICATE_WINDOW_DAYS)
        return transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, from, to)
            .groupBy { dupKey(it.direction, it.amount) }
    }

    /** The bucket already pins direction+amount; this narrows it to the movement's date window. */
    private fun candidatesFor(index: Map<String, List<Transaction>>, m: PendingMovement): List<Transaction> =
        index[dupKey(m.direction, m.amount)]?.filter { isCandidate(m, it) } ?: emptyList()

    private fun matchesDuplicate(index: Map<String, List<Transaction>>, m: PendingMovement): Boolean =
        candidatesFor(index, m).isNotEmpty()

    private fun dupKey(direction: Direction, amount: BigDecimal): String =
        "$direction|${amount.stripTrailingZeros().toPlainString()}"

    private fun PendingMovement.toDto(
        connection: BankConnection?,
        account: BankConnectionAccount?,
        possibleDuplicate: Boolean,
        // Defaults to the single link, which is what every path except a split produces.
        transactionIds: List<UUID> = listOfNotNull(createdTransactionId),
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
        createdTransactionIds = transactionIds,
        createdMovementId = createdMovementId,
        possibleDuplicate = possibleDuplicate,
    )

    private companion object {
        const val DUPLICATE_WINDOW_DAYS = 3L
    }
}
