package com.sephilabs.sharedledger.household.admin

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.bank.BankAuthSession
import com.sephilabs.sharedledger.bank.BankAuthSessionRepository
import com.sephilabs.sharedledger.bank.BankConnection
import com.sephilabs.sharedledger.bank.BankConnectionAccount
import com.sephilabs.sharedledger.bank.BankConnectionAccountRepository
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.CategorizationRule
import com.sephilabs.sharedledger.bank.CategorizationRuleRepository
import com.sephilabs.sharedledger.bank.PendingMovement
import com.sephilabs.sharedledger.bank.PendingMovementRepository
import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.budget.Budget
import com.sephilabs.sharedledger.budget.BudgetRepository
import com.sephilabs.sharedledger.catalog.CustomCategoryEntity
import com.sephilabs.sharedledger.catalog.CustomCategoryId
import com.sephilabs.sharedledger.catalog.CustomCategoryRepository
import com.sephilabs.sharedledger.fire.FireSettings
import com.sephilabs.sharedledger.fire.FireSettingsRepository
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.lending.InterestType
import com.sephilabs.sharedledger.lending.Lending
import com.sephilabs.sharedledger.lending.LendingRepository
import com.sephilabs.sharedledger.networth.amortization.AmortizationEntry
import com.sephilabs.sharedledger.networth.amortization.AmortizationEntryRepository
import com.sephilabs.sharedledger.networth.amortization.AmortizationPart
import com.sephilabs.sharedledger.networth.amortization.AmortizationPartRepository
import com.sephilabs.sharedledger.networth.asset.Asset
import com.sephilabs.sharedledger.networth.asset.AssetRepository
import com.sephilabs.sharedledger.networth.asset.AssetValueEntry
import com.sephilabs.sharedledger.networth.asset.AssetValueEntryRepository
import com.sephilabs.sharedledger.networth.liability.Liability
import com.sephilabs.sharedledger.networth.liability.LiabilityBalanceEntry
import com.sephilabs.sharedledger.networth.liability.LiabilityBalanceEntryRepository
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.networth.movement.MovementRepository
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.networth.movement.NetWorthMovement
import com.sephilabs.sharedledger.networth.snapshot.AutoSnapshotSettings
import com.sephilabs.sharedledger.networth.snapshot.AutoSnapshotSettingsRepository
import com.sephilabs.sharedledger.networth.snapshot.Snapshot
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValue
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValueId
import com.sephilabs.sharedledger.networth.snapshot.SnapshotLiabilityBalance
import com.sephilabs.sharedledger.networth.snapshot.SnapshotLiabilityBalanceId
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import com.sephilabs.sharedledger.notification.TelegramSettings
import com.sephilabs.sharedledger.notification.TelegramSettingsRepository
import com.sephilabs.sharedledger.portfolio.Holding
import com.sephilabs.sharedledger.portfolio.HoldingAssetClass
import com.sephilabs.sharedledger.portfolio.HoldingLot
import com.sephilabs.sharedledger.portfolio.HoldingLotRepository
import com.sephilabs.sharedledger.portfolio.HoldingRepository
import com.sephilabs.sharedledger.recurring.Cadence
import com.sephilabs.sharedledger.recurring.RecurringTemplate
import com.sephilabs.sharedledger.recurring.RecurringTemplateRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.Transaction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class HouseholdDataWipePersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val transactions: TransactionRepository,
    private val snapshots: SnapshotRepository,
    private val movements: MovementRepository,
    private val recurringTemplates: RecurringTemplateRepository,
    private val budgets: BudgetRepository,
    private val customCategories: CustomCategoryRepository,
    private val holdings: HoldingRepository,
    private val holdingLots: HoldingLotRepository,
    private val assets: AssetRepository,
    private val assetValues: AssetValueEntryRepository,
    private val liabilities: LiabilityRepository,
    private val liabilityBalances: LiabilityBalanceEntryRepository,
    private val amortizationParts: AmortizationPartRepository,
    private val amortizationEntries: AmortizationEntryRepository,
    private val lendings: LendingRepository,
    private val bankConnections: BankConnectionRepository,
    private val bankAccounts: BankConnectionAccountRepository,
    private val pendingMovements: PendingMovementRepository,
    private val categorizationRules: CategorizationRuleRepository,
    private val bankAuthSessions: BankAuthSessionRepository,
    private val fireSettings: FireSettingsRepository,
    private val telegramSettings: TelegramSettingsRepository,
    private val autoSnapshotSettings: AutoSnapshotSettingsRepository,
    private val service: HouseholdDataWipeService,
) : IntegrationTestBase() {

    @PersistenceContext
    private lateinit var em: EntityManager

    /** Every household-scoped root table, keyed by its own `household_id` column. */
    private val rootTables = listOf(
        "transactions", "net_worth_movements", "snapshots",
        "recurring_templates", "budgets", "custom_categories",
        "holdings", "assets", "liabilities", "lendings",
        "pending_movements", "bank_categorization_rules", "bank_auth_sessions", "bank_connections",
        "fire_settings", "telegram_settings", "auto_snapshot_settings",
    )

    @Test
    fun `wipe removes every household-scoped entity (incl cascade children and soft-deleted rows) for the household only`() {
        val (user, target) = seed("wp-a")
        val (_, other) = seed("wp-b")

        val seeded = seedAll(target, user)
        // Other household gets the same shape, to prove scoping is by household_id, not global.
        seedAll(other, user)

        // Sanity: everything is present before the wipe.
        rootTables.forEach { assertThat(countRows(it, target.id)).describedAs("$it before wipe").isPositive }

        service.wipe(target.id, user.id)

        // Target household: every root table empty (native counts see soft-deleted rows too).
        rootTables.forEach { assertThat(countRows(it, target.id)).describedAs("$it after wipe").isZero }

        // Cascade children are gone as well (queried by their seeded parent id, independent of the parent row).
        assertThat(countChild("holding_lots", "holding_id", seeded.holdingId)).isZero
        assertThat(countChild("asset_value_entries", "asset_id", seeded.assetId)).isZero
        assertThat(countChild("liability_balance_entries", "liability_id", seeded.liabilityId)).isZero
        assertThat(countChild("amortization_parts", "liability_id", seeded.liabilityId)).isZero
        assertThat(countChild("amortization_entries", "part_id", seeded.amortizationPartId)).isZero
        assertThat(countChild("bank_connection_accounts", "connection_id", seeded.connectionId)).isZero
        assertThat(countSnapshotAssetValues(target.id)).isZero
        assertThat(countSnapshotLiabilityBalances(target.id)).isZero

        // Other household is completely untouched.
        rootTables.forEach { assertThat(countRows(it, other.id)).describedAs("$it (other household)").isPositive }
    }

    /** Seeds one row in every household-scoped table (plus cascade children) and returns the parent ids. */
    private fun seedAll(household: Household, user: User): SeededIds {
        // Transactions: one live + one soft-deleted, to prove the native wipe removes soft-deleted rows too.
        transactions.save(sampleTx(household.id, user.id))
        transactions.save(sampleTx(household.id, user.id).also { it.deletedAt = Instant.now() })
        movements.save(sampleMovement(household.id, user.id))

        val liability = liabilities.save(Liability(
            householdId = household.id, name = "Mortgage ${System.nanoTime()}",
            createdByUserId = user.id, updatedByUserId = user.id,
        ))
        liabilityBalances.save(LiabilityBalanceEntry(
            liabilityId = liability.id, balanceDate = LocalDate.of(2025, 1, 1), balance = BigDecimal("1000.00"),
            createdByUserId = user.id, updatedByUserId = user.id,
        ))
        val part = amortizationParts.save(AmortizationPart(
            liabilityId = liability.id, originalPrincipal = BigDecimal("1000.00"),
            startDate = LocalDate.of(2025, 1, 1), createdByUserId = user.id, updatedByUserId = user.id,
        ))
        amortizationEntries.save(AmortizationEntry(
            partId = part.id, chargeDate = LocalDate.of(2025, 2, 1),
            interest = BigDecimal("5.00"), principal = BigDecimal("95.00"), resultingBalance = BigDecimal("905.00"),
        ))

        val snapshot = Snapshot(
            householdId = household.id, snapshotDate = LocalDate.of(2025, 4, 1),
            createdByUserId = user.id, updatedByUserId = user.id,
        )
        snapshot.assetValues.add(SnapshotAssetValue(SnapshotAssetValueId(snapshot.id, "etfs"), BigDecimal("1000.00")))
        snapshot.liabilityBalances.add(SnapshotLiabilityBalance(SnapshotLiabilityBalanceId(snapshot.id, liability.id), BigDecimal("250.00")))
        snapshots.save(snapshot)

        val asset = assets.save(Asset(
            householdId = household.id, name = "House ${System.nanoTime()}",
            createdByUserId = user.id, updatedByUserId = user.id,
        ))
        assetValues.save(AssetValueEntry(
            assetId = asset.id, valueDate = LocalDate.of(2025, 1, 1), value = BigDecimal("200000.00"),
            createdByUserId = user.id, updatedByUserId = user.id,
        ))

        val holding = holdings.save(Holding(
            householdId = household.id, assetClass = HoldingAssetClass.etf, symbol = "VWCE",
            nativeCurrency = "EUR", createdByUserId = user.id, updatedByUserId = user.id,
        ))
        holdingLots.save(HoldingLot(
            holdingId = holding.id, tradedOn = LocalDate.of(2025, 1, 1),
            quantity = BigDecimal("10"), unitPrice = BigDecimal("100"), currency = "EUR",
            fxRateToBase = BigDecimal.ONE, createdByUserId = user.id, updatedByUserId = user.id,
        ))

        lendings.save(Lending(
            householdId = household.id, borrowerName = "Alice", principalAmount = BigDecimal("500.00"),
            startDate = LocalDate.of(2025, 1, 1), interestType = InterestType.none,
            createdByUserId = user.id, updatedByUserId = user.id,
        ))
        budgets.save(Budget(
            householdId = household.id, year = 2025, month = 4, categoryCode = "groceries.groceries",
            amount = BigDecimal("300.00"), createdByUserId = user.id, updatedByUserId = user.id,
        ))
        recurringTemplates.save(RecurringTemplate(
            householdId = household.id, direction = Direction.expense, categoryCode = "groceries.groceries",
            amount = BigDecimal("50.00"), cadence = Cadence.monthly, dayOfMonth = 1, startDate = LocalDate.of(2025, 1, 1),
            createdByUserId = user.id, updatedByUserId = user.id,
        ))
        customCategories.save(CustomCategoryEntity(
            id = CustomCategoryId(household.id, "custom.${System.nanoTime()}"),
            name = "Custom", kind = "expense", groupCode = "groceries", createdByUserId = user.id,
        ))

        val connection = bankConnections.save(BankConnection(
            householdId = household.id, aspspName = "Bank", aspspCountry = "ES",
        ))
        val account = bankAccounts.save(BankConnectionAccount(
            connectionId = connection.id, accountUid = "acc-${System.nanoTime()}",
        ))
        pendingMovements.save(PendingMovement(
            householdId = household.id, connectionId = connection.id, accountId = account.id,
            bankMovementId = "bm-${System.nanoTime()}", bookingDate = LocalDate.of(2025, 1, 1),
            direction = Direction.expense, amount = BigDecimal("12.34"),
        ))
        categorizationRules.save(CategorizationRule(
            householdId = household.id, matchField = RuleField.counterparty, matchOp = RuleOp.contains,
            matchValue = "MERCADONA", categoryCode = "groceries.groceries", direction = Direction.expense,
        ))
        bankAuthSessions.save(BankAuthSession(
            state = "state-${System.nanoTime()}", householdId = household.id, aspspName = "Bank", aspspCountry = "ES",
        ))

        fireSettings.save(FireSettings(householdId = household.id))
        telegramSettings.save(TelegramSettings(
            householdId = household.id, createdByUserId = user.id, updatedByUserId = user.id,
        ))
        autoSnapshotSettings.save(AutoSnapshotSettings(householdId = household.id))

        return SeededIds(
            holdingId = holding.id, assetId = asset.id, liabilityId = liability.id,
            amortizationPartId = part.id, connectionId = connection.id,
        )
    }

    private data class SeededIds(
        val holdingId: UUID,
        val assetId: UUID,
        val liabilityId: UUID,
        val amortizationPartId: UUID,
        val connectionId: UUID,
    )

    private fun countRows(table: String, householdId: UUID): Long {
        val q = em.createNativeQuery("SELECT COUNT(*) FROM $table WHERE household_id = :hid")
        q.setParameter("hid", householdId)
        return (q.singleResult as Number).toLong()
    }

    private fun countChild(table: String, fkColumn: String, parentId: UUID): Long {
        val q = em.createNativeQuery("SELECT COUNT(*) FROM $table WHERE $fkColumn = :pid")
        q.setParameter("pid", parentId)
        return (q.singleResult as Number).toLong()
    }

    private fun countSnapshotAssetValues(householdId: UUID): Long {
        val q = em.createNativeQuery("""
            SELECT COUNT(*) FROM snapshot_asset_values v
            WHERE v.snapshot_id IN (SELECT id FROM snapshots WHERE household_id = :hid)
        """.trimIndent())
        q.setParameter("hid", householdId)
        return (q.singleResult as Number).toLong()
    }

    private fun countSnapshotLiabilityBalances(householdId: UUID): Long {
        val q = em.createNativeQuery("""
            SELECT COUNT(*) FROM snapshot_liability_balances b
            WHERE b.snapshot_id IN (SELECT id FROM snapshots WHERE household_id = :hid)
        """.trimIndent())
        q.setParameter("hid", householdId)
        return (q.singleResult as Number).toLong()
    }

    private fun sampleTx(householdId: UUID, userId: UUID) = Transaction(
        householdId = householdId,
        occurrenceDate = LocalDate.of(2025, 1, 15),
        direction = Direction.expense,
        categoryCode = "groceries.groceries",
        amount = BigDecimal("12.34"),
        description = null,
        createdByUserId = userId,
        updatedByUserId = userId,
    )

    private fun sampleMovement(householdId: UUID, userId: UUID) = NetWorthMovement(
        householdId = householdId,
        movementDate = LocalDate.of(2025, 3, 1),
        type = MovementType.contribution,
        assetClassCode = "etfs",
        liabilityId = null,
        amount = BigDecimal("500.00"),
        description = null,
        createdByUserId = userId,
        updatedByUserId = userId,
    )

    private fun seed(prefix: String): Pair<User, Household> {
        val user = users.save(User(email = "$prefix-${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H-$prefix", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
