package com.sephilabs.sharedledger.bank.sync

import com.sephilabs.sharedledger.bank.BankConnectionAccount
import com.sephilabs.sharedledger.bank.BankConnectionAccountRepository
import com.sephilabs.sharedledger.bank.BankFxConverter
import com.sephilabs.sharedledger.bank.connector.BankBalance
import com.sephilabs.sharedledger.bank.connector.BankConnector
import com.sephilabs.sharedledger.bank.connector.EbCredentials
import com.sephilabs.sharedledger.bank.connector.PsuContext
import com.sephilabs.sharedledger.bank.connector.RateLimitExceededException
import com.sephilabs.sharedledger.bank.connector.SessionClosedException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Published once fresh account balances are stored, so cash can re-anchor on them. */
data class BankBalancesRefreshed(val householdId: UUID)

/** Reads each account's balance on its first successful sync of the day. One call per account per day,
 *  kept outside the transactions budget; a balance the bank refuses never fails the sync. */
@Component
class AccountBalanceRefresher(
    private val connector: BankConnector,
    private val accounts: BankConnectionAccountRepository,
    private val fx: BankFxConverter,
    private val events: ApplicationEventPublisher,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)

    private data class ReadBalance(val accountId: UUID, val balance: BankBalance, val amountBase: BigDecimal, val on: LocalDate)

    fun refreshIfDue(
        householdId: UUID,
        creds: EbCredentials,
        sessionId: String,
        candidates: List<BankConnectionAccount>,
        psu: PsuContext?,
        today: LocalDate,
    ) {
        val due = candidates.filter { it.balanceFetchedOn != today }
        if (due.isEmpty()) return
        val read = readAll(creds, sessionId, due, psu, today)
        if (read.isEmpty()) return
        tx.executeWithoutResult {
            read.forEach { r ->
                accounts.findById(r.accountId).ifPresent {
                    it.balanceAmount = r.balance.amount
                    it.balanceCurrency = r.balance.currency
                    it.balanceAmountBase = r.amountBase
                    it.balanceType = r.balance.type
                    it.balanceDate = r.on
                    it.balanceFetchedOn = today
                }
            }
        }
        log.info("bank_balances_refreshed household={} accounts={}/{}", householdId, read.size, due.size)
        try {
            events.publishEvent(BankBalancesRefreshed(householdId))
        } catch (ex: Exception) {
            log.error("bank_balances_listener_failed household={}", householdId, ex)
        }
    }

    // Provider HTTP and FX lookups stay out of any transaction, as in the movement sync.
    private fun readAll(
        creds: EbCredentials,
        sessionId: String,
        due: List<BankConnectionAccount>,
        psu: PsuContext?,
        today: LocalDate,
    ): List<ReadBalance> {
        val read = mutableListOf<ReadBalance>()
        for (account in due) {
            try {
                val balance = BankBalance.preferred(connector.fetchBalances(creds, sessionId, account.accountUid, psu))
                if (balance == null) {
                    log.info("bank_balance_none account={}", account.id)
                    continue
                }
                val on = balance.validOn(today)
                read += ReadBalance(account.id, balance, fx.toBase(balance.amount, balance.currency, on), on)
            } catch (_: RateLimitExceededException) {
                log.warn("bank_balance_rate_limited account={}", account.id)
                break
            } catch (_: SessionClosedException) {
                log.warn("bank_balance_session_closed account={}", account.id)
                break
            } catch (ex: Exception) {
                log.warn("bank_balance_failed account={} error='{}'", account.id, ex.message)
            }
        }
        return read
    }
}
