package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.common.TimestampedEntity
import com.sephilabs.sharedledger.transaction.Direction
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A raw bank movement awaiting review. Its own entity with its own endpoints — the transaction
 * tables/endpoints are never touched. Confirming generates either a transaction (sets
 * [createdTransactionId]) or a net-worth movement (sets [createdMovementId]) and flips
 * status=confirmed so the item is never re-ingested. Exactly one of the two links is set.
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

    @Column(name = "created_movement_id")
    var createdMovementId: UUID? = null,

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

    /**
     * Row-locking variants used when confirming/rejecting: two concurrent confirms of the same item
     * would otherwise both pass the in-memory status check and each generate a transaction/movement.
     * `SELECT … FOR UPDATE` serialises them — the loser blocks, then re-reads status=confirmed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM PendingMovement m WHERE m.id = :id AND m.householdId = :hid")
    fun findByIdAndHouseholdIdForUpdate(@Param("id") id: UUID, @Param("hid") householdId: UUID): PendingMovement?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM PendingMovement m WHERE m.id IN :ids AND m.householdId = :hid")
    fun findAllByIdInAndHouseholdIdForUpdate(@Param("ids") ids: Collection<UUID>, @Param("hid") householdId: UUID): List<PendingMovement>

    fun existsByConnectionIdAndBankMovementId(connectionId: UUID, bankMovementId: String): Boolean

    fun countByHouseholdIdAndStatus(householdId: UUID, status: MovementStatus): Long

    fun findAllByHouseholdIdAndStatus(householdId: UUID, status: MovementStatus): List<PendingMovement>

    /**
     * The full-dataset pending query behind the review inbox. Text search ([search], a pre-lowercased
     * `%term%` or null) and categorisation ([categorized]: true = has a suggested category, false =
     * none, null = either) are applied server-side so the filters cover every row, not just a page.
     * The possible-duplicate filter is computed cross-table in the service, so it isn't expressed here.
     */
    @Query(
        value = """
        SELECT m FROM PendingMovement m
        WHERE m.householdId = :hid
          AND m.status = :status
          AND (:connectionId IS NULL OR m.connectionId = :connectionId)
          AND (:search IS NULL
               OR LOWER(m.counterparty) LIKE :search
               OR LOWER(m.description) LIKE :search
               OR LOWER(m.reference) LIKE :search)
          AND (:categorized IS NULL
               OR (:categorized = TRUE AND m.suggestedCategoryCode IS NOT NULL AND m.suggestedCategoryCode <> '')
               OR (:categorized = FALSE AND (m.suggestedCategoryCode IS NULL OR m.suggestedCategoryCode = '')))
        ORDER BY m.bookingDate DESC, m.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(m) FROM PendingMovement m
        WHERE m.householdId = :hid
          AND m.status = :status
          AND (:connectionId IS NULL OR m.connectionId = :connectionId)
          AND (:search IS NULL
               OR LOWER(m.counterparty) LIKE :search
               OR LOWER(m.description) LIKE :search
               OR LOWER(m.reference) LIKE :search)
          AND (:categorized IS NULL
               OR (:categorized = TRUE AND m.suggestedCategoryCode IS NOT NULL AND m.suggestedCategoryCode <> '')
               OR (:categorized = FALSE AND (m.suggestedCategoryCode IS NULL OR m.suggestedCategoryCode = '')))
        """,
    )
    fun search(
        @Param("hid") householdId: UUID,
        @Param("status") status: MovementStatus,
        @Param("connectionId") connectionId: UUID?,
        @Param("search") search: String?,
        @Param("categorized") categorized: Boolean?,
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
