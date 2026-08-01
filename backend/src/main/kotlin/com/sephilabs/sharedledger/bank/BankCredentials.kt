package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/**
 * The Enable Banking API application a household signs its provider calls with (one row per
 * household), shaped like [com.sephilabs.sharedledger.notification.TelegramSettings]: owner-only,
 * secret held as AES-GCM ciphertext (see [com.sephilabs.sharedledger.bank.connector.BankCrypto]),
 * never returned by the API. [appId] is not secret — it is the JWT `kid`, echoed back so a
 * connection authorized under a different application is detectable (see `BankConnection.appId`).
 */
@Entity
@Table(name = "bank_credentials")
class BankCredentials(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "app_id", nullable = false, length = 128)
    var appId: String,

    @Column(name = "private_key_enc", nullable = false)
    var privateKeyEnc: String,

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : TimestampedEntity()

interface BankCredentialsRepository : JpaRepository<BankCredentials, UUID> {
    fun findByHouseholdId(householdId: UUID): BankCredentials?

    @Modifying
    @Query(value = "DELETE FROM bank_credentials WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}
