package com.sephilabs.sharedledger.networth.cash

import com.sephilabs.sharedledger.common.SoftDeletableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Who wrote an adjustment: a member, or the daily linked-bank anchor ([BankCashService]). */
enum class CashAdjustmentSource { manual, bank }

/** One dated cash adjustment ("at the close of day X I had Y") — the only persisted truth for cash,
 *  mirroring an asset's value series; between adjustments cash is estimated (see [CashEstimateService]).
 *  End-of-day convention: an adjustment dated D means the close of D, so only flows strictly after D
 *  adjust the estimate. Same-day adjustments tie-break by [created_at]. Bank-sourced rows have no author. */
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

    @Column(name = "created_by_user_id")
    var createdByUserId: UUID?,

    @Column(name = "updated_by_user_id")
    var updatedByUserId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 8)
    var source: CashAdjustmentSource = CashAdjustmentSource.manual,
) : SoftDeletableEntity()

/** Per-household toggles for which flow types feed the cash estimate, configured in the Cash sub-tab. All
 *  on by default; a user who captures a flow type incompletely can turn it off and lean on adjustments. */
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

    // Cash outside the linked banks (wallet, unlinked accounts), added to the daily bank anchor.
    @Column(name = "other_cash", nullable = false, precision = 15, scale = 2)
    var otherCash: BigDecimal = BigDecimal.ZERO,

    @Column(name = "updated_by_user_id")
    var updatedByUserId: UUID? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
