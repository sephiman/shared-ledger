package com.sephilabs.sharedledger.budget

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface BudgetRepository : JpaRepository<Budget, UUID> {

    @Modifying
    @Query(value = "DELETE FROM budgets WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findAllByHouseholdIdAndYearAndMonth(householdId: UUID, year: Short, month: Short): List<Budget>

    fun findAllByHouseholdIdAndYearAndMonthIsNull(householdId: UUID, year: Short): List<Budget>

    fun findAllByHouseholdIdAndYear(householdId: UUID, year: Short): List<Budget>

    fun findByHouseholdIdAndYearAndMonthAndCategoryCode(
        householdId: UUID,
        year: Short,
        month: Short,
        categoryCode: String,
    ): Budget?

    fun findByHouseholdIdAndYearAndMonthIsNullAndCategoryCode(
        householdId: UUID,
        year: Short,
        categoryCode: String,
    ): Budget?
}
