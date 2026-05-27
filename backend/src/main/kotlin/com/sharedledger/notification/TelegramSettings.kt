package com.sharedledger.notification

import com.sharedledger.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Per-household Telegram notification configuration (one row per household).
 *
 * [botTokenEnc] holds AES-GCM ciphertext (see [TelegramCrypto]); it is never exposed
 * through the API. The per-entity toggles each cover create/update/delete for that
 * entity; [notifyRecurringTxn] / [notifyRecurringLoan] cover scheduler materialization.
 */
@Entity
@Table(name = "telegram_settings")
class TelegramSettings(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "notify_transactions", nullable = false)
    var notifyTransactions: Boolean = true,

    @Column(name = "notify_snapshots", nullable = false)
    var notifySnapshots: Boolean = true,

    @Column(name = "notify_movements", nullable = false)
    var notifyMovements: Boolean = true,

    @Column(name = "notify_loan_payments", nullable = false)
    var notifyLoanPayments: Boolean = true,

    @Column(name = "notify_recurring_txn", nullable = false)
    var notifyRecurringTxn: Boolean = true,

    @Column(name = "notify_recurring_loan", nullable = false)
    var notifyRecurringLoan: Boolean = true,

    @Column(name = "chat_id", length = 64)
    var chatId: String? = null,

    @Column(name = "bot_token_enc")
    var botTokenEnc: String? = null,

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    var createdByUserId: UUID,

    @Column(name = "updated_by_user_id", nullable = false)
    var updatedByUserId: UUID,
) : TimestampedEntity() {

    /** Whether outgoing notifications for [entity] are currently enabled (master + per-entity). */
    fun isEnabledFor(entity: NotifyEntity): Boolean {
        if (!active) return false
        return when (entity) {
            NotifyEntity.TRANSACTION -> notifyTransactions
            NotifyEntity.SNAPSHOT -> notifySnapshots
            NotifyEntity.MOVEMENT -> notifyMovements
            NotifyEntity.LOAN_PAYMENT -> notifyLoanPayments
            NotifyEntity.RECURRING_TXN -> notifyRecurringTxn
            NotifyEntity.RECURRING_LOAN -> notifyRecurringLoan
        }
    }

    /** True once a destination is fully configured (token + chat). */
    fun isDeliverable(): Boolean = !botTokenEnc.isNullOrBlank() && !chatId.isNullOrBlank()
}

interface TelegramSettingsRepository : JpaRepository<TelegramSettings, UUID> {
    fun findByHouseholdId(householdId: UUID): TelegramSettings?
}
