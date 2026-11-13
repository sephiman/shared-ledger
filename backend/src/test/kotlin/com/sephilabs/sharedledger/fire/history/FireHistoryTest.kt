package com.sephilabs.sharedledger.fire.history

import com.sephilabs.sharedledger.catalog.CustomCategoryEntity
import com.sephilabs.sharedledger.catalog.CustomCategoryId
import com.sephilabs.sharedledger.catalog.CustomCategoryRepository
import com.sephilabs.sharedledger.fire.ContributionMode
import com.sephilabs.sharedledger.fire.FireDefaults
import com.sephilabs.sharedledger.analytics.AnalyticsService
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.fire.FireService
import com.sephilabs.sharedledger.fire.FireSettingsRepository
import com.sephilabs.sharedledger.fire.FireTaxBracketRepository
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.ZoneId
import com.sephilabs.sharedledger.fire.FireSettingsRequest
import com.sephilabs.sharedledger.fire.FireTierKey
import com.sephilabs.sharedledger.fire.ReturnScenarioInput
import com.sephilabs.sharedledger.fire.SpendingBaseMode
import com.sephilabs.sharedledger.fire.TaxBracketInput
import com.sephilabs.sharedledger.identity.AuthHttpTestBase
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.movement.MovementRepository
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.networth.movement.NetWorthMovement
import com.sephilabs.sharedledger.networth.snapshot.Snapshot
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValue
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValueId
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import com.sephilabs.sharedledger.networth.snapshot.SnapshotService
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRequest
import com.sephilabs.sharedledger.transaction.TransactionService
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields
import java.util.UUID

class FireHistoryTest @Autowired constructor(
    private val objectMapper: ObjectMapper,
    private val users: UserRepository,
    private val snapshots: SnapshotRepository,
    private val snapshotService: SnapshotService,
    private val movements: MovementRepository,
    private val transactions: TransactionService,
    private val customCategories: CustomCategoryRepository,
    private val fireService: FireService,
    private val meters: MeterRegistry,
    private val fireSettings: FireSettingsRepository,
    private val taxBrackets: FireTaxBracketRepository,
    private val transactionRows: TransactionRepository,
    private val analytics: AnalyticsService,
    private val props: AppProperties,
    private val txManager: PlatformTransactionManager,
) : AuthHttpTestBase() {

    private val today: LocalDate = LocalDate.now()
    private val quarterStart: LocalDate =
        today.withDayOfMonth(1).withMonth((today.get(IsoFields.QUARTER_OF_YEAR) - 1) * 3 + 1)

    /** Mid-quarter date `quartersBack` whole quarters before the current one. */
    private fun quarterDate(quartersBack: Long): LocalDate = quarterStart.minusMonths(3 * quartersBack).plusDays(14)

    /** Spending rises month by month, so every date's window gives a different base. */
    private fun spendingFor(month: YearMonth): BigDecimal =
        BigDecimal(1000 + 10 * (month.year * 12 + month.monthValue - 24_000)).setScale(2)

    @Test
    fun `history is built with today's settings and each date's wealth and spending window`() {
        val f = fixture(spendingMonths = 30)
        val past = listOf(12L, 5L, 3L, 1L).associateWith { quarterDate(it) }
        past.forEach { (k, date) -> addSnapshot(f, date, BigDecimal(100_000 + 10_000 * (12 - k))) }
        addSnapshot(f, today, BigDecimal("250000.00"))
        // A second snapshot earlier in the same quarter is not a point: only the quarter's last one is.
        addSnapshot(f, quarterDate(3).minusDays(10), BigDecimal("1.00"))

        val history = getHistory(f)
        val points = history["points"].toList()

        // 12 quarters back needs spending from before the first transaction: omitted, and counted.
        assertThat(points.map { LocalDate.parse(it["date"].asString()) })
            .containsExactly(past.getValue(5), past.getValue(3), past.getValue(1), today)
        assertThat(history["omitted"].toList().map { it["reason"].asString() to it["count"].asInt() })
            .containsExactly("insufficient_spending_history" to 1)
        assertThat(points.last()["current"].asBoolean()).isTrue()

        for (point in points.dropLast(1)) {
            val date = LocalDate.parse(point["date"].asString())
            val wealth = snapshots.findAllOrdered(f.householdId).single { it.snapshotDate == date }.assetValues.single().value
            assertThat(BigDecimal(point["wealth"].asString())).isEqualByComparingTo(wealth)
            // Today's settings (SWR 4 %, no tax) over the 12 months ending at that date's month.
            val window = (0L until 12L).map { YearMonth.from(date).minusMonths(it) }
            val monthly = window.fold(BigDecimal.ZERO) { acc, m -> acc + spendingFor(m) }.divide(BigDecimal(12), 2, RoundingMode.HALF_EVEN)
            val expectedTarget = monthly * BigDecimal(12) / BigDecimal("0.04")
            assertThat(BigDecimal(point["target"].asString())).isEqualByComparingTo(expectedTarget)
            assertThat(point["coveragePercent"].asDouble())
                .isCloseTo(wealth.toDouble() / expectedTarget.toDouble() * 100.0, Offset.offset(1e-9))
        }

        // Changing a setting re-prices every point, past ones included.
        saveSettings(f, swrPct = "3.00")
        val repriced = getHistory(f)["points"].toList()
        assertThat(BigDecimal(repriced.first()["target"].asString()))
            .isEqualByComparingTo(BigDecimal(points.first()["target"].asString()) * BigDecimal(4) / BigDecimal(3))
    }

    @Test
    fun `the current point is exactly today's photo`() {
        val f = fixture(spendingMonths = 30)
        addSnapshot(f, quarterDate(2), BigDecimal("150000.00"))
        addSnapshot(f, today, BigDecimal("180000.00"))

        val current = getHistory(f)["points"].toList().last()
        val photo = fireService.project(f.householdId)
        val tier = photo.tiers.single { it.key == FireTierKey.fire }
        val stats = photo.scenarios[photo.settings.referenceScenarioIndex].tierStats.single { it.tier == FireTierKey.fire }

        assertThat(current["coveragePercent"].asDouble()).isEqualTo(tier.coveragePercent)
        assertThat(BigDecimal(current["target"].asString())).isEqualByComparingTo(tier.targetToday)
        assertThat(BigDecimal(current["wealth"].asString())).isEqualByComparingTo(photo.startingValue)
        assertThat(current["probability"].asDouble()).isEqualTo(stats.probabilityOfReachingTarget)
        assertThat(current["estimatedYear"].takeUnless { it.isNull }?.asInt()).isEqualTo(stats.estimatedYear)
        // A stochastic, not-certain case, so the equality above means something.
        assertThat(stats.probabilityOfReachingTarget).isStrictlyBetween(0.0, 1.0)
    }

    @Test
    fun `a past point is exactly the photo as it looked on that day`() {
        val f = fixture(spendingMonths = 30)
        val day = quarterDate(2)
        addSnapshot(f, quarterDate(5), BigDecimal("120000.00"))
        addSnapshot(f, day, BigDecimal("150000.00"))

        val point = getHistory(f)["points"].toList().single { it["date"].asString() == day.toString() }
        assertThat(point["current"].asBoolean()).isFalse()

        // The same service, but with "today" set to that day — what the photo would have shown then.
        val then = FireService(
            fireSettings, taxBrackets, snapshots, movements, transactionRows, analytics, props,
            Clock.fixed(day.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()),
        )
        val photo = requireNotNull(TransactionTemplate(txManager).execute { then.project(f.householdId) })
        val tier = photo.tiers.single { it.key == FireTierKey.fire }
        val stats = photo.scenarios[photo.settings.referenceScenarioIndex].tierStats.single { it.tier == FireTierKey.fire }

        assertThat(photo.snapshotDate).isEqualTo(day)
        assertThat(point["coveragePercent"].asDouble()).isEqualTo(tier.coveragePercent)
        assertThat(BigDecimal(point["target"].asString())).isEqualByComparingTo(tier.targetToday)
        assertThat(BigDecimal(point["wealth"].asString())).isEqualByComparingTo(photo.startingValue)
        assertThat(point["probability"].asDouble()).isEqualTo(stats.probabilityOfReachingTarget)
        assertThat(point["estimatedYear"].takeUnless { it.isNull }?.asInt()).isEqualTo(stats.estimatedYear)
        // Spending kept rising after that day, so today's photo really is different: the equality above is not trivial.
        assertThat(fireService.project(f.householdId).tiers.single { it.key == FireTierKey.fire }.targetToday)
            .isGreaterThan(tier.targetToday)
    }

    @Test
    fun `a single snapshot gives one point and no attribution`() {
        val f = fixture(spendingMonths = 30)
        addSnapshot(f, today, BigDecimal("180000.00"))

        val history = getHistory(f)
        assertThat(history["points"].size()).isEqualTo(1)
        assertThat(history["attribution"].isNull).isTrue()
    }

    @Test
    fun `no spending history and no snapshot give an empty series rather than a broken one`() {
        val f = fixture(spendingMonths = 0)
        val history = getHistory(f)
        assertThat(history["points"].size()).isZero()
        assertThat(history["attribution"].isNull).isTrue()
    }

    @Test
    fun `attribution splits the change exactly when movements are recorded`() {
        val f = fixture(spendingMonths = 30)
        val from = quarterDate(4)
        addSnapshot(f, from, BigDecimal("120000.00"))
        addSnapshot(f, today, BigDecimal("170000.00"))
        // Monthly contributions across the interval: 12 × 2,000 = 24,000 put in; the rest is the market.
        (1L..12L).map { from.plusMonths(it).withDayOfMonth(1) }.filter { !it.isAfter(today) }
            .forEach { addMovement(f, it, "2000.00") }
        addMovement(f, from.minusMonths(1), "500.00") // before the interval: not counted

        val a = getHistory(f)["attribution"]
        assertThat(a["merged"].asBoolean()).isFalse()
        assertThat(LocalDate.parse(a["fromDate"].asString())).isEqualTo(from)
        val putIn = (1L..12L).count { !from.plusMonths(it).withDayOfMonth(1).isAfter(today) } * 2000
        assertThat(BigDecimal(a["contributedAmount"].asString())).isEqualByComparingTo(BigDecimal(putIn))
        assertThat(BigDecimal(a["marketAmount"].asString())).isEqualByComparingTo(BigDecimal(50_000 - putIn))

        val exact = a["exact"]
        val points = getHistory(f)["points"].toList()
        val total = points.last()["coveragePercent"].asDouble() - points.first()["coveragePercent"].asDouble()
        assertThat(exact["total"].asDouble()).isCloseTo(total, Offset.offset(1e-9))
        assertThat(exact["contributed"].asDouble() + exact["market"].asDouble() + exact["need"].asDouble())
            .isCloseTo(exact["total"].asDouble(), Offset.offset(1e-9))
        // Spending rose, so the need part is negative.
        assertThat(exact["need"].asDouble()).isNegative()
        // The split itself, not just its sum: the cross term is shared half and half (Shapley midpoint).
        val (w0, w1) = points.first()["wealth"].asDouble() to points.last()["wealth"].asDouble()
        val (t0, t1) = points.first()["target"].asDouble() to points.last()["target"].asDouble()
        assertThat(exact["need"].asDouble()).isCloseTo(0.5 * (w0 + w1) * (1 / t1 - 1 / t0) * 100, Offset.offset(1e-9))
        assertThat(exact["contributed"].asDouble()).isCloseTo(putIn * 0.5 * (1 / t0 + 1 / t1) * 100, Offset.offset(1e-9))
        // Shown parts add up to the shown total.
        assertThat(BigDecimal(a["contributedPoints"].asString()) + BigDecimal(a["marketPoints"].asString()) + BigDecimal(a["needPoints"].asString()))
            .isEqualByComparingTo(BigDecimal(a["totalPoints"].asString()))
        assertThat(BigDecimal(a["totalPoints"].asString()))
            .isEqualByComparingTo(BigDecimal(total).setScale(1, RoundingMode.HALF_EVEN))
    }

    @Test
    fun `attribution merges contributions and market when no movements were recorded`() {
        val f = fixture(spendingMonths = 30)
        addSnapshot(f, quarterDate(4), BigDecimal("120000.00"))
        addSnapshot(f, today, BigDecimal("170000.00"))

        val a = getHistory(f)["attribution"]
        assertThat(a["merged"].asBoolean()).isTrue()
        assertThat(a["contributedPoints"].isNull).isTrue()
        assertThat(a["marketPoints"].isNull).isTrue()
        assertThat(a["contributedAmount"].isNull).isTrue()
        val exact = a["exact"]
        assertThat(exact["wealth"].asDouble() + exact["need"].asDouble()).isCloseTo(exact["total"].asDouble(), Offset.offset(1e-9))
        assertThat(BigDecimal(a["wealthPoints"].asString()) + BigDecimal(a["needPoints"].asString()))
            .isEqualByComparingTo(BigDecimal(a["totalPoints"].asString()))
    }

    @Test
    fun `attribution can be asked against any earlier point`() {
        val f = fixture(spendingMonths = 30)
        addSnapshot(f, quarterDate(4), BigDecimal("120000.00"))
        addSnapshot(f, quarterDate(1), BigDecimal("150000.00"))
        addSnapshot(f, today, BigDecimal("170000.00"))

        val a = json(
            mockMvc.perform(
                get("/api/households/${f.householdId}/fire/history/attribution")
                    .param("from", quarterDate(1).toString()).cookie(f.session),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
        )
        assertThat(LocalDate.parse(a["fromDate"].asString())).isEqualTo(quarterDate(1))
        mockMvc.perform(
            get("/api/households/${f.householdId}/fire/history/attribution")
                .param("from", today.minusDays(1).toString()).cookie(f.session),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `the cached series is reused until any input changes`() {
        val f = fixture(spendingMonths = 30)
        addSnapshot(f, quarterDate(2), BigDecimal("150000.00"))
        addSnapshot(f, today, BigDecimal("180000.00"))

        fun missesAfterRead(): Double {
            getHistory(f)
            return meters.counter("sl_fire_section_cache_total", "section", "history", "outcome", "miss").count()
        }

        val first = missesAfterRead()
        assertThat(missesAfterRead()).describedAs("unchanged inputs hit").isEqualTo(first)

        var misses = first
        fun assertMissAfter(trigger: String, change: () -> Unit) {
            change()
            val now = missesAfterRead()
            assertThat(now).describedAs("$trigger invalidates").isEqualTo(misses + 1)
            misses = now
            assertThat(missesAfterRead()).describedAs("$trigger then unchanged hits").isEqualTo(misses)
        }

        assertMissAfter("settings") { saveSettings(f, swrPct = "3.50") }
        assertMissAfter("new snapshot") { addSnapshot(f, quarterDate(3), BigDecimal("140000.00")) }
        assertMissAfter("edited snapshot value") {
            val snap = snapshots.findAllOrdered(f.householdId).first()
            snap.assetValues.single().value = BigDecimal("141000.00")
            snapshots.save(snap)
        }
        assertMissAfter("deleted snapshot") {
            // Not the snapshot just edited: removing that one would restore an earlier, still-cached state.
            val older = snapshots.findAllOrdered(f.householdId).single { it.snapshotDate == quarterDate(2) }
            snapshotService.delete(f.householdId, older.id, f.user)
        }
        assertMissAfter("movement") { addMovement(f, today.withDayOfMonth(1), "300.00") }
        assertMissAfter("transaction") { addTx(f, today.withDayOfMonth(1), "50.00") }
        assertMissAfter("category essential flag") {
            customCategories.save(
                CustomCategoryEntity(
                    id = CustomCategoryId(f.householdId, "custom.history-test"),
                    name = "History test",
                    kind = "expense",
                    groupCode = "groceries",
                    essential = true,
                    createdByUserId = f.user.id,
                ),
            )
        }
    }

    // -------------------------------------------------------------------------------------

    private class Fixture(val householdId: UUID, val user: User, val session: Cookie)

    private fun fixture(spendingMonths: Int): Fixture {
        val email = newEmail("fire-history")
        val session = sessionOf(register(email))
        val householdId = UUID.fromString(json(me(session).andReturn().response.contentAsString)["households"][0]["householdId"].asString())
        val f = Fixture(householdId, requireNotNull(users.findByEmailIgnoreCase(email)), session)
        saveSettings(f, swrPct = "4.00")
        (0 until spendingMonths).forEach { back ->
            val month = YearMonth.from(today).minusMonths(back.toLong())
            addTx(f, month.atDay(1), spendingFor(month).toPlainString())
        }
        return f
    }

    private fun saveSettings(f: Fixture, swrPct: String) {
        fireService.update(
            f.householdId,
            FireSettingsRequest(
                targetAmount = BigDecimal.ZERO,
                targetYear = (today.year + 20).toShort(),
                monthlyContribution = BigDecimal("1500.00"),
                returnScenarios = listOf(ReturnScenarioInput(BigDecimal("6.0"), BigDecimal("12.0"), false, "Base")),
                qualifyingAssetClasses = listOf("etfs"),
                expectedInflationPct = BigDecimal("2.0"),
                safeWithdrawalRatePct = BigDecimal(swrPct),
                fatMultiplier = BigDecimal("1.5"),
                contributionMode = ContributionMode.manual,
                indexContribution = true,
                essentialSpendingMode = SpendingBaseMode.derived,
                manualEssentialSpending = BigDecimal.ZERO,
                totalSpendingMode = SpendingBaseMode.derived,
                manualTotalSpending = BigDecimal.ZERO,
                tierLeanEnabled = true,
                tierFireEnabled = true,
                tierFatEnabled = true,
                tierCustomEnabled = false,
                applyCapitalGainsTax = false,
                indexTaxBrackets = false,
                drawdownYears = 30,
                fallbackGainFractionPct = BigDecimal("50.0"),
                taxBrackets = FireDefaults.SPANISH_SAVINGS_TAX_BRACKETS.map { TaxBracketInput(it.lowerBound, it.ratePct) },
                cashFlowEvents = emptyList(),
                referenceTier = FireTierKey.fire,
                referenceScenarioIndex = 0,
            ),
            f.user,
        )
    }

    private fun getHistory(f: Fixture): JsonNode =
        json(
            mockMvc.perform(get("/api/households/${f.householdId}/fire/history").cookie(f.session))
                .andExpect(status().isOk).andReturn().response.contentAsString,
        )

    private fun json(body: String): JsonNode = objectMapper.readTree(body)

    private fun addSnapshot(f: Fixture, date: LocalDate, etfs: BigDecimal) {
        val snapshot = Snapshot(householdId = f.householdId, snapshotDate = date, createdByUserId = f.user.id, updatedByUserId = f.user.id)
        snapshot.assetValues.add(SnapshotAssetValue(SnapshotAssetValueId(snapshot.id, "etfs"), etfs.setScale(2)))
        snapshots.save(snapshot)
    }

    private fun addMovement(f: Fixture, date: LocalDate, amount: String) {
        movements.save(
            NetWorthMovement(
                householdId = f.householdId,
                movementDate = date,
                type = MovementType.contribution,
                assetClassCode = "etfs",
                amount = BigDecimal(amount),
                createdByUserId = f.user.id,
                updatedByUserId = f.user.id,
            ),
        )
    }

    private fun addTx(f: Fixture, date: LocalDate, amount: String) {
        transactions.create(
            f.householdId,
            TransactionRequest(
                occurrenceDate = date,
                direction = Direction.expense,
                categoryCode = "groceries.groceries",
                amount = BigDecimal(amount),
                description = null,
            ),
            f.user,
        )
    }
}
