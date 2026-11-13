package com.sephilabs.sharedledger.fire

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.movement.MovementRepository
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.networth.movement.NetWorthMovement
import com.sephilabs.sharedledger.networth.snapshot.Snapshot
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValue
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValueId
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRequest
import com.sephilabs.sharedledger.transaction.TransactionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class FireReferenceAndNamesTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val snapshots: SnapshotRepository,
    private val movements: MovementRepository,
    private val transactions: TransactionService,
    private val fireService: FireService,
    private val objectMapper: ObjectMapper,
) : IntegrationTestBase() {

    @Test
    fun `unsaved household defaults to FIRE tier, middle scenario, and named scenarios`() {
        val (h, _) = setupHousehold()
        val dto = fireService.settingsDto(h.id)

        assertThat(dto.referenceTier).isEqualTo(FireTierKey.fire)
        assertThat(dto.referenceScenarioIndex).isEqualTo(1)
        assertThat(dto.returnScenarios.map { it.name }).containsExactly("Conservative", "Base", "Optimistic")
        assertThat(dto.returnScenarios.map { it.meanPercent.toDouble() to it.stdDevPercent.toDouble() })
            .containsExactly(4.0 to 5.0, 6.5 to 10.0, 8.5 to 15.0)
        assertThat(dto.targetYear).isEqualTo(FireDefaults.defaultTargetYear())
    }

    @Test
    fun `default horizon rounds current year plus 25 to nearest multiple of 5 and typical compound return matches exp(mu_ln) - 1`() {
        assertThat(FireDefaults.defaultTargetYear(2026)).isEqualTo(2050.toShort())
        assertThat(FireDefaults.defaultTargetYear(2028)).isEqualTo(2055.toShort())
        // 6.5% arithmetic mean, 10% std dev => varianceRatio = (0.10/1.065)^2, exp(muLn) - 1 ≈ 6.03%
        val compound = FireDefaults.typicalCompoundReturn(0.065, 0.10)
        assertThat(compound).isBetween(0.0600, 0.0607)
    }

    @Test
    fun `deleting the reference scenario falls back to the middle-mean scenario (first on ties)`() {
        val (h, u) = setupHousehold()

        // Four scenarios with a tie at the middle mean (6.0): indices 1 and 2.
        fireService.update(
            h.id,
            baseRequest(
                scenarios = listOf(
                    ReturnScenarioInput(BigDecimal("4.0"), BigDecimal("5.0"), name = "Low"),
                    ReturnScenarioInput(BigDecimal("6.0"), BigDecimal("10.0"), name = "Base A"),
                    ReturnScenarioInput(BigDecimal("6.0"), BigDecimal("14.0"), name = "Base B"),
                    ReturnScenarioInput(BigDecimal("9.0"), BigDecimal("18.0"), name = "High"),
                ),
                referenceTier = FireTierKey.lean,
                referenceScenarioIndex = 3, // High
            ),
            u,
        )
        assertThat(fireService.settingsDto(h.id).referenceScenarioIndex).isEqualTo(3)

        // Now delete index 3 ("High"), leaving 3 scenarios [4.0, 6.0, 6.0] and passing the stale index 3.
        val updated = fireService.update(
            h.id,
            baseRequest(
                scenarios = listOf(
                    ReturnScenarioInput(BigDecimal("4.0"), BigDecimal("5.0"), name = "Low"),
                    ReturnScenarioInput(BigDecimal("6.0"), BigDecimal("10.0"), name = "Base A"),
                    ReturnScenarioInput(BigDecimal("6.0"), BigDecimal("14.0"), name = "Base B"),
                ),
                referenceTier = FireTierKey.lean,
                referenceScenarioIndex = 3,
            ),
            u,
        )

        // Middle mean is 6.0; first on ties is index 1 ("Base A").
        assertThat(updated.referenceScenarioIndex).isEqualTo(1)
        assertThat(updated.referenceTier).isEqualTo(FireTierKey.lean)
    }

    @Test
    fun `projection exposes scenario names and reference plan in settings`() {
        val (h, u) = setupHousehold()
        addSnapshot(h, u, LocalDate.now(), "100000.00")

        fireService.update(
            h.id,
            baseRequest(
                scenarios = listOf(
                    ReturnScenarioInput(BigDecimal("4.5"), BigDecimal("6.0"), name = "Cautious"),
                    ReturnScenarioInput(BigDecimal("7.0"), BigDecimal("12.0"), name = "Steady"),
                ),
                referenceTier = FireTierKey.fat,
                referenceScenarioIndex = 0,
            ),
            u,
        )

        val proj = fireService.project(h.id)
        assertThat(proj.settings.referenceTier).isEqualTo(FireTierKey.fat)
        assertThat(proj.settings.referenceScenarioIndex).isEqualTo(0)
        assertThat(proj.scenarios.map { it.name }).containsExactly("Cautious", "Steady")
    }

    @Test
    fun `migration columns and defaults exist on fire_settings`() {
        val cols = jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'fire_settings'",
            String::class.java,
        )
        assertThat(cols).contains("reference_tier", "reference_scenario_index")
    }

    @Test
    fun `engine output is deterministic and unaffected by reference tier, reference scenario, or scenario names`() {
        val (h, u) = setupHousehold()
        val today = LocalDate.now()
        addSnapshot(h, u, today.minusYears(1), "80000.00")
        addSnapshot(h, u, today, "120000.00")
        addMovement(h, u, today.minusMonths(6), MovementType.contribution, "20000.00")
        for (back in 0L..11L) {
            val m = YearMonth.now().minusMonths(back)
            addTx(h, u, m.atDay(1), "income.salary", "4000.00", Direction.income)
            addTx(h, u, m.atDay(2), "groceries.groceries", "1200.00", Direction.expense)
            addTx(h, u, m.atDay(3), "outings.restaurants", "800.00", Direction.expense)
        }

        for (taxOn in listOf(true, false)) {
            fireService.update(
                h.id,
                baseRequest(
                    scenarios = listOf(
                        ReturnScenarioInput(BigDecimal("4.0"), BigDecimal("0.0"), name = "Conservative"),
                        ReturnScenarioInput(BigDecimal("6.0"), BigDecimal("0.0"), name = "Base"),
                        ReturnScenarioInput(BigDecimal("8.0"), BigDecimal("0.0"), name = "Optimistic"),
                    ),
                    applyCapitalGainsTax = taxOn,
                    referenceTier = FireTierKey.fire,
                    referenceScenarioIndex = 1,
                ),
                u,
            )
            val beforeJson = stripNewFields(objectMapper.valueToTree(fireService.project(h.id)))
            val beforePreviewJson = stripNewFields(
                objectMapper.valueToTree(
                    fireService.project(
                        h.id,
                        FirePreviewRequest(monthlyContribution = BigDecimal("2500.00"), lumpSum = BigDecimal("10000.00")),
                    ),
                ),
            )

            // Change only reference tier, reference scenario, and scenario names.
            fireService.update(
                h.id,
                baseRequest(
                    scenarios = listOf(
                        ReturnScenarioInput(BigDecimal("4.0"), BigDecimal("0.0"), name = "Slow"),
                        ReturnScenarioInput(BigDecimal("6.0"), BigDecimal("0.0"), name = "Middle"),
                        ReturnScenarioInput(BigDecimal("8.0"), BigDecimal("0.0"), name = "Fast"),
                    ),
                    applyCapitalGainsTax = taxOn,
                    referenceTier = FireTierKey.lean,
                    referenceScenarioIndex = 2,
                ),
                u,
            )
            val afterJson = stripNewFields(objectMapper.valueToTree(fireService.project(h.id)))
            val afterPreviewJson = stripNewFields(
                objectMapper.valueToTree(
                    fireService.project(
                        h.id,
                        FirePreviewRequest(monthlyContribution = BigDecimal("2500.00"), lumpSum = BigDecimal("10000.00")),
                    ),
                ),
            )

            assertThat(afterJson).isEqualTo(beforeJson)
            assertThat(afterPreviewJson).isEqualTo(beforePreviewJson)
        }
    }

    private fun stripNewFields(node: JsonNode): JsonNode {
        val root = node.deepCopy() as ObjectNode
        (root.get("settings") as? ObjectNode)?.let { settings ->
            settings.remove("referenceTier")
            settings.remove("referenceScenarioIndex")
            settings.get("returnScenarios")?.forEach { (it as? ObjectNode)?.remove("name") }
        }
        root.get("scenarios")?.forEach { (it as? ObjectNode)?.remove("name") }
        return root
    }

    private fun baseRequest(
        scenarios: List<ReturnScenarioInput>,
        referenceTier: FireTierKey = FireTierKey.fire,
        referenceScenarioIndex: Int = 1,
        applyCapitalGainsTax: Boolean = true,
    ) = FireSettingsRequest(
        targetAmount = BigDecimal("0.00"),
        targetYear = 2045,
        monthlyContribution = BigDecimal("1500.00"),
        returnScenarios = scenarios,
        qualifyingAssetClasses = listOf("etfs"),
        expectedInflationPct = BigDecimal("2.0"),
        safeWithdrawalRatePct = BigDecimal("4.0"),
        fatMultiplier = BigDecimal("1.5"),
        contributionMode = ContributionMode.manual,
        applyCapitalGainsTax = applyCapitalGainsTax,
        fallbackGainFractionPct = BigDecimal("50.0"),
        taxBrackets = FireDefaults.SPANISH_SAVINGS_TAX_BRACKETS.map { TaxBracketInput(it.lowerBound, it.ratePct) },
        referenceTier = referenceTier,
        referenceScenarioIndex = referenceScenarioIndex,
    )

    private fun setupHousehold(): Pair<Household, User> {
        val user = users.save(User(email = "fr${System.nanoTime()}@example.com", passwordHash = "x"))
        val h = households.save(Household(name = "H", currency = "EUR"))
        return h to user
    }

    private fun addSnapshot(h: Household, u: User, date: LocalDate, etfsValue: String) {
        val snapshot = Snapshot(
            householdId = h.id,
            snapshotDate = date,
            createdByUserId = u.id,
            updatedByUserId = u.id,
        )
        snapshot.assetValues.add(SnapshotAssetValue(SnapshotAssetValueId(snapshot.id, "etfs"), BigDecimal(etfsValue)))
        snapshots.save(snapshot)
    }

    private fun addMovement(h: Household, u: User, date: LocalDate, type: MovementType, amount: String) {
        movements.save(
            NetWorthMovement(
                householdId = h.id,
                movementDate = date,
                type = type,
                assetClassCode = "etfs",
                amount = BigDecimal(amount),
                createdByUserId = u.id,
                updatedByUserId = u.id,
            ),
        )
    }

    private fun addTx(h: Household, u: User, date: LocalDate, cat: String, amount: String, dir: Direction) {
        transactions.create(
            h.id,
            TransactionRequest(
                occurrenceDate = date,
                direction = dir,
                categoryCode = cat,
                amount = BigDecimal(amount),
                description = null,
            ),
            u,
        )
    }
}
