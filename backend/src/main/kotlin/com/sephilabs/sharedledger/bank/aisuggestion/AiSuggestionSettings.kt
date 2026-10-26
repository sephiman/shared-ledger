package com.sephilabs.sharedledger.bank.aisuggestion

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** Per-household opt-in for AI category suggestions. Off by default; no row reads as off. */
@Entity
@Table(name = "ai_suggestion_settings")
class AiSuggestionSettings(
    @Id
    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false,

    @Column(name = "updated_by_user_id")
    var updatedByUserId: UUID? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

interface AiSuggestionSettingsRepository : JpaRepository<AiSuggestionSettings, UUID> {

    @Modifying
    @Query(value = "DELETE FROM ai_suggestion_settings WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}
