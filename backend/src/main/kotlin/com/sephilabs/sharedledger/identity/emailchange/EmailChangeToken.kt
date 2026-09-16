package com.sephilabs.sharedledger.identity.emailchange

import com.sephilabs.sharedledger.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/** One mailed confirmation link, carrying the address the user asked to move to. Only the token's hash is
 *  stored; the raw token lives in the mail alone. */
@Entity
@Table(name = "email_change_tokens")
class EmailChangeToken(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: UUID,

    @Column(name = "new_email", nullable = false)
    var newEmail: String,

    @Column(name = "token_hash", nullable = false, unique = true)
    var tokenHash: String,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "used_at")
    var usedAt: Instant? = null,
) : TimestampedEntity() {

    fun isActive(now: Instant): Boolean = usedAt == null && expiresAt.isAfter(now)
}

interface EmailChangeTokenRepository : JpaRepository<EmailChangeToken, UUID> {

    fun findByTokenHash(tokenHash: String): EmailChangeToken?

    fun deleteAllByUserIdAndUsedAtIsNull(userId: UUID): Long
}
