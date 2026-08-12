package com.sephilabs.sharedledger.transaction

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

interface TxAggRow {
    val occurrenceDate: LocalDate
    val direction: Direction
    val categoryCode: String
    val amount: BigDecimal
    val recurringTemplateId: UUID?
}

/** How much of an original expense has already come back, in one grouped query per page. */
interface RefundTotalRow {
    val originalId: UUID
    val total: BigDecimal
    val cnt: Long
}

interface MinMaxDateRow {
    val minDate: LocalDate?
    val maxDate: LocalDate?
}

interface RecurringLastFiredRow {
    val templateId: UUID
    val lastFired: LocalDate
}

interface TransactionRepository :
    JpaRepository<Transaction, UUID>,
    JpaSpecificationExecutor<Transaction> {

    @Modifying
    @Query(value = "DELETE FROM transactions WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findByHouseholdIdAndOccurrenceDateBetween(
        householdId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<Transaction>

    /** Refunds already linked to each of [ids] — soft-deleted ones drop out with the entity restriction. */
    @Query(
        """
        SELECT t.refundOfTransactionId AS originalId, SUM(t.amount) AS total, COUNT(t) AS cnt
        FROM Transaction t
        WHERE t.refundOfTransactionId IN :ids
        GROUP BY t.refundOfTransactionId
        """,
    )
    fun refundTotalsFor(@Param("ids") ids: Collection<UUID>): List<RefundTotalRow>

    fun existsByRefundOfTransactionId(refundOfTransactionId: UUID): Boolean

    /** Row-locked load for Replace: two concurrent replaces of the same target would otherwise both pass
     *  the "already backs a movement" check and link it twice. `SELECT … FOR UPDATE` serialises them. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): Transaction?

    fun existsByRecurringTemplateIdAndOccurrenceDate(
        recurringTemplateId: UUID,
        occurrenceDate: LocalDate,
    ): Boolean

    @Query("""
        SELECT t.categoryCode, COUNT(t)
        FROM Transaction t
        WHERE t.householdId = :hid
          AND t.createdByUserId = :uid
          AND t.occurrenceDate >= :from
        GROUP BY t.categoryCode
        ORDER BY COUNT(t) DESC
    """)
    fun topCategoriesForUser(
        @Param("hid") householdId: UUID,
        @Param("uid") userId: UUID,
        @Param("from") from: LocalDate,
    ): List<Array<Any>>

    @Query("""
        SELECT t.occurrenceDate as occurrenceDate,
               t.direction as direction,
               t.categoryCode as categoryCode,
               t.amount as amount,
               t.recurringTemplateId as recurringTemplateId
        FROM Transaction t
        WHERE t.householdId = :hid
          AND t.occurrenceDate BETWEEN :from AND :to
    """)
    fun aggregationRows(
        @Param("hid") householdId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<TxAggRow>

    @Query("""
        SELECT MIN(t.occurrenceDate) as minDate, MAX(t.occurrenceDate) as maxDate
        FROM Transaction t
        WHERE t.householdId = :hid
    """)
    fun dateBounds(@Param("hid") householdId: UUID): MinMaxDateRow

    /** Latest occurrence date actually produced by each template — the ground truth for "last fired". A
     *  template that scanned forward without generating anything simply has no row here. */
    @Query("""
        SELECT t.recurringTemplateId as templateId, MAX(t.occurrenceDate) as lastFired
        FROM Transaction t
        WHERE t.householdId = :hid AND t.recurringTemplateId IS NOT NULL
        GROUP BY t.recurringTemplateId
    """)
    fun lastFiredDatesByTemplate(@Param("hid") householdId: UUID): List<RecurringLastFiredRow>

    @Query("""
        SELECT MAX(t.occurrenceDate)
        FROM Transaction t
        WHERE t.recurringTemplateId = :tid
    """)
    fun lastFiredDateForTemplate(@Param("tid") templateId: UUID): LocalDate?
}
