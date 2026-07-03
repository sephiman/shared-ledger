package com.sephilabs.sharedledger.networth.snapshot

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AutoSnapshotSettingsRepository : JpaRepository<AutoSnapshotSettings, UUID> {
    fun findAllByEnabledTrue(): List<AutoSnapshotSettings>
}
