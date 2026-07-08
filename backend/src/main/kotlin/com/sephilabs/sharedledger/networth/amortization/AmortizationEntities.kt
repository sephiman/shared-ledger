package com.sephilabs.sharedledger.networth.amortization

import com.sephilabs.sharedledger.common.SoftDeletableEntity
import com.sephilabs.sharedledger.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Amortization methods. Validated in the service layer (no DB CHECK constraint). French is the default. */
enum class AmortizationMethod {
    /** Constant instalment (interest + principal); the classic French mortgage. */
    french,

    /** Constant principal component; the instalment decreases over time (German / linear). */
    german,

    /** Instalment is interest only; the whole principal is due at the end (bullet). */
    interest_only,

    /** No interest; the principal is repaid in equal instalments. */
    zero,
}

/** How a part's schedule is anchored in time. */
enum class StartMode {
    /** Outstanding balance at [AmortizationPart.startDate] → project forward. No past history. */
    current_balance,

    /** Original principal at the origin [AmortizationPart.startDate] → full schedule incl. past. */
    origin,
}

/** How a recorded prepayment reshapes the remaining schedule. */
enum class PrepaymentMode {
    /** Keep the instalment; the loan finishes earlier. */
    reduce_term,

    /** Keep the term; the instalment drops. */
    reduce_instalment,
}

/**
 * One part of an amortizable liability. A liability's total instalment is the sum of its parts
 * (a real Florius mortgage has two parts under a unified instalment). The starting point is the
 * current state ([originalPrincipal] at [startDate]); the schedule projects forward.
 */
@Entity
@Table(name = "amortization_parts")
@SQLRestriction("deleted_at IS NULL")
class AmortizationPart(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "liability_id", nullable = false)
    var liabilityId: UUID,

    @Column(name = "label", length = 120)
    var label: String? = null,

    @Column(name = "method", nullable = false, length = 24)
    @Enumerated(EnumType.STRING)
    var method: AmortizationMethod = AmortizationMethod.french,

    @Column(name = "original_principal", nullable = false, precision = 15, scale = 2)
    var originalPrincipal: BigDecimal,

    @Column(name = "annual_rate", nullable = false, precision = 8, scale = 4)
    var annualRate: BigDecimal = BigDecimal.ZERO,

    @Column(name = "term_months")
    var termMonths: Int? = null,

    // For French, an instalment supplied instead of a term (the calculator derives the missing one).
    @Column(name = "instalment", precision = 15, scale = 2)
    var instalment: BigDecimal? = null,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "start_mode", nullable = false, length = 24)
    @Enumerated(EnumType.STRING)
    var startMode: StartMode = StartMode.current_balance,

    // Re-anchor (origin mode): the real outstanding balance at [anchorDate]; the schedule reprojects from there.
    @Column(name = "anchor_date")
    var anchorDate: LocalDate? = null,

    @Column(name = "anchor_balance", precision = 15, scale = 2)
    var anchorBalance: BigDecimal? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()

/** A manual rate revision (date + new annual rate), possibly back-dated; the schedule recalculates from it. */
@Entity
@Table(name = "amortization_rate_revisions")
@SQLRestriction("deleted_at IS NULL")
class AmortizationRateRevision(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "part_id", nullable = false)
    var partId: UUID,

    @Column(name = "effective_date", nullable = false)
    var effectiveDate: LocalDate,

    @Column(name = "annual_rate", nullable = false, precision = 8, scale = 4)
    var annualRate: BigDecimal,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()

/** A recorded (real) prepayment against a part, reshaping the remaining schedule per [mode]. */
@Entity
@Table(name = "amortization_prepayments")
@SQLRestriction("deleted_at IS NULL")
class AmortizationPrepayment(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "part_id", nullable = false)
    var partId: UUID,

    @Column(name = "prepayment_date", nullable = false)
    var prepaymentDate: LocalDate,

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    var amount: BigDecimal,

    @Column(name = "mode", nullable = false, length = 24)
    @Enumerated(EnumType.STRING)
    var mode: PrepaymentMode = PrepaymentMode.reduce_term,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()

/**
 * An instalment charged by the monthly job: the exact generated history (date, interest split,
 * resulting balance), write-once. Idempotency comes from the unique (part_id, charge_date) index.
 */
@Entity
@Table(name = "amortization_entries")
class AmortizationEntry(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "part_id", nullable = false)
    var partId: UUID,

    @Column(name = "charge_date", nullable = false)
    var chargeDate: LocalDate,

    @Column(name = "interest", nullable = false, precision = 15, scale = 2)
    var interest: BigDecimal,

    @Column(name = "principal", nullable = false, precision = 15, scale = 2)
    var principal: BigDecimal,

    @Column(name = "resulting_balance", nullable = false, precision = 15, scale = 2)
    var resultingBalance: BigDecimal,
) : TimestampedEntity()
