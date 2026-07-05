package com.sephilabs.sharedledger.bank

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/** Per-connection sync audit row: outcome + how many new movements were ingested. */
@Entity
@Table(name = "bank_sync_runs")
class BankSyncRun(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "connection_id", nullable = false, updatable = false)
    var connectionId: UUID,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: SyncRunStatus = SyncRunStatus.success,

    @Column(name = "new_movements", nullable = false)
    var newMovements: Int = 0,

    @Column(name = "error_code", length = 64)
    var errorCode: String? = null,

    @Column(name = "error_message", length = 500)
    var errorMessage: String? = null,
)

interface BankSyncRunRepository : JpaRepository<BankSyncRun, UUID> {
    fun findFirstByConnectionIdOrderByStartedAtDesc(connectionId: UUID): BankSyncRun?
}
