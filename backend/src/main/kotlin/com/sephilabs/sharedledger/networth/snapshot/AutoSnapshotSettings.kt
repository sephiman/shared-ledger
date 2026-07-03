package com.sephilabs.sharedledger.networth.snapshot

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class SnapshotFrequency { daily, weekly, monthly }

/** Per-household opt-in configuration for the scheduled snapshot job. */
@Entity
@Table(name = "auto_snapshot_settings")
class AutoSnapshotSettings(
    @Id
    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 16)
    var frequency: SnapshotFrequency = SnapshotFrequency.monthly,

    @Column(name = "updated_by_user_id")
    var updatedByUserId: UUID? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
