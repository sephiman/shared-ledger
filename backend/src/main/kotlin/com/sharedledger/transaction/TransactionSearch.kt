package com.sharedledger.transaction

import com.sharedledger.catalog.CategoryEntity
import org.springframework.data.jpa.domain.Specification
import java.time.LocalDate
import java.util.UUID

data class TransactionSearchCriteria(
    val householdId: UUID,
    val from: LocalDate?,
    val to: LocalDate?,
    val direction: Direction?,
    val categoryCode: String?,
    val categoryGroup: String?,
    val page: Int,
    val size: Int,
    val sort: String,
)

object TransactionSpecs {

    fun householdIs(householdId: UUID): Specification<Transaction> =
        Specification { root, _, cb -> cb.equal(root.get<UUID>("householdId"), householdId) }

    fun fromDate(from: LocalDate): Specification<Transaction> =
        Specification { root, _, cb -> cb.greaterThanOrEqualTo(root.get("occurrenceDate"), from) }

    fun toDate(to: LocalDate): Specification<Transaction> =
        Specification { root, _, cb -> cb.lessThanOrEqualTo(root.get("occurrenceDate"), to) }

    fun directionIs(direction: Direction): Specification<Transaction> =
        Specification { root, _, cb -> cb.equal(root.get<Direction>("direction"), direction) }

    fun categoryCodeIs(code: String): Specification<Transaction> =
        Specification { root, _, cb -> cb.equal(root.get<String>("categoryCode"), code) }

    fun categoryGroupIs(group: String): Specification<Transaction> =
        Specification { root, query, cb ->
            val q = query ?: return@Specification cb.conjunction()
            val sub = q.subquery(String::class.java)
            val cat = sub.from(CategoryEntity::class.java)
            sub.select(cat.get<String>("code")).where(cb.equal(cat.get<String>("groupCode"), group))
            root.get<String>("categoryCode").`in`(sub)
        }

    fun fromCriteria(c: TransactionSearchCriteria): Specification<Transaction> {
        var spec: Specification<Transaction> = householdIs(c.householdId)
        c.from?.let { spec = spec.and(fromDate(it)) }
        c.to?.let { spec = spec.and(toDate(it)) }
        c.direction?.let { spec = spec.and(directionIs(it)) }
        c.categoryCode?.let { spec = spec.and(categoryCodeIs(it)) }
        c.categoryGroup?.let { spec = spec.and(categoryGroupIs(it)) }
        return spec
    }
}
