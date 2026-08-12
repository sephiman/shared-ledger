package com.sephilabs.sharedledger.notification

import com.sephilabs.sharedledger.lending.LendingPayment
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.networth.movement.NetWorthMovement
import com.sephilabs.sharedledger.networth.snapshot.SnapshotDto
import com.sephilabs.sharedledger.recurring.RecurringTemplate
import com.sephilabs.sharedledger.transaction.Transaction
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/** Builds the immutable mobile-card field snapshot for each notifiable change and publishes a domain event.
 *  Called inside the mutation's transaction and read synchronously, so nothing JPA-managed crosses to the
 *  async listener. CSV import flows MUST NOT call this — that is how imports stay silent. */
@Component
class NotificationPublisher(
    private val events: ApplicationEventPublisher,
    private val liabilities: LiabilityRepository,
) {

    fun transaction(tx: Transaction, action: NotifyAction, actor: NotifyActor) {
        events.publishEvent(
            EntityChangeEvent(
                householdId = tx.householdId,
                entity = NotifyEntity.TRANSACTION,
                action = action,
                actor = actor,
                fields = listOf(
                    CardField("common.category", FieldValue.Category(tx.categoryCode)),
                    CardField("common.direction", FieldValue.Keyed("common.${tx.direction.name}")),
                    CardField("common.amount", FieldValue.Money(tx.amount)),
                    CardField("common.date", FieldValue.Day(tx.occurrenceDate)),
                    CardField("common.description", FieldValue.Text(tx.description)),
                ),
            ),
        )
    }

    fun snapshot(dto: SnapshotDto, householdId: UUID, action: NotifyAction, actor: NotifyActor) {
        events.publishEvent(
            EntityChangeEvent(
                householdId = householdId,
                entity = NotifyEntity.SNAPSHOT,
                action = action,
                actor = actor,
                fields = listOf(
                    CardField("networth.snapshot_date", FieldValue.Day(dto.snapshotDate)),
                    CardField("networth.snapshot_note", FieldValue.Text(dto.note)),
                    CardField("networth.total_assets", FieldValue.Money(dto.totalAssets)),
                    CardField("networth.total_liabilities", FieldValue.Money(dto.totalLiabilities)),
                    CardField("networth.net_worth", FieldValue.Money(dto.netWorth)),
                ),
            ),
        )
    }

    fun movement(m: NetWorthMovement, action: NotifyAction, actor: NotifyActor) {
        val target: FieldValue = when (m.type) {
            MovementType.contribution, MovementType.withdrawal ->
                FieldValue.Keyed("asset.${m.assetClassCode}")
            MovementType.debt_payment ->
                FieldValue.Text(m.liabilityId?.let { id -> liabilities.findById(id).map { it.name }.orElse(null) })
        }
        events.publishEvent(
            EntityChangeEvent(
                householdId = m.householdId,
                entity = NotifyEntity.MOVEMENT,
                action = action,
                actor = actor,
                fields = listOf(
                    CardField("common.date", FieldValue.Day(m.movementDate)),
                    CardField("networth.movement_type", FieldValue.Keyed("networth.${m.type.name}")),
                    CardField("notif.field.target", target),
                    CardField("common.amount", FieldValue.Money(m.amount)),
                    CardField("common.description", FieldValue.Text(m.description)),
                ),
            ),
        )
    }

    /** A portfolio trade. [typeName] is the LotType name ("BUY"/"SELL"), [amountBase] the cost or proceeds in
     *  base currency. Holding lifecycle is intentionally not notified, and the CSV importer never calls this. */
    fun holdingTrade(
        householdId: UUID,
        symbol: String,
        typeName: String,
        quantity: BigDecimal,
        unitPrice: BigDecimal,
        unitCurrency: String,
        tradedOn: java.time.LocalDate,
        amountBase: BigDecimal,
        action: NotifyAction,
        actor: NotifyActor,
    ) {
        events.publishEvent(
            EntityChangeEvent(
                householdId = householdId,
                entity = NotifyEntity.HOLDING,
                action = action,
                actor = actor,
                fields = listOf(
                    CardField("portfolio.trade", FieldValue.Keyed("portfolio.lot_type.$typeName")),
                    CardField("portfolio.symbol", FieldValue.Text(symbol)),
                    CardField("portfolio.quantity", FieldValue.Text(quantity.stripTrailingZeros().toPlainString())),
                    // Unit price is in the lot's own currency (may differ from the base), so it
                    // carries its currency inline rather than being formatted as base money.
                    CardField(
                        "portfolio.unit_price",
                        FieldValue.Text("${unitPrice.stripTrailingZeros().toPlainString()} $unitCurrency"),
                    ),
                    CardField("common.date", FieldValue.Day(tradedOn)),
                    CardField("common.amount", FieldValue.Money(amountBase)),
                ),
            ),
        )
    }

    fun lendingPayment(
        payment: LendingPayment,
        householdId: UUID,
        borrowerName: String,
        action: NotifyAction,
        actor: NotifyActor,
    ) {
        events.publishEvent(
            EntityChangeEvent(
                householdId = householdId,
                entity = NotifyEntity.LENDING_PAYMENT,
                action = action,
                actor = actor,
                fields = listOf(
                    CardField("lendings.borrower_name", FieldValue.Text(borrowerName)),
                    CardField("lendings.payment_date", FieldValue.Day(payment.paymentDate)),
                    CardField("lendings.amount", FieldValue.Money(payment.amount)),
                    CardField("lendings.description", FieldValue.Text(payment.description)),
                ),
            ),
        )
    }

    fun recurringTransactions(template: RecurringTemplate, count: Int, actor: NotifyActor) {
        if (count <= 0) return
        events.publishEvent(
            MaterializationEvent(
                householdId = template.householdId,
                entity = NotifyEntity.RECURRING_TXN,
                count = count,
                actor = actor,
                fields = listOf(
                    CardField("common.category", FieldValue.Category(template.categoryCode)),
                    CardField("common.direction", FieldValue.Keyed("common.${template.direction.name}")),
                    CardField("common.amount", FieldValue.Money(template.amount)),
                    CardField("recurring.cadence", FieldValue.Keyed("recurring.${template.cadence.name}")),
                    CardField("common.description", FieldValue.Text(template.description)),
                ),
            ),
        )
    }

    /** One aggregated message after a batch confirm of N pending movements. Single confirms go through
     *  [transaction] instead, so this only fires for batches — mirroring the recurring summary. */
    fun bankMovementsConfirmed(householdId: UUID, count: Int, actor: NotifyActor) {
        if (count <= 0) return
        events.publishEvent(
            MaterializationEvent(
                householdId = householdId,
                entity = NotifyEntity.BANK_MOVEMENT,
                count = count,
                actor = actor,
                fields = emptyList(),
            ),
        )
    }

    /** One aggregated message for a split instead of N creation cards. Its own entity only so the header
     *  can say what happened; same `notify_bank_movements` toggle as the confirm summary. */
    fun bankMovementSplit(householdId: UUID, count: Int, total: BigDecimal, actor: NotifyActor) {
        if (count <= 0) return
        events.publishEvent(
            MaterializationEvent(
                householdId = householdId,
                entity = NotifyEntity.BANK_MOVEMENT_SPLIT,
                count = count,
                actor = actor,
                fields = listOf(CardField("common.amount", FieldValue.Money(total))),
            ),
        )
    }

    /** One aggregated message for a merge, whose single transaction would otherwise announce itself as an
     *  ordinary create and hide that N movements went into it. [total] is the summed amount. */
    fun bankMovementsMerged(householdId: UUID, count: Int, total: BigDecimal, actor: NotifyActor) {
        if (count <= 0) return
        events.publishEvent(
            MaterializationEvent(
                householdId = householdId,
                entity = NotifyEntity.BANK_MOVEMENT_MERGE,
                count = count,
                actor = actor,
                fields = listOf(CardField("common.amount", FieldValue.Money(total))),
            ),
        )
    }

    /** Heads-up after a sync ingests N new movements, before any confirm. Same `notify_bank_movements` toggle
     *  as the confirm summary; uses a CREATE change event so its header ("new to review") stays distinct. */
    fun bankMovementsToReview(
        householdId: UUID,
        count: Int,
        bankName: String,
        label: String?,
        actor: NotifyActor,
    ) {
        if (count <= 0) return
        events.publishEvent(
            EntityChangeEvent(
                householdId = householdId,
                entity = NotifyEntity.BANK_MOVEMENT,
                action = NotifyAction.CREATE,
                actor = actor,
                fields = listOf(
                    // Name the source bank so the reader can tell which connection synced these.
                    CardField("banks.bank", FieldValue.Text(bankName)),
                    CardField("banks.connection", FieldValue.Text(label)),
                    CardField("banks.new_movements", FieldValue.Text(count.toString())),
                ),
            ),
        )
    }

    /** Re-link reminder for a connection whose consent is about to expire (per connection). */
    fun bankConnectionExpiring(
        householdId: UUID,
        bankName: String,
        label: String?,
        expiresOn: java.time.LocalDate,
        actor: NotifyActor,
    ) {
        events.publishEvent(
            EntityChangeEvent(
                householdId = householdId,
                entity = NotifyEntity.BANK_CONNECTION,
                action = NotifyAction.UPDATE,
                actor = actor,
                fields = listOf(
                    CardField("banks.bank", FieldValue.Text(bankName)),
                    CardField("banks.connection", FieldValue.Text(label)),
                    CardField("banks.expires", FieldValue.Day(expiresOn)),
                ),
            ),
        )
    }

    /** A recorded prepayment against an amortizable liability. Reuses the LENDING_PAYMENT event/toggle — the
     *  requirement is to emit events the bot already consumes, not add a subsystem. */
    fun amortizationPrepayment(
        householdId: UUID,
        liabilityName: String,
        amount: BigDecimal,
        newBalance: BigDecimal,
        date: java.time.LocalDate,
        actor: NotifyActor,
    ) {
        events.publishEvent(
            EntityChangeEvent(
                householdId = householdId,
                entity = NotifyEntity.LENDING_PAYMENT,
                action = NotifyAction.CREATE,
                actor = actor,
                fields = listOf(
                    CardField("networth.liability_name", FieldValue.Text(liabilityName)),
                    CardField("common.date", FieldValue.Day(date)),
                    CardField("networth.prepayment_amount", FieldValue.Money(amount)),
                    CardField("networth.new_balance", FieldValue.Money(newBalance)),
                ),
            ),
        )
    }

    /** The monthly instalments charged by the amortization job for one liability. Reuses the RECURRING_LENDING
     *  materialization event/toggle. */
    fun amortizationInstalments(
        householdId: UUID,
        liabilityName: String,
        instalment: BigDecimal,
        count: Int,
        actor: NotifyActor,
    ) {
        if (count <= 0) return
        events.publishEvent(
            MaterializationEvent(
                householdId = householdId,
                entity = NotifyEntity.RECURRING_LENDING,
                count = count,
                actor = actor,
                fields = listOf(
                    CardField("networth.liability_name", FieldValue.Text(liabilityName)),
                    CardField("networth.instalment", FieldValue.Money(instalment)),
                ),
            ),
        )
    }

    fun recurringLendingPayments(
        householdId: UUID,
        borrowerName: String,
        expectedAmount: BigDecimal,
        count: Int,
        actor: NotifyActor,
    ) {
        if (count <= 0) return
        events.publishEvent(
            MaterializationEvent(
                householdId = householdId,
                entity = NotifyEntity.RECURRING_LENDING,
                count = count,
                actor = actor,
                fields = listOf(
                    CardField("lendings.borrower_name", FieldValue.Text(borrowerName)),
                    CardField("lendings.expected_amount", FieldValue.Money(expectedAmount)),
                ),
            ),
        )
    }
}
