package com.sharedledger.budget

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BudgetRepository : JpaRepository<Budget, UUID> {

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
