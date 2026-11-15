package com.sephilabs.sharedledger.networth.cash

import com.sephilabs.sharedledger.bank.BankConnection
import com.sephilabs.sharedledger.bank.BankConnectionAccount
import com.sephilabs.sharedledger.bank.BankConnectionAccountRepository
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.sync.BankBalancesRefreshed
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

/** One linked account as cash sees it. It counts when marked so, not paused and holding a balance. */
data class BankCashAccount(
    val account: BankConnectionAccount,
    val connection: BankConnection,
) {
    val paused: Boolean get() = !account.ingestionEnabled || !connection.ingestionEnabled
    val counted: Boolean get() = account.countsAsCash && !paused && account.balanceAmountBase != null
}

data class BankCashOverview(
    val accounts: List<BankCashAccount>,
    val bankTotal: BigDecimal,
    val otherCash: BigDecimal,
) {
    val total: BigDecimal get() = Money.normalize(bankTotal + otherCash)
}

/** Linked-bank cash: the counted accounts' latest balances plus the household's free other cash are
 *  written as a `bank` cash adjustment, dated at the freshest balance. Rewritten whenever balances,
 *  the counted set or other cash change, so the estimate re-anchors on the bank once a day. */
@Service
class BankCashService(
    private val connections: BankConnectionRepository,
    private val accounts: BankConnectionAccountRepository,
    private val adjustments: CashAdjustmentRepository,
    private val estimates: CashEstimateService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun overview(householdId: UUID): BankCashOverview {
        val linked = linkedAccounts(householdId)
        return BankCashOverview(
            accounts = linked,
            bankTotal = bankTotal(linked),
            otherCash = estimates.getOrCreateSettings(householdId).otherCash,
        )
    }

    @Transactional
    fun setOtherCash(householdId: UUID, amount: BigDecimal, by: User): BankCashOverview {
        val settings = estimates.getOrCreateSettings(householdId)
        settings.otherCash = Money.normalize(amount)
        settings.updatedByUserId = by.id
        log.info("cash_other_cash_updated household={}", householdId)
        refreshAnchor(householdId)
        return overview(householdId)
    }

    @Transactional
    fun setCountsAsCash(householdId: UUID, accountId: UUID, countsAsCash: Boolean): BankCashOverview {
        val target = linkedAccounts(householdId).firstOrNull { it.account.id == accountId }
            ?: throw AppException.notFound("BANK_ACCOUNT_NOT_FOUND")
        target.account.countsAsCash = countsAsCash
        log.info("cash_bank_account_counted account={} counts={}", accountId, countsAsCash)
        refreshAnchor(householdId)
        return overview(householdId)
    }

    @EventListener
    @Transactional
    fun onBalancesRefreshed(event: BankBalancesRefreshed) = refreshAnchor(event.householdId)

    /** Upserts the bank anchor. It never moves backwards: when the freshest counted balance is older than
     *  the latest anchor (an account was just un-counted), that latest anchor is recomputed in place. */
    @Transactional
    fun refreshAnchor(householdId: UUID) {
        val linked = linkedAccounts(householdId)
        val counted = linked.filter { it.counted }
        val latest = adjustments.findFirstByHouseholdIdAndSourceOrderByAdjustmentDateDescCreatedAtDesc(
            householdId, CashAdjustmentSource.bank,
        )
        val freshest = counted.mapNotNull { it.account.balanceDate }.maxOrNull()
        val date = listOfNotNull(freshest, latest?.adjustmentDate).maxOrNull() ?: return
        val amount = Money.normalize(bankTotal(linked) + estimates.getOrCreateSettings(householdId).otherCash)

        val existing = adjustments.findFirstByHouseholdIdAndAdjustmentDateAndSource(householdId, date, CashAdjustmentSource.bank)
        if (existing == null) {
            adjustments.save(
                CashAdjustment(
                    householdId = householdId,
                    adjustmentDate = date,
                    amount = amount,
                    createdByUserId = null,
                    updatedByUserId = null,
                    source = CashAdjustmentSource.bank,
                ),
            )
            log.info("cash_bank_anchor_created household={} date={} accounts={}", householdId, date, counted.size)
        } else if (existing.amount.compareTo(amount) != 0) {
            existing.amount = amount
            log.info("cash_bank_anchor_updated household={} date={} accounts={}", householdId, date, counted.size)
        }
    }

    private fun linkedAccounts(householdId: UUID): List<BankCashAccount> {
        val byId = connections.findAllByHouseholdIdOrderByCreatedAtAsc(householdId).associateBy { it.id }
        if (byId.isEmpty()) return emptyList()
        return accounts.findAllByConnectionIdIn(byId.keys)
            .sortedBy { it.createdAt }
            .map { BankCashAccount(it, byId.getValue(it.connectionId)) }
    }

    private fun bankTotal(linked: List<BankCashAccount>): BigDecimal =
        Money.normalize(
            linked.filter { it.counted }.mapNotNull { it.account.balanceAmountBase }.fold(BigDecimal.ZERO, BigDecimal::add),
        )
}
