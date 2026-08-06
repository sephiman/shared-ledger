package com.sephilabs.sharedledger.bank

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** One transaction a confirmed pending movement produced: one row for a confirm or Replace, one per part
 *  for a split. The single source for "is this transaction already resolving a movement?". */
@Entity
@Table(name = "pending_movement_transactions")
class PendingMovementTransaction(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "pending_movement_id", nullable = false, updatable = false)
    var pendingMovementId: UUID,

    @Column(name = "transaction_id", nullable = false, updatable = false)
    var transactionId: UUID,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

interface PendingMovementTransactionRepository : JpaRepository<PendingMovementTransaction, UUID> {

    /** Replace guard: a transaction backs at most one movement. */
    fun existsByTransactionIdAndPendingMovementIdNot(transactionId: UUID, pendingMovementId: UUID): Boolean

    fun findAllByPendingMovementId(pendingMovementId: UUID): List<PendingMovementTransaction>

    fun findAllByPendingMovementIdIn(pendingMovementIds: Collection<UUID>): List<PendingMovementTransaction>

    /** Which of [ids] already resolve a movement, in one query. Joined back to pending_movements for the
     *  household scope, which this table doesn't carry. */
    @Query(
        """
        SELECT l.transactionId FROM PendingMovementTransaction l, PendingMovement m
        WHERE l.pendingMovementId = m.id AND m.householdId = :hid AND l.transactionId IN :ids
        """,
    )
    fun findLinkedTransactionIds(@Param("hid") householdId: UUID, @Param("ids") ids: Collection<UUID>): List<UUID>
}
