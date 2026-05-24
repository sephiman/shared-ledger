package com.sharedledger.budget

import com.sharedledger.catalog.CategoryRepository
import com.sharedledger.common.Money
import com.sharedledger.common.errors.AppException
import com.sharedledger.identity.user.User
import com.sharedledger.transaction.Direction
import com.sharedledger.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Service
class BudgetService(
    private val budgets: BudgetRepository,
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
) {

    @Transactional
    fun upsert(householdId: UUID, request: BudgetUpsertRequest, by: User): List<BudgetDto> {
        val result = mutableListOf<Budget>()
        for (item in request.items) {
            val category = categories.findById(item.categoryCode).orElseThrow { AppException.notFound("CATEGORY_NOT_FOUND") }
            if (category.kind != "expense") throw AppException.badRequest("BUDGET_REQUIRES_EXPENSE_CATEGORY")

            val existing = if (item.month != null) {
                budgets.findByHouseholdIdAndYearAndMonthAndCategoryCode(householdId, item.year, item.month, item.categoryCode)
            } else {
                budgets.findByHouseholdIdAndYearAndMonthIsNullAndCategoryCode(householdId, item.year, item.categoryCode)
            }

            if (existing != null) {
                existing.amount = Money.normalize(item.amount)
                existing.updatedByUserId = by.id
                result += existing
            } else {
                val b = Budget(
                    householdId = householdId,
                    year = item.year,
                    month = item.month,
                    categoryCode = item.categoryCode,
                    amount = Money.normalize(item.amount),
                    createdByUserId = by.id,
                    updatedByUserId = by.id,
                )
                budgets.save(b)
                result += b
            }
        }
        return result.map { it.toDto() }
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID) {
        val b = budgets.findById(id).orElseThrow { AppException.notFound("BUDGET_NOT_FOUND") }
        if (b.householdId != householdId) throw AppException.notFound("BUDGET_NOT_FOUND")
        budgets.delete(b)
    }

    @Transactional(readOnly = true)
    fun listMonth(householdId: UUID, year: Short, month: Short): List<BudgetDto> =
        budgets.findAllByHouseholdIdAndYearAndMonth(householdId, year, month).map { it.toDto() }

    @Transactional(readOnly = true)
    fun listYear(householdId: UUID, year: Short): List<BudgetDto> =
        budgets.findAllByHouseholdIdAndYear(householdId, year).map { it.toDto() }

    @Transactional(readOnly = true)
    fun monthSummary(householdId: UUID, year: Short, month: Short): MonthSummaryResponse {
        val ym = YearMonth.of(year.toInt(), month.toInt())
        val from = ym.atDay(1)
        val to = ym.atEndOfMonth()
        val today = LocalDate.now()
        val daysInMonth = ym.lengthOfMonth()
        val daysElapsed = when {
            today.isBefore(from) -> 0
            today.isAfter(to) -> daysInMonth
            else -> today.dayOfMonth
        }

        val monthly = budgets.findAllByHouseholdIdAndYearAndMonth(householdId, year, month).associateBy { it.categoryCode }
        val annualBudgets = budgets.findAllByHouseholdIdAndYearAndMonthIsNull(householdId, year).associateBy { it.categoryCode }

        val txInMonth = transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, from, to)
            .filter { it.direction == Direction.expense }
        val spent: Map<String, BigDecimal> = txInMonth.groupBy { it.categoryCode }
            .mapValues { entry -> entry.value.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount } }

        val codes = (monthly.keys + annualBudgets.keys + spent.keys).distinct()
        val rows = codes.map { code ->
            val budgetAmount = monthly[code]?.amount
                ?: annualBudgets[code]?.amount?.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_EVEN)
                ?: BigDecimal.ZERO
            val spentAmount = spent[code] ?: BigDecimal.ZERO
            val pace = if (daysElapsed > 0) {
                spentAmount.divide(BigDecimal.valueOf(daysElapsed.toLong()), 4, RoundingMode.HALF_EVEN)
            } else BigDecimal.ZERO
            val projection = pace.multiply(BigDecimal.valueOf(daysInMonth.toLong())).setScale(2, RoundingMode.HALF_EVEN)
            val percent = if (budgetAmount.signum() > 0) {
                spentAmount.divide(budgetAmount, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0
            } else 0.0
            MonthSummaryRow(
                categoryCode = code,
                budget = Money.normalize(budgetAmount),
                spent = Money.normalize(spentAmount),
                pace = pace.setScale(2, RoundingMode.HALF_EVEN),
                projection = projection,
                percent = percent,
            )
        }.sortedByDescending { it.budget }

        return MonthSummaryResponse(year, month, daysElapsed, daysInMonth, rows)
    }

    private fun Budget.toDto() = BudgetDto(id, year, month, categoryCode, amount, updatedByUserId)
}
