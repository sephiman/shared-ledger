package com.sharedledger.networth.movement

import com.sharedledger.common.Csv
import com.sharedledger.common.Money
import com.sharedledger.common.PageResponse
import com.sharedledger.common.errors.AppException
import com.sharedledger.identity.user.User
import com.sharedledger.networth.liability.LiabilityRepository
import com.sharedledger.observability.AppMetrics
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class MovementService(
    private val movements: MovementRepository,
    private val queries: MovementQueryRepository,
    private val liabilities: LiabilityRepository,
    private val metrics: AppMetrics,
) {

    @Transactional
    fun create(householdId: UUID, request: MovementRequest, by: User): NetWorthMovement {
        validateTarget(request)
        val movement = NetWorthMovement(
            householdId = householdId,
            movementDate = request.movementDate,
            type = request.type,
            assetClassCode = request.assetClassCode,
            liabilityId = request.liabilityId,
            amount = Money.normalize(request.amount),
            description = request.description,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        movements.save(movement)
        metrics.movementCreated(
            type = request.type.name,
            targetClass = request.assetClassCode,
            targetLiabilityId = request.liabilityId?.toString(),
        )
        return movement
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, request: MovementRequest, by: User): NetWorthMovement {
        validateTarget(request)
        val movement = loadOwn(householdId, id)
        movement.movementDate = request.movementDate
        movement.type = request.type
        movement.assetClassCode = request.assetClassCode
        movement.liabilityId = request.liabilityId
        movement.amount = Money.normalize(request.amount)
        movement.description = request.description
        movement.updatedByUserId = by.id
        return movement
    }

    @Transactional
    fun delete(householdId: UUID, id: UUID, by: User) {
        val m = loadOwn(householdId, id)
        m.deletedAt = Instant.now()
        m.updatedByUserId = by.id
    }

    @Transactional(readOnly = true)
    fun search(criteria: MovementSearchCriteria): PageResponse<MovementDto> {
        val r = queries.search(criteria)
        return PageResponse.of(r.items.map { it.toDto() }, criteria.page, criteria.size, r.total)
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): MovementDto = loadOwn(householdId, id).toDto()

    @Transactional(readOnly = true)
    fun cumulative(
        householdId: UUID,
        from: LocalDate,
        to: LocalDate,
        groupBy: String,
    ): List<CumulativeBucket> {
        val items = movements.findInRange(householdId, from, to)
        val buckets = LinkedHashMap<String, MutableMap<MovementType, BigDecimal>>()
        for (m in items) {
            val key = when (groupBy) {
                "asset_class" -> m.assetClassCode ?: "liabilities"
                "liability" -> m.liabilityId?.toString() ?: "assets"
                else -> "%04d-%02d".format(m.movementDate.year, m.movementDate.monthValue)
            }
            val byType = buckets.getOrPut(key) { mutableMapOf() }
            byType.merge(m.type, m.amount) { a, b -> a + b }
        }
        return buckets.map { (k, totals) ->
            CumulativeBucket(
                key = k,
                contributions = Money.normalize(totals[MovementType.contribution] ?: BigDecimal.ZERO),
                withdrawals = Money.normalize(totals[MovementType.withdrawal] ?: BigDecimal.ZERO),
                debtPayments = Money.normalize(totals[MovementType.debt_payment] ?: BigDecimal.ZERO),
            )
        }
    }

    @Transactional(readOnly = true)
    fun exportCsv(householdId: UUID): String {
        val all = movements.findInRange(householdId, LocalDate.of(1970, 1, 1), LocalDate.now().plusYears(100))
            .sortedWith(compareBy({ it.movementDate }, { it.createdAt }))
        val liabilityNames = liabilities.findAllByHouseholdIdOrderByNameAsc(householdId)
            .associate { it.id to it.name }
        val sb = StringBuilder()
        sb.append(Csv.row("date", "type", "asset_class_code", "liability_name", "amount", "description", "created_at"))
        for (m in all) {
            val liabName = m.liabilityId?.let { liabilityNames[it] ?: it.toString() }
            sb.append(Csv.row(
                m.movementDate,
                m.type,
                m.assetClassCode,
                liabName,
                Csv.decimal(m.amount),
                m.description,
                m.createdAt,
            ))
        }
        return sb.toString()
    }

    private fun validateTarget(request: MovementRequest) {
        val ok = when (request.type) {
            MovementType.contribution, MovementType.withdrawal ->
                request.assetClassCode != null && request.liabilityId == null
            MovementType.debt_payment ->
                request.liabilityId != null && request.assetClassCode == null
        }
        if (!ok) throw AppException.badRequest("MOVEMENT_TARGET_INVALID")
    }

    private fun loadOwn(householdId: UUID, id: UUID): NetWorthMovement {
        val m = movements.findById(id).orElseThrow { AppException.notFound("MOVEMENT_NOT_FOUND") }
        if (m.householdId != householdId) throw AppException.notFound("MOVEMENT_NOT_FOUND")
        return m
    }
}
