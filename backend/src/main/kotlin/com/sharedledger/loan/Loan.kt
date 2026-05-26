package com.sharedledger.loan

import com.sharedledger.common.SoftDeletableEntity
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

enum class InterestType { none, simple, compound }
enum class CompoundingPeriod { monthly, yearly }
enum class LoanStatus { active, settled, written_off }
enum class LoanFrequency { weekly, monthly, yearly }

@Entity
@Table(name = "loans")
@SQLRestriction("deleted_at IS NULL")
class Loan(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false)
    var householdId: UUID,

    @Column(name = "borrower_name", nullable = false, length = 120)
    var borrowerName: String,

    @Column(name = "principal_amount", nullable = false, precision = 15, scale = 2)
    var principalAmount: BigDecimal,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "description", length = 500)
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_type", nullable = false, length = 16)
    var interestType: InterestType,

    @Column(name = "annual_interest_rate", precision = 8, scale = 4)
    var annualInterestRate: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "compounding_period", length = 16)
    var compoundingPeriod: CompoundingPeriod? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: LoanStatus = LoanStatus.active,

    @Column(name = "closed_date")
    var closedDate: LocalDate? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()
