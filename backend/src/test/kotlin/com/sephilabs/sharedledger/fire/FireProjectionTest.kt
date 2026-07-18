package com.sephilabs.sharedledger.fire

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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class FireProjectionTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val snapshots: SnapshotRepository,
    private val movements: MovementRepository,
    private val transactions: TransactionService,
    private val fireService: FireService,
) : IntegrationTestBase() {

    // -------------------------------------------------------------------------------------
    // Deterministic engine

    @Test
    fun `deterministic series matches expected math with zero stddev`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "100000.00")

        fireService.update(
            household.id,
            request(targetAmount = "500000.00", targetYear = 2035, monthlyContribution = "1000.00"),
            user,
        )

        val result = fireService.project(household.id)
        val series = result.scenarios.first().series
        // 0% return, 0% inflation, 12k/year contributions, starting 100k, 10 years => 220k.
        assertThat(series.first().value).isEqualByComparingTo("100000.00")
        assertThat(series.last().value).isEqualByComparingTo("220000.00")
    }

    @Test
    fun `contribution indexation compounds contributions with inflation`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "0.00")

        fireService.update(
            household.id,
            request(
                targetYear = 2027,
                monthlyContribution = "100.00",
                inflation = "10.00",
                indexContribution = true,
                tierCustomEnabled = false,
            ),
            user,
        )
        // Year 1: 1200, year 2: 1200 × 1.1 = 1320 => 2520 at 0% return.
        val indexed = fireService.project(household.id).scenarios.first().series.last().value
        assertThat(indexed).isEqualByComparingTo("2520.00")

        fireService.update(
            household.id,
            request(
                targetYear = 2027,
                monthlyContribution = "100.00",
                inflation = "10.00",
                indexContribution = false,
                tierCustomEnabled = false,
            ),
            user,
        )
        val flat = fireService.project(household.id).scenarios.first().series.last().value
        assertThat(flat).isEqualByComparingTo("2400.00")
    }

    @Test
    fun `monte carlo percentiles are ordered and collapse when stddev is zero`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "100000.00")

        fireService.update(
            household.id,
            request(
                targetAmount = "1000000.00",
                targetYear = 2040,
                monthlyContribution = "500.00",
                scenarios = listOf(
                    ReturnScenarioInput(BigDecimal("5.0"), BigDecimal("0.0")),
                    ReturnScenarioInput(BigDecimal("7.0"), BigDecimal("15.0")),
                ),
            ),
            user,
        )

        val result = fireService.project(household.id)
        val deterministic = result.scenarios[0]
        val stochastic = result.scenarios[1]

        deterministic.percentiles.forEachIndexed { i, p ->
            assertThat(p.p10).isEqualByComparingTo(p.p25)
            assertThat(p.p25).isEqualByComparingTo(p.p50)
            assertThat(p.p50).isEqualByComparingTo(p.p75)
            assertThat(p.p75).isEqualByComparingTo(p.p90)
            assertThat(p.p50).isEqualByComparingTo(deterministic.series[i].value)
        }

        stochastic.percentiles.forEach { p ->
            assertThat(p.p10).isLessThanOrEqualTo(p.p25)
            assertThat(p.p25).isLessThanOrEqualTo(p.p50)
            assertThat(p.p50).isLessThanOrEqualTo(p.p75)
            assertThat(p.p75).isLessThanOrEqualTo(p.p90)
        }

        val customStats = deterministic.tierStats.single { it.tier == FireTierKey.custom }
        assertThat(customStats.probabilityOfReachingTarget).isEqualTo(0.0)
        val stochasticCustom = stochastic.tierStats.single { it.tier == FireTierKey.custom }
        assertThat(stochasticCustom.probabilityOfReachingTarget).isBetween(0.0, 1.0)
    }

    @Test
    fun `high mean and modest stddev gives high probability of hitting reachable target`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "500000.00")

        fireService.update(
            household.id,
            request(
                targetAmount = "750000.00",
                targetYear = 2035,
                monthlyContribution = "2000.00",
                scenarios = listOf(ReturnScenarioInput(BigDecimal("8.0"), BigDecimal("5.0"))),
            ),
            user,
        )

        val out = fireService.project(household.id).scenarios.single()
        val custom = out.tierStats.single { it.tier == FireTierKey.custom }
        assertThat(custom.probabilityOfReachingTarget).isGreaterThan(0.95)
        assertThat(custom.medianYearReachingTarget).isNotNull
        // Coast FIRE: 500k at 8% for 10 years comfortably clears 750k with no contributions.
        assertThat(custom.coastProbabilityOfReachingTarget).isGreaterThan(0.9)
    }

    @Test
    fun `no computable tier yields empty tier stats instead of zero probabilities`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "100000.00")

        // No transactions (no spending bases) and target amount 0: nothing to project against.
        fireService.update(household.id, request(targetAmount = "0.00", tierCustomEnabled = true), user)

        val result = fireService.project(household.id)
        assertThat(result.scenarios.first().tierStats).isEmpty()
        assertThat(result.tiers.single { it.key == FireTierKey.custom }.targetToday).isNull()
        assertThat(result.tiers.single { it.key == FireTierKey.lean }.targetToday).isNull()
    }

    // -------------------------------------------------------------------------------------
    // Real money-weighted return

    @Test
    fun `actual return is money weighted not contribution inflated`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2024, 1, 1), "10000.00")
        addMovement(household, user, LocalDate.of(2024, 7, 1), MovementType.contribution, "5000.00")
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "16000.00")

        fireService.update(household.id, request(), user)
        val result = fireService.project(household.id)

        // Naive growth would report 60%; the money-weighted return of
        // 10000(1+r) + 5000(1+r)^0.5 = 16000 is ~8%.
        assertThat(result.actualReturn).isNotNull
        assertThat(result.actualReturnUnavailableReason).isNull()
        val pct = result.actualReturn!!.annualizedPercent.toDouble()
        assertThat(pct).isBetween(7.0, 9.0)
        assertThat(result.actualReturn!!.movementCount).isEqualTo(1)
    }

    @Test
    fun `partial coverage is flagged when the first movement lags the first snapshot`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2024, 1, 1), "10000.00")
        addMovement(household, user, LocalDate.of(2024, 9, 1), MovementType.contribution, "5000.00")
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "16000.00")

        fireService.update(household.id, request(), user)
        val actual = fireService.project(household.id).actualReturn

        assertThat(actual).isNotNull
        assertThat(actual!!.firstMovementDate).isEqualTo(LocalDate.of(2024, 9, 1))
        assertThat(actual.uncoveredMonths).isEqualTo(8)
    }

    @Test
    fun `coverage is complete when the first movement is within the threshold of the first snapshot`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2024, 1, 1), "10000.00")
        addMovement(household, user, LocalDate.of(2024, 1, 20), MovementType.contribution, "5000.00")
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "16000.00")

        fireService.update(household.id, request(), user)
        val actual = fireService.project(household.id).actualReturn

        assertThat(actual).isNotNull
        assertThat(actual!!.uncoveredMonths).isEqualTo(0)
    }

    @Test
    fun `no movements means no return figure at all`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2024, 1, 1), "10000.00")
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "16000.00")

        fireService.update(household.id, request(), user)
        val result = fireService.project(household.id)

        assertThat(result.actualReturn).isNull()
        assertThat(result.actualReturnUnavailableReason).isEqualTo(FireActualReturnUnavailableReason.no_movements)
        assertThat(result.historicalScenario).isNull()
    }

    @Test
    fun `fewer than two snapshots cannot produce a return`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "10000.00")

        fireService.update(household.id, request(), user)
        val result = fireService.project(household.id)

        assertThat(result.actualReturn).isNull()
        assertThat(result.actualReturnUnavailableReason)
            .isEqualTo(FireActualReturnUnavailableReason.insufficient_snapshots)
    }

    @Test
    fun `historical scenario is available once a valid return exists`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2023, 1, 1), "10000.00")
        addMovement(household, user, LocalDate.of(2023, 6, 1), MovementType.contribution, "2000.00")
        addSnapshot(household, user, LocalDate.of(2024, 1, 1), "13000.00")
        addMovement(household, user, LocalDate.of(2024, 6, 1), MovementType.contribution, "2000.00")
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "16000.00")

        fireService.update(household.id, request(), user)
        val result = fireService.project(household.id)

        assertThat(result.actualReturn).isNotNull
        assertThat(result.historicalScenario).isNotNull
        assertThat(result.historicalScenario!!.yearsOfData).isGreaterThanOrEqualTo(2)
    }

    // -------------------------------------------------------------------------------------
    // Tiers from real spending

    @Test
    fun `tiers derive from essential and total spending`() {
        val (household, user) = setupHousehold()
        val today = LocalDate.now()
        addSnapshot(household, user, today, "45000.00")
        // 12 months of 300 essential (groceries) + 200 non-essential (restaurants).
        for (back in 0L..11L) {
            val month = YearMonth.now().minusMonths(back)
            addTx(household, user, month.atDay(1), "groceries.groceries", "300.00")
            addTx(household, user, month.atDay(2), "outings.restaurants", "200.00")
        }

        fireService.update(
            household.id,
            request(targetYear = (today.year + 10).toShort(), inflation = "2.00", tierCustomEnabled = false),
            user,
        )
        val result = fireService.project(household.id)

        assertThat(result.spending.essentialMonthly).isEqualByComparingTo("300.00")
        assertThat(result.spending.totalMonthly).isEqualByComparingTo("500.00")

        // SWR 4%: lean = 3600/0.04, fire = 6000/0.04, fat = 6000×1.5/0.04.
        val lean = result.tiers.single { it.key == FireTierKey.lean }
        val fire = result.tiers.single { it.key == FireTierKey.fire }
        val fat = result.tiers.single { it.key == FireTierKey.fat }
        assertThat(lean.targetToday).isEqualByComparingTo("90000.00")
        assertThat(fire.targetToday).isEqualByComparingTo("150000.00")
        assertThat(fat.targetToday).isEqualByComparingTo("225000.00")

        // Targets inflate at 2%/year on the chart curve.
        assertThat(lean.targetCurve[0].value).isEqualByComparingTo("90000.00")
        assertThat(lean.targetCurve[1].value).isEqualByComparingTo("91800.00")

        // Current coverage: 45000 of 90000 = 50%.
        assertThat(lean.coveragePercent!!).isBetween(49.9, 50.1)

        // Scenario stats exist for the three derived tiers only (custom is off).
        val statTiers = result.scenarios.first().tierStats.map { it.tier }
        assertThat(statTiers).containsExactlyInAnyOrder(FireTierKey.lean, FireTierKey.fire, FireTierKey.fat)
    }

    @Test
    fun `capital gains tax grosses up spending tiers`() {
        val (household, user) = setupHousehold()
        val today = LocalDate.now()
        addSnapshot(household, user, today, "45000.00")
        for (back in 0L..11L) {
            addTx(household, user, YearMonth.now().minusMonths(back).atDay(1), "groceries.groceries", "300.00")
        }

        // No movements: the gain fraction falls back to the manual estimate, set here to 100%.
        fireService.update(
            household.id,
            request(
                targetYear = (today.year + 10).toShort(),
                tierCustomEnabled = false,
                applyCapitalGainsTax = true,
                fallbackGainFractionPct = "100.00",
            ),
            user,
        )
        val result = fireService.project(household.id)

        assertThat(result.gainFraction.source).isEqualTo(FireService.GAIN_FRACTION_SOURCE_MANUAL)
        assertThat(result.gainFraction.percent).isEqualByComparingTo("100.00")

        // Gross-up in the 19% bracket: 3600 / 0.81 = 4444.44; target = 4444.44 / 0.04.
        val lean = result.tiers.single { it.key == FireTierKey.lean }
        assertThat(lean.annualGrossSpending).isEqualByComparingTo("4444.44")
        assertThat(lean.estimatedAnnualTax).isEqualByComparingTo("844.44")
        assertThat(lean.targetToday).isEqualByComparingTo("111111.11")
    }

    @Test
    fun `gain fraction derives from movements when available`() {
        val (household, user) = setupHousehold()
        addMovement(household, user, LocalDate.of(2024, 3, 1), MovementType.contribution, "35000.00")
        addMovement(household, user, LocalDate.of(2024, 9, 1), MovementType.withdrawal, "5000.00")
        addSnapshot(household, user, LocalDate.now(), "45000.00")

        fireService.update(household.id, request(), user)
        val result = fireService.project(household.id)

        // Net contributions 30000 against 45000 of qualifying wealth: g = 1/3.
        assertThat(result.gainFraction.source).isEqualTo(FireService.GAIN_FRACTION_SOURCE_MOVEMENTS)
        assertThat(result.gainFraction.percent).isEqualByComparingTo("33.33")
    }

    // -------------------------------------------------------------------------------------
    // Spending-base overrides

    @Test
    fun `manual total spending overrides fire and fat but not lean`() {
        val (household, user) = setupHousehold()
        val today = LocalDate.now()
        addSnapshot(household, user, today, "45000.00")
        for (back in 0L..11L) {
            val month = YearMonth.now().minusMonths(back)
            addTx(household, user, month.atDay(1), "groceries.groceries", "300.00")
            addTx(household, user, month.atDay(2), "outings.restaurants", "200.00")
        }

        fireService.update(
            household.id,
            request(
                targetYear = (today.year + 10).toShort(),
                tierCustomEnabled = false,
                totalSpendingMode = SpendingBaseMode.manual,
                manualTotalSpending = "1000.00",
            ),
            user,
        )
        val result = fireService.project(household.id)

        // The derived values stay visible; the effective total is the override.
        assertThat(result.spending.derivedEssentialMonthly).isEqualByComparingTo("300.00")
        assertThat(result.spending.derivedTotalMonthly).isEqualByComparingTo("500.00")
        assertThat(result.spending.essentialMonthly).isEqualByComparingTo("300.00")
        assertThat(result.spending.totalMonthly).isEqualByComparingTo("1000.00")

        // SWR 4%: lean keeps the derived essential base; fire/fat use the manual total.
        assertThat(result.tiers.single { it.key == FireTierKey.lean }.targetToday).isEqualByComparingTo("90000.00")
        assertThat(result.tiers.single { it.key == FireTierKey.fire }.targetToday).isEqualByComparingTo("300000.00")
        assertThat(result.tiers.single { it.key == FireTierKey.fat }.targetToday).isEqualByComparingTo("450000.00")
    }

    @Test
    fun `manual bases make tiers computable without any transactions`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.now(), "48000.00")

        fireService.update(
            household.id,
            request(
                tierCustomEnabled = false,
                essentialSpendingMode = SpendingBaseMode.manual,
                manualEssentialSpending = "800.00",
                totalSpendingMode = SpendingBaseMode.manual,
                manualTotalSpending = "2000.00",
            ),
            user,
        )
        val result = fireService.project(household.id)

        assertThat(result.spending.monthsAvailable).isEqualTo(0)
        // 800×12/0.04 and 2000×12/0.04.
        assertThat(result.tiers.single { it.key == FireTierKey.lean }.targetToday).isEqualByComparingTo("240000.00")
        assertThat(result.tiers.single { it.key == FireTierKey.fire }.targetToday).isEqualByComparingTo("600000.00")
        // Coverage: 48000 / 240000 = 20%.
        assertThat(result.tiers.single { it.key == FireTierKey.lean }.coveragePercent!!).isBetween(19.9, 20.1)
        val statTiers = result.scenarios.first().tierStats.map { it.tier }
        assertThat(statTiers).containsExactlyInAnyOrder(FireTierKey.lean, FireTierKey.fire, FireTierKey.fat)
    }

    @Test
    fun `manual essential above total is never reordered or clamped`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.now(), "10000.00")

        fireService.update(
            household.id,
            request(
                tierCustomEnabled = false,
                essentialSpendingMode = SpendingBaseMode.manual,
                manualEssentialSpending = "2000.00",
                totalSpendingMode = SpendingBaseMode.manual,
                manualTotalSpending = "1000.00",
            ),
            user,
        )
        val result = fireService.project(household.id)

        val lean = result.tiers.single { it.key == FireTierKey.lean }.targetToday!!
        val fire = result.tiers.single { it.key == FireTierKey.fire }.targetToday!!
        assertThat(lean).isEqualByComparingTo("600000.00")
        assertThat(fire).isEqualByComparingTo("300000.00")
        assertThat(lean).isGreaterThan(fire)
    }

    @Test
    fun `spending base modes persist per household`() {
        val (household, user) = setupHousehold()

        fireService.update(
            household.id,
            request(
                essentialSpendingMode = SpendingBaseMode.manual,
                manualEssentialSpending = "750.50",
                totalSpendingMode = SpendingBaseMode.derived,
                manualTotalSpending = "1234.00",
            ),
            user,
        )

        val dto = fireService.settingsDto(household.id)
        assertThat(dto.essentialSpendingMode).isEqualTo(SpendingBaseMode.manual)
        assertThat(dto.manualEssentialSpending).isEqualByComparingTo("750.50")
        assertThat(dto.totalSpendingMode).isEqualTo(SpendingBaseMode.derived)
        assertThat(dto.manualTotalSpending).isEqualByComparingTo("1234.00")
    }

    // -------------------------------------------------------------------------------------
    // Contribution modes

    @Test
    fun `derived contribution modes expose all three values`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.now(), "10000.00")
        for (back in 0L..11L) {
            val month = YearMonth.now().minusMonths(back)
            addTx(household, user, month.atDay(1), "income.salary", "3000.00", Direction.income)
            addTx(household, user, month.atDay(2), "groceries.groceries", "1600.00")
        }
        addMovement(household, user, LocalDate.now().minusMonths(1), MovementType.contribution, "10800.00")

        fireService.update(household.id, request(monthlyContribution = "500.00", contributionMode = ContributionMode.savings), user)
        val result = fireService.project(household.id)

        assertThat(result.contributions.manualMonthly).isEqualByComparingTo("500.00")
        // (36000 − 19200) / 12 = 1400
        assertThat(result.contributions.savingsMonthly).isEqualByComparingTo("1400.00")
        // 10800 net contributions in the trailing window ÷ 2 months since the first movement.
        assertThat(result.contributions.movementsMonthly).isEqualByComparingTo("5400.00")
        assertThat(result.contributions.activeMonthly).isEqualByComparingTo("1400.00")
    }

    @Test
    fun `derived mode without data leaves the active contribution null`() {
        val (household, user) = setupHousehold()
        addSnapshot(household, user, LocalDate.of(2025, 1, 1), "10000.00")

        fireService.update(household.id, request(contributionMode = ContributionMode.movements), user)
        val result = fireService.project(household.id)

        assertThat(result.contributions.movementsMonthly).isNull()
        assertThat(result.contributions.activeMonthly).isNull()
    }

    // -------------------------------------------------------------------------------------
    // Settings persistence

    @Test
    fun `tax brackets persist per household and validate ordering`() {
        val (household, user) = setupHousehold()

        val saved = fireService.update(
            household.id,
            request().copy(
                taxBrackets = listOf(
                    TaxBracketInput(BigDecimal("0.00"), BigDecimal("20.0")),
                    TaxBracketInput(BigDecimal("10000.00"), BigDecimal("25.0")),
                ),
            ),
            user,
        )
        assertThat(saved.taxBrackets).hasSize(2)
        assertThat(fireService.settingsDto(household.id).taxBrackets.map { it.ratePct.toDouble() })
            .containsExactly(20.0, 25.0)

        val badRequest = request().copy(
            taxBrackets = listOf(
                TaxBracketInput(BigDecimal("10000.00"), BigDecimal("25.0")),
                TaxBracketInput(BigDecimal("0.00"), BigDecimal("20.0")),
            ),
        )
        org.junit.jupiter.api.assertThrows<com.sephilabs.sharedledger.common.errors.AppException> {
            fireService.update(household.id, badRequest, user)
        }
    }

    @Test
    fun `unsaved household reads the seeded spanish brackets`() {
        val (household, _) = setupHousehold()
        val dto = fireService.settingsDto(household.id)
        assertThat(dto.taxBrackets).hasSize(5)
        assertThat(dto.taxBrackets.first().ratePct).isEqualByComparingTo("19.0")
        assertThat(dto.expectedInflationPct).isEqualByComparingTo("2.0")
        assertThat(dto.safeWithdrawalRatePct).isEqualByComparingTo("4.0")
        assertThat(dto.fatMultiplier).isEqualByComparingTo("1.5")
    }

    // -------------------------------------------------------------------------------------
    // Helpers

    private fun request(
        targetAmount: String = "0.00",
        targetYear: Short = 2035,
        monthlyContribution: String = "0.00",
        scenarios: List<ReturnScenarioInput> = listOf(ReturnScenarioInput(BigDecimal("0.0"), BigDecimal("0.0"))),
        inflation: String = "0.00",
        indexContribution: Boolean = true,
        tierCustomEnabled: Boolean = true,
        contributionMode: ContributionMode = ContributionMode.manual,
        applyCapitalGainsTax: Boolean = false,
        fallbackGainFractionPct: String = "50.00",
        essentialSpendingMode: SpendingBaseMode = SpendingBaseMode.derived,
        manualEssentialSpending: String = "0.00",
        totalSpendingMode: SpendingBaseMode = SpendingBaseMode.derived,
        manualTotalSpending: String = "0.00",
    ) = FireSettingsRequest(
        targetAmount = BigDecimal(targetAmount),
        targetYear = targetYear,
        monthlyContribution = BigDecimal(monthlyContribution),
        returnScenarios = scenarios,
        qualifyingAssetClasses = listOf("etfs"),
        expectedInflationPct = BigDecimal(inflation),
        safeWithdrawalRatePct = BigDecimal("4.0"),
        fatMultiplier = BigDecimal("1.5"),
        contributionMode = contributionMode,
        indexContribution = indexContribution,
        essentialSpendingMode = essentialSpendingMode,
        manualEssentialSpending = BigDecimal(manualEssentialSpending),
        totalSpendingMode = totalSpendingMode,
        manualTotalSpending = BigDecimal(manualTotalSpending),
        tierLeanEnabled = true,
        tierFireEnabled = true,
        tierFatEnabled = true,
        tierCustomEnabled = tierCustomEnabled,
        applyCapitalGainsTax = applyCapitalGainsTax,
        fallbackGainFractionPct = BigDecimal(fallbackGainFractionPct),
        taxBrackets = FireDefaults.SPANISH_SAVINGS_TAX_BRACKETS.map { TaxBracketInput(it.lowerBound, it.ratePct) },
    )

    private fun setupHousehold(): Pair<Household, User> {
        val user = users.save(User(email = "f${System.nanoTime()}@example.com", passwordHash = "x"))
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
            )
        )
    }

    private fun addTx(h: Household, u: User, date: LocalDate, cat: String, amount: String, dir: Direction = Direction.expense) {
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
