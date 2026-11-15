package com.sephilabs.sharedledger.bank

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** An account exposed by a connection's authorization. One connection can grant several. */
@Entity
@Table(name = "bank_connection_accounts")
class BankConnectionAccount(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "connection_id", nullable = false, updatable = false)
    var connectionId: UUID,

    @Column(name = "account_uid", nullable = false, length = 128)
    var accountUid: String,

    @Column(name = "iban_masked", length = 64)
    var ibanMasked: String? = null,

    @Column(name = "name", length = 120)
    var name: String? = null,

    @Column(name = "currency", length = 3)
    var currency: String? = null,

    @Column(name = "identification_hash")
    var identificationHash: String? = null,

    // Paused accounts are skipped by sync before any provider call; resuming backfills via the usual window.
    @Column(name = "ingestion_enabled", nullable = false)
    var ingestionEnabled: Boolean = true,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    // Latest balance as the bank reported it, plus its base-currency value; refreshed once a day.
    @Column(name = "balance_amount", precision = 15, scale = 2)
    var balanceAmount: BigDecimal? = null,

    @Column(name = "balance_currency", length = 3)
    var balanceCurrency: String? = null,

    @Column(name = "balance_amount_base", precision = 15, scale = 2)
    var balanceAmountBase: BigDecimal? = null,

    @Column(name = "balance_type", length = 8)
    var balanceType: String? = null,

    // The day the balance is valid at (the bank's reference date, or our best reading of it).
    @Column(name = "balance_date")
    var balanceDate: LocalDate? = null,

    @Column(name = "balance_fetched_on")
    var balanceFetchedOn: LocalDate? = null,

    @Column(name = "counts_as_cash", nullable = false)
    var countsAsCash: Boolean = true,
)

interface BankConnectionAccountRepository : JpaRepository<BankConnectionAccount, UUID> {
    fun findAllByConnectionId(connectionId: UUID): List<BankConnectionAccount>
    fun findAllByConnectionIdIn(connectionIds: Collection<UUID>): List<BankConnectionAccount>
}
