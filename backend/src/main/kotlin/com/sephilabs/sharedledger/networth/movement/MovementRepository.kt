package com.sephilabs.sharedledger.networth.movement

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

interface MovementRepository : JpaRepository<NetWorthMovement, UUID> {

    @Query("""
        SELECT m FROM NetWorthMovement m
        WHERE m.householdId = :hid
          AND m.movementDate BETWEEN :from AND :to
        ORDER BY m.movementDate DESC, m.createdAt DESC
    """)
    fun findInRange(@Param("hid") householdId: UUID, @Param("from") from: LocalDate, @Param("to") to: LocalDate): List<NetWorthMovement>

    @Modifying
    @Query(value = "DELETE FROM net_worth_movements WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}

interface MovementQueryRepository {
    fun search(criteria: MovementSearchCriteria): MovementSearchResult
}

data class MovementSearchCriteria(
    val householdId: UUID,
    val from: LocalDate?,
    val to: LocalDate?,
    val type: MovementType?,
    val assetClassCode: String?,
    val liabilityId: UUID?,
    val page: Int,
    val size: Int,
)

data class MovementSearchResult(
    val items: List<NetWorthMovement>,
    val total: Long,
)

@Repository
class MovementQueryRepositoryImpl(
    @PersistenceContext private val em: EntityManager,
) : MovementQueryRepository {

    override fun search(criteria: MovementSearchCriteria): MovementSearchResult {
        val clauses = mutableListOf("m.householdId = :hid")
        val params = mutableMapOf<String, Any>("hid" to criteria.householdId)
        criteria.from?.let { clauses += "m.movementDate >= :from"; params["from"] = it }
        criteria.to?.let { clauses += "m.movementDate <= :to"; params["to"] = it }
        criteria.type?.let { clauses += "m.type = :type"; params["type"] = it }
        criteria.assetClassCode?.let { clauses += "m.assetClassCode = :cls"; params["cls"] = it }
        criteria.liabilityId?.let { clauses += "m.liabilityId = :lid"; params["lid"] = it }
        val where = "WHERE " + clauses.joinToString(" AND ")

        val q = em.createQuery("SELECT m FROM NetWorthMovement m $where ORDER BY m.movementDate DESC, m.createdAt DESC", NetWorthMovement::class.java)
        params.forEach { (k, v) -> q.setParameter(k, v) }
        q.firstResult = criteria.page * criteria.size
        q.maxResults = criteria.size

        val countQ = em.createQuery("SELECT COUNT(m) FROM NetWorthMovement m $where", java.lang.Long::class.java)
        params.forEach { (k, v) -> countQ.setParameter(k, v) }

        return MovementSearchResult(items = q.resultList, total = countQ.singleResult.toLong())
    }
}
