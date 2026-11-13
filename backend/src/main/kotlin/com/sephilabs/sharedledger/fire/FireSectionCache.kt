package com.sephilabs.sharedledger.fire

import com.sephilabs.sharedledger.observability.AppMetrics
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** Results of the FIRE sections that are expensive to compute but change only with their inputs. The key carries
 *  the inputs themselves (settings, a fingerprint of the household's data, today's date), so a change to any of
 *  them is a miss — nothing has to remember to evict. */
@Component
class FireSectionCache(
    private val settingsRepository: FireSettingsRepository,
    private val metrics: AppMetrics,
) {

    private data class Key(
        val householdId: UUID,
        val section: String,
        val variant: String,
        val settings: FireSettingsDto,
        val dataFingerprint: String,
        val today: LocalDate,
    )

    private val entries = object : LinkedHashMap<Key, Any>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Any>?): Boolean = size > MAX_ENTRIES
    }

    fun <T : Any> getOrCompute(
        householdId: UUID,
        section: String,
        variant: String,
        settings: FireSettingsDto,
        compute: () -> T,
    ): T {
        val key = Key(householdId, section, variant, settings, settingsRepository.dataFingerprint(householdId), LocalDate.now())
        synchronized(entries) { entries[key] }?.let {
            metrics.fireSectionCache(section, "hit")
            @Suppress("UNCHECKED_CAST") // a key's section fixes the value's type
            return it as T
        }
        metrics.fireSectionCache(section, "miss")
        val value = compute()
        synchronized(entries) { entries[key] = value }
        return value
    }

    private companion object {
        const val MAX_ENTRIES = 64
    }
}
