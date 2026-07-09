package com.sephilabs.sharedledger.bank

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

/**
 * Short-lived record tying a redirect `state` to the household/holder that started a link, so the
 * callback cannot bind an authorization to a different household. Consumed (deleted) on completion.
 */
@Entity
@Table(name = "bank_auth_sessions")
class BankAuthSession(
    @Id
    @Column(name = "state", nullable = false, updatable = false, length = 64)
    var state: String,

    @Column(name = "household_id", nullable = false)
    var householdId: UUID,

    @Column(name = "holder_user_id")
    var holderUserId: UUID? = null,

    @Column(name = "aspsp_name", nullable = false, length = 120)
    var aspspName: String,

    @Column(name = "aspsp_country", nullable = false, length = 2)
    var aspspCountry: String,

    @Column(name = "label", length = 120)
    var label: String? = null,

    @Column(name = "relink_connection_id")
    var relinkConnectionId: UUID? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

interface BankAuthSessionRepository : JpaRepository<BankAuthSession, String> {

    @Modifying
    @Query(value = "DELETE FROM bank_auth_sessions WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}
