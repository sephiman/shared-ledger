package com.sephilabs.sharedledger.portfolio.alert

import com.sephilabs.sharedledger.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

const val MAX_ACTIVE_ALERTS_PER_HOUSEHOLD = 50
const val MAX_ALERT_DELIVERY_ATTEMPTS = 3

// Bounds retries so rapid scheduled jobs (e.g. crypto :05 + intraday equity :10) or back-to-back
// manual refreshes never burn all three delivery attempts in the same minute.
val ALERT_DELIVERY_RETRY_MIN_SPACING: Duration = Duration.ofMinutes(15)

enum class AlertDirection { ABOVE, BELOW }

enum class AlertStatus { ACTIVE, FIRED, EXPIRED, CANCELLED }

enum class AlertCancelReason { USER, POSITION_CLOSED }

@Entity
@Table(name = "price_alerts")
class PriceAlert(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "holding_id", nullable = false, updatable = false)
    var holdingId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    var direction: AlertDirection,

    @Column(name = "threshold", nullable = false, precision = 28, scale = 12)
    var threshold: BigDecimal,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String,

    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    @Column(name = "note", length = 200)
    var note: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: AlertStatus = AlertStatus.ACTIVE,

    @Column(name = "fired_at")
    var firedAt: Instant? = null,

    @Column(name = "fired_price", precision = 28, scale = 12)
    var firedPrice: BigDecimal? = null,

    @Column(name = "fired_price_as_of")
    var firedPriceAsOf: Instant? = null,

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 24)
    var cancelReason: AlertCancelReason? = null,

    @Column(name = "delivery_sent_at")
    var deliverySentAt: Instant? = null,

    @Column(name = "delivery_attempts", nullable = false)
    var deliveryAttempts: Int = 0,

    @Column(name = "delivery_last_attempt_at")
    var deliveryLastAttemptAt: Instant? = null,

    @Column(name = "delivery_last_error")
    var deliveryLastError: String? = null,

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    var createdByUserId: UUID,
) : TimestampedEntity()
