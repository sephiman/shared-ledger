package com.sephilabs.sharedledger.household.admin

import com.sephilabs.sharedledger.bank.BankAuthSessionRepository
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.CategorizationRuleRepository
import com.sephilabs.sharedledger.bank.PendingMovementRepository
import com.sephilabs.sharedledger.budget.BudgetRepository
import com.sephilabs.sharedledger.catalog.CustomCategoryRepository
import com.sephilabs.sharedledger.fire.FireSettingsRepository
import com.sephilabs.sharedledger.lending.LendingRepository
import com.sephilabs.sharedledger.networth.asset.AssetRepository
import com.sephilabs.sharedledger.networth.cash.CashAdjustmentRepository
import com.sephilabs.sharedledger.networth.cash.CashEstimateSettingsRepository
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.networth.movement.MovementRepository
import com.sephilabs.sharedledger.networth.snapshot.AutoSnapshotSettingsRepository
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import com.sephilabs.sharedledger.notification.TelegramSettingsRepository
import com.sephilabs.sharedledger.portfolio.HoldingRepository
import com.sephilabs.sharedledger.recurring.RecurringTemplateRepository
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Permanently deletes every piece of a household's financial data, leaving only the household
 * itself and its membership/invitations intact. Every root table is hard-deleted (native queries
 * that bypass @SQLRestriction so soft-deleted rows go too); child tables are cleaned up via their
 * `ON DELETE CASCADE` foreign keys (amortization parts/entries, liability & asset value series,
 * holding lots & valuations, lending schedules & payments, bank accounts & sync runs, snapshot rows).
 *
 * Delete order respects the foreign keys that are NOT cascading between root tables:
 *  - transactions before recurring_templates (transactions.recurring_template_id → recurring_templates)
 *  - snapshots and movements before liabilities/assets
 *    (snapshot_liability_balances / net_worth_movements → liabilities; snapshot asset rows → assets)
 *  - pending_movements / auth sessions before bank_connections.
 */
@Service
class HouseholdDataWipeService(
    private val transactions: TransactionRepository,
    private val snapshots: SnapshotRepository,
    private val movements: MovementRepository,
    private val recurringTemplates: RecurringTemplateRepository,
    private val budgets: BudgetRepository,
    private val customCategories: CustomCategoryRepository,
    private val holdings: HoldingRepository,
    private val assets: AssetRepository,
    private val liabilities: LiabilityRepository,
    private val lendings: LendingRepository,
    private val pendingMovements: PendingMovementRepository,
    private val categorizationRules: CategorizationRuleRepository,
    private val bankAuthSessions: BankAuthSessionRepository,
    private val bankConnections: BankConnectionRepository,
    private val fireSettings: FireSettingsRepository,
    private val telegramSettings: TelegramSettingsRepository,
    private val autoSnapshotSettings: AutoSnapshotSettingsRepository,
    private val cashAdjustments: CashAdjustmentRepository,
    private val cashEstimateSettings: CashEstimateSettingsRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun wipe(householdId: UUID, byUserId: UUID) {
        // 1. Transactions & net-worth ledger. Must precede recurring_templates / liabilities.
        val txCount = transactions.hardDeleteAllByHouseholdId(householdId)
        val movementCount = movements.hardDeleteAllByHouseholdId(householdId)
        val balanceCount = snapshots.deleteAllLiabilityBalancesByHouseholdId(householdId)
        val snapshotCount = snapshots.hardDeleteAllByHouseholdId(householdId)
        val cashAdjustmentCount = cashAdjustments.hardDeleteAllByHouseholdId(householdId)

        // 2. Planning / catalog data referencing transactions or category codes.
        val recurringCount = recurringTemplates.hardDeleteAllByHouseholdId(householdId)
        val budgetCount = budgets.hardDeleteAllByHouseholdId(householdId)
        val customCategoryCount = customCategories.hardDeleteAllByHouseholdId(householdId)

        // 3. Assets & liabilities (children cascade). Liabilities after snapshots/movements above.
        val holdingCount = holdings.hardDeleteAllByHouseholdId(householdId)
        val assetCount = assets.hardDeleteAllByHouseholdId(householdId)
        val liabilityCount = liabilities.hardDeleteAllByHouseholdId(householdId)
        val lendingCount = lendings.hardDeleteAllByHouseholdId(householdId)

        // 4. Bank ingestion. Pending movements & auth sessions before connections cascade the rest.
        val pendingCount = pendingMovements.hardDeleteAllByHouseholdId(householdId)
        val ruleCount = categorizationRules.hardDeleteAllByHouseholdId(householdId)
        val authSessionCount = bankAuthSessions.hardDeleteAllByHouseholdId(householdId)
        val connectionCount = bankConnections.hardDeleteAllByHouseholdId(householdId)

        // 5. Per-household settings.
        val fireCount = fireSettings.hardDeleteAllByHouseholdId(householdId)
        val telegramCount = telegramSettings.hardDeleteAllByHouseholdId(householdId)
        val autoSnapshotCount = autoSnapshotSettings.hardDeleteAllByHouseholdId(householdId)
        val cashSettingsCount = cashEstimateSettings.hardDeleteAllByHouseholdId(householdId)

        log.info(
            "household_data_wipe household={} by_user={} tx={} mv={} snap={} snap_liab_balances={} cash_adj={} " +
                "recurring={} budgets={} custom_categories={} holdings={} assets={} liabilities={} lendings={} " +
                "pending={} rules={} auth_sessions={} bank_connections={} fire={} telegram={} auto_snapshot={} cash_settings={}",
            householdId, byUserId, txCount, movementCount, snapshotCount, balanceCount, cashAdjustmentCount,
            recurringCount, budgetCount, customCategoryCount, holdingCount, assetCount, liabilityCount, lendingCount,
            pendingCount, ruleCount, authSessionCount, connectionCount, fireCount, telegramCount, autoSnapshotCount, cashSettingsCount,
        )
    }
}
