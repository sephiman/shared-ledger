package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.common.TimestampedEntity
import com.sephilabs.sharedledger.transaction.Direction
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A raw bank movement awaiting review. Its own entity with its own endpoints — the transaction
 * tables/endpoints are never touched. Confirming generates a transaction and sets
 * [createdTransactionId] + status=confirmed so the item is never re-ingested.
 */
@Entity
@Table(name = "pending_movements")
class PendingMovement(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "connection_id", nullable = false, updatable = false)
    var connectionId: UUID,

    @Column(name = "account_id", nullable = false, updatable = false)
    var accountId: UUID,

    @Column(name = "bank_movement_id", nullable = false, length = 255)
    var bankMovementId: String,

    @Column(name = "booking_date", nullable = false)
    var bookingDate: LocalDate,

    @Column(name = "value_date")
    var valueDate: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    var direction: Direction,

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    var amount: BigDecimal,

    @Column(name = "original_amount", precision = 18, scale = 2)
    var originalAmount: BigDecimal? = null,

    @Column(name = "original_currency", length = 3)
    var originalCurrency: String? = null,

    @Column(name = "counterparty", length = 255)
    var counterparty: String? = null,

    @Column(name = "description", length = 500)
    var description: String? = null,

    @Column(name = "reference", length = 255)
    var reference: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: MovementStatus = MovementStatus.pending,

    @Column(name = "suggested_category_code", length = 64)
    var suggestedCategoryCode: String? = null,

    @Column(name = "created_transaction_id")
    var createdTransactionId: UUID? = null,

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Column(name = "processed_by_user_id")
    var processedByUserId: UUID? = null,
) : TimestampedEntity()

interface PendingMovementRepository : JpaRepository<PendingMovement, UUID> {

    @Modifying
    @Query(value = "DELETE FROM pending_movements WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): PendingMovement?

    fun findAllByIdInAndHouseholdId(ids: Collection<UUID>, householdId: UUID): List<PendingMovement>

    fun existsByConnectionIdAndBankMovementId(connectionId: UUID, bankMovementId: String): Boolean

    fun countByHouseholdIdAndStatus(householdId: UUID, status: MovementStatus): Long

    fun findAllByHouseholdIdAndStatus(householdId: UUID, status: MovementStatus): List<PendingMovement>

    @Query(
        value = """
        SELECT m FROM PendingMovement m
        WHERE m.householdId = :hid
          AND m.status = :status
          AND (:connectionId IS NULL OR m.connectionId = :connectionId)
        ORDER BY m.bookingDate DESC, m.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(m) FROM PendingMovement m
        WHERE m.householdId = :hid
          AND m.status = :status
          AND (:connectionId IS NULL OR m.connectionId = :connectionId)
        """,
    )
    fun search(
        @Param("hid") householdId: UUID,
        @Param("status") status: MovementStatus,
        @Param("connectionId") connectionId: UUID?,
        pageable: Pageable,
    ): Page<PendingMovement>

    @Query(
        """
        SELECT MAX(m.bookingDate) FROM PendingMovement m
        WHERE m.connectionId = :connectionId AND m.accountId = :accountId
        """
    )
    fun findMaxBookingDate(
        @Param("connectionId") connectionId: UUID,
        @Param("accountId") accountId: UUID,
    ): LocalDate?
}
