package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One linked bank authorization for a household. [sessionIdEnc] holds the AES-GCM ciphertext of the
 * provider session id (see BankCrypto) and is never exposed through the API. Sync state (cursor,
 * status, expiry, call budget) is per-connection so a failing or expired connection never blocks
 * the others.
 */
@Entity
@Table(name = "bank_connections")
class BankConnection(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "provider", nullable = false, length = 24)
    var provider: String = "enable_banking",

    @Column(name = "aspsp_name", nullable = false, length = 120)
    var aspspName: String,

    @Column(name = "aspsp_country", nullable = false, length = 2)
    var aspspCountry: String,

    @Column(name = "label", length = 120)
    var label: String? = null,

    @Column(name = "holder_user_id")
    var holderUserId: UUID? = null,

    @Column(name = "session_id_enc")
    var sessionIdEnc: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: ConnectionStatus = ConnectionStatus.active,

    @Column(name = "consent_expires_at")
    var consentExpiresAt: Instant? = null,

    @Column(name = "last_synced_at")
    var lastSyncedAt: Instant? = null,

    @Column(name = "ingestion_enabled", nullable = false)
    var ingestionEnabled: Boolean = true,

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_frequency", nullable = false, length = 16)
    var syncFrequency: SyncFrequency = SyncFrequency.twice_daily,

    @Column(name = "calls_used_today", nullable = false)
    var callsUsedToday: Int = 0,

    @Column(name = "calls_reset_on")
    var callsResetOn: LocalDate? = null,

    // Set when a background sync hits ASPSP_RATE_LIMIT_EXCEEDED; the scheduler skips the connection
    // until this instant passes. Null = not backing off. Interactive syncs ignore it.
    @Column(name = "sync_backoff_until")
    var syncBackoffUntil: Instant? = null,

    @Column(name = "created_by_user_id")
    var createdByUserId: UUID? = null,

    @Column(name = "updated_by_user_id")
    var updatedByUserId: UUID? = null,
) : TimestampedEntity()

interface BankConnectionRepository : JpaRepository<BankConnection, UUID> {

    /** Hard-delete every connection of the household. bank_connection_accounts, bank_sync_runs and any
     *  remaining pending_movements are removed via ON DELETE CASCADE. */
    @Modifying
    @Query(value = "DELETE FROM bank_connections WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findByIdAndHouseholdId(id: UUID, householdId: UUID): BankConnection?
    fun findAllByHouseholdIdOrderByCreatedAtAsc(householdId: UUID): List<BankConnection>
    fun countByHouseholdId(householdId: UUID): Long
    fun findAllByIngestionEnabledTrue(): List<BankConnection>
    fun findAllByStatus(status: ConnectionStatus): List<BankConnection>
}
