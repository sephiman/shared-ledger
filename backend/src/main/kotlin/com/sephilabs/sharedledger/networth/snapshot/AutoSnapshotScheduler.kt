package com.sephilabs.sharedledger.networth.snapshot

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Fires the auto-snapshot job once a day. Each enabled household's frequency decides
 * whether today is a due date; the job itself is idempotent (skips dates that already
 * have a snapshot), so a same-day restart never double-creates. One try/catch inside
 * runForAll isolates per-household failures.
 */
@Component
class AutoSnapshotScheduler(private val autoSnapshots: AutoSnapshotService) {

    private val log = LoggerFactory.getLogger(AutoSnapshotScheduler::class.java)

    @Scheduled(cron = "\${app.auto-snapshot.cron}", zone = "\${app.scheduler.timezone}")
    fun run() {
        try {
            autoSnapshots.runForAll(LocalDate.now())
        } catch (ex: Exception) {
            log.error("Auto-snapshot scheduler run failed", ex)
        }
    }
}
