package com.sephilabs.sharedledger.bank

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
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

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

interface BankConnectionAccountRepository : JpaRepository<BankConnectionAccount, UUID> {
    fun findAllByConnectionId(connectionId: UUID): List<BankConnectionAccount>
    fun findByConnectionIdAndAccountUid(connectionId: UUID, accountUid: String): BankConnectionAccount?
}
