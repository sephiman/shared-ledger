package com.sephilabs.sharedledger.transaction

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/** The list's totals line, in the rows' own convention (the direction carries the sign; refunds are negative
 *  expenses): [expenses] is net of refunds, so with "Refunds only" it is what came back, negative; [net] is income
 *  minus expenses. The client signs them for display exactly as it signs rows. */
data class TransactionTotalsDto(
    val count: Long,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val income: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val expenses: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING) val net: BigDecimal,
)

/** One grouped aggregate under the list's own filters ([TransactionSpecs.fromCriteria]), over the whole filtered set
 *  rather than a page. */
@Component
class TransactionTotals(private val em: EntityManager) {

    @Transactional(readOnly = true)
    fun of(criteria: TransactionSearchCriteria): TransactionTotalsDto {
        val cb = em.criteriaBuilder
        val query = cb.createTupleQuery()
        val root = query.from(Transaction::class.java)
        val direction = root.get<Direction>("direction")
        query.select(cb.tuple(direction, cb.count(root), cb.sum(root.get<BigDecimal>("amount"))))
            .where(TransactionSpecs.fromCriteria(criteria).toPredicate(root, query, cb))
            .groupBy(direction)
        val rows = em.createQuery(query).resultList
        fun sum(match: (Tuple) -> Boolean) = rows.filter(match)
            .fold(BigDecimal.ZERO) { acc, row -> acc + (row.get(2, BigDecimal::class.java) ?: BigDecimal.ZERO) }
        val income = sum { it.get(0) == Direction.income }
        val expenses = sum { it.get(0) == Direction.expense }
        return TransactionTotalsDto(
            count = rows.sumOf { (it.get(1) as Number).toLong() },
            income = income.money(),
            expenses = expenses.money(),
            net = (income - expenses).money(),
        )
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.UNNECESSARY)
}
