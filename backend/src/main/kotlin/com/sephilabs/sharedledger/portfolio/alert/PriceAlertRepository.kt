package com.sephilabs.sharedledger.portfolio.alert

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface PriceAlertRepository : JpaRepository<PriceAlert, UUID> {

    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): PriceAlert?

    fun findAllByHouseholdIdOrderByCreatedAtDesc(householdId: UUID): List<PriceAlert>

    fun findAllByHouseholdIdAndHoldingIdOrderByCreatedAtDesc(householdId: UUID, holdingId: UUID): List<PriceAlert>

    fun findAllByStatus(status: AlertStatus): List<PriceAlert>

    fun countByHouseholdIdAndStatus(householdId: UUID, status: AlertStatus): Long

    @Query(
        """
        SELECT a FROM PriceAlert a
        WHERE a.status = 'FIRED'
          AND a.deliverySentAt IS NULL
          AND a.deliveryAttempts < :maxAttempts
          AND (a.deliveryLastAttemptAt IS NULL OR a.deliveryLastAttemptAt <= :retryBefore)
        ORDER BY a.firedAt ASC
        """
    )
    fun findPendingDeliveries(
        @Param("maxAttempts") maxAttempts: Int,
        @Param("retryBefore") retryBefore: Instant,
    ): List<PriceAlert>

    @Modifying
    @Query(
        """
        UPDATE PriceAlert a
        SET a.status = 'EXPIRED', a.updatedAt = :now
        WHERE a.householdId = :householdId
          AND a.status = 'ACTIVE'
          AND a.expiresAt IS NOT NULL
          AND a.expiresAt <= :now
        """
    )
    fun expireDueForHousehold(@Param("householdId") householdId: UUID, @Param("now") now: Instant): Int

    @Modifying
    @Query(
        """
        UPDATE PriceAlert a
        SET a.status = 'EXPIRED', a.updatedAt = :now
        WHERE a.id = :id
          AND a.status = 'ACTIVE'
          AND a.expiresAt IS NOT NULL
          AND a.expiresAt <= :now
        """
    )
    fun expireIfDue(@Param("id") id: UUID, @Param("now") now: Instant): Int

    @Modifying
    @Query(
        """
        UPDATE PriceAlert a
        SET a.status = 'FIRED',
            a.firedAt = :firedAt,
            a.firedPrice = :firedPrice,
            a.firedPriceAsOf = :firedPriceAsOf,
            a.updatedAt = :firedAt
        WHERE a.id = :id AND a.status = 'ACTIVE'
        """
    )
    fun fireIfActive(
        @Param("id") id: UUID,
        @Param("firedAt") firedAt: Instant,
        @Param("firedPrice") firedPrice: BigDecimal,
        @Param("firedPriceAsOf") firedPriceAsOf: Instant,
    ): Int

    @Modifying
    @Query(
        """
        UPDATE PriceAlert a
        SET a.status = 'CANCELLED',
            a.cancelledAt = :now,
            a.cancelReason = :reason,
            a.updatedAt = :now
        WHERE a.holdingId = :holdingId AND a.status = 'ACTIVE'
        """
    )
    fun cancelActiveForHolding(
        @Param("holdingId") holdingId: UUID,
        @Param("reason") reason: AlertCancelReason,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Query(
        """
        UPDATE PriceAlert a
        SET a.deliveryAttempts = a.deliveryAttempts + 1,
            a.deliveryLastAttemptAt = :now,
            a.updatedAt = :now
        WHERE a.id = :id
          AND a.status = 'FIRED'
          AND a.deliverySentAt IS NULL
          AND a.deliveryAttempts < :maxAttempts
          AND (a.deliveryLastAttemptAt IS NULL OR a.deliveryLastAttemptAt <= :retryBefore)
        """
    )
    fun claimDeliveryAttempt(
        @Param("id") id: UUID,
        @Param("maxAttempts") maxAttempts: Int,
        @Param("retryBefore") retryBefore: Instant,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Query(
        """
        UPDATE PriceAlert a
        SET a.deliverySentAt = :sentAt,
            a.deliveryLastError = NULL,
            a.updatedAt = :sentAt
        WHERE a.id = :id
        """
    )
    fun markDelivered(@Param("id") id: UUID, @Param("sentAt") sentAt: Instant): Int

    @Modifying
    @Query(
        """
        UPDATE PriceAlert a
        SET a.deliveryLastError = :error,
            a.updatedAt = :now
        WHERE a.id = :id
        """
    )
    fun recordDeliveryError(@Param("id") id: UUID, @Param("error") error: String, @Param("now") now: Instant): Int

    @Modifying
    @Query(value = "DELETE FROM price_alerts WHERE holding_id = :holdingId", nativeQuery = true)
    fun hardDeleteAllByHoldingId(@Param("holdingId") holdingId: UUID): Int

    @Modifying
    @Query(value = "DELETE FROM price_alerts WHERE household_id = :householdId", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("householdId") householdId: UUID): Int
}
