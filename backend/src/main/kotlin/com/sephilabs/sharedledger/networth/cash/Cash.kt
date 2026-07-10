package com.sephilabs.sharedledger.networth.cash

import com.sephilabs.sharedledger.common.SoftDeletableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One dated cash adjustment ("at the close of day X I had Y") — the source of truth for cash,
 * mirroring an asset's value series. Between adjustments cash is *estimated* on demand from the
 * marked flows (see [CashEstimateService]); this series is the only persisted truth.
 *
 * End-of-day convention: an adjustment dated D means cash at the close of day D, so only flows
 * dated strictly after D adjust the estimate. Multiple adjustments on the same day tie-break by
 * [created_at] (last created wins).
 */
@Entity
@Table(name = "cash_adjustments")
@SQLRestriction("deleted_at IS NULL")
class CashAdjustment(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false)
    var householdId: UUID,

    @Column(name = "adjustment_date", nullable = false)
    var adjustmentDate: LocalDate,

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    var amount: BigDecimal,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()

/**
 * Per-household toggles for which flow types feed the cash estimate. Configured in the Cash
 * sub-tab (not general settings). All on by default; a user who captures a flow type incompletely
 * can turn it off and lean on manual adjustments.
 */
@Entity
@Table(name = "cash_estimate_settings")
class CashEstimateSettings(
    @Id
    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "include_transactions", nullable = false)
    var includeTransactions: Boolean = true,

    @Column(name = "include_lendings", nullable = false)
    var includeLendings: Boolean = true,

    @Column(name = "include_movements", nullable = false)
    var includeMovements: Boolean = true,

    @Column(name = "updated_by_user_id")
    var updatedByUserId: UUID? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
