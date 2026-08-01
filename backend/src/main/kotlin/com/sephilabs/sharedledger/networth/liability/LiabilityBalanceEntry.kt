package com.sephilabs.sharedledger.networth.liability

import com.sephilabs.sharedledger.common.SoftDeletableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** One dated balance in a liability's manual balance series (the source of truth for its outstanding
 *  amount). Amortizable liabilities compute their balance instead. */
@Entity
@Table(name = "liability_balance_entries")
@SQLRestriction("deleted_at IS NULL")
class LiabilityBalanceEntry(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "liability_id", nullable = false)
    var liabilityId: UUID,

    @Column(name = "balance_date", nullable = false)
    var balanceDate: LocalDate,

    @Column(name = "balance", nullable = false, precision = 15, scale = 2)
    var balance: BigDecimal,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : SoftDeletableEntity()
