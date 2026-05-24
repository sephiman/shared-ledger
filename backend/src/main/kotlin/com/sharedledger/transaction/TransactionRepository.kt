package com.sharedledger.transaction

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
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

interface MinMaxDateRow {
    val minDate: LocalDate?
    val maxDate: LocalDate?
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
}
