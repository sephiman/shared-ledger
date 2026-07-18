package com.sephilabs.sharedledger.analytics

import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.networth.movement.MovementRepository
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import com.sephilabs.sharedledger.recurring.RecurringTemplateRepository
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val DAYS_PER_MONTH: Double = 30.44

private fun YearMonth.key(): Int = year * 12 + (monthValue - 1)

private fun savingsRate(income: BigDecimal, expenses: BigDecimal): Double =
    if (income.signum() > 0)
        (income - expenses).divide(income, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0
    else 0.0

// Median of a series of amounts (2 dp, HALF_EVEN). Even-length series average the two middle values.
private fun median(values: List<BigDecimal>): BigDecimal {
    val sorted = values.sorted()
    return if (sorted.isEmpty()) BigDecimal.ZERO
    else if (sorted.size % 2 == 1) sorted[sorted.size / 2]
    else sorted[sorted.size / 2 - 1].add(sorted[sorted.size / 2])
        .divide(BigDecimal.valueOf(2L), 2, RoundingMode.HALF_EVEN)
}

@Service
class AnalyticsService(
    private val transactions: TransactionRepository,
    private val categoryService: CategoryService,
    private val recurring: RecurringTemplateRepository,
    private val snapshots: SnapshotRepository,
    private val movements: MovementRepository,
) {

    @Transactional(readOnly = true)
    fun monthDashboard(householdId: UUID, year: Int, month: Int): MonthDashboardResponse {
        val ym = YearMonth.of(year, month)
        val tx = transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, ym.atDay(1), ym.atEndOfMonth())
        val income = tx.filter { it.direction == Direction.income }.sumOf { it.amount }
        val expenses = tx.filter { it.direction == Direction.expense }.sumOf { it.amount }
        val savings = income - expenses
        val savingsRate = if (income.signum() > 0) {
            savings.divide(income, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0
        } else 0.0
        val catByCode = categoryService.listForHousehold(householdId).associate { it.code to (it.group ?: "ungrouped") }
        val byGroup = tx.filter { it.direction == Direction.expense }
            .groupBy { catByCode[it.categoryCode] ?: "ungrouped" }
            .map { (g, list) -> GroupTotal(g, Money.normalize(list.sumOf { it.amount })) }
            .sortedByDescending { it.amount }
        return MonthDashboardResponse(year, month, Money.normalize(income), Money.normalize(expenses), Money.normalize(savings), savingsRate, byGroup)
    }

    @Transactional(readOnly = true)
    fun yearDashboard(householdId: UUID, year: Int): YearDashboardResponse {
        val tx = transactions.findByHouseholdIdAndOccurrenceDateBetween(
            householdId, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)
        )
        val income = tx.filter { it.direction == Direction.income }.sumOf { it.amount }
        val expenses = tx.filter { it.direction == Direction.expense }.sumOf { it.amount }
        val savings = income - expenses
        val savingsRate = if (income.signum() > 0) {
            savings.divide(income, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0
        } else 0.0
        val catByCode = categoryService.listForHousehold(householdId).associate { it.code to (it.group ?: "ungrouped") }
        val byGroup = tx.filter { it.direction == Direction.expense }
            .groupBy { catByCode[it.categoryCode] ?: "ungrouped" }
            .map { (g, list) -> GroupTotal(g, Money.normalize(list.sumOf { it.amount })) }
            .sortedByDescending { it.amount }
        return YearDashboardResponse(year, Money.normalize(income), Money.normalize(expenses), Money.normalize(savings), savingsRate, byGroup)
    }

    @Transactional(readOnly = true)
    fun yearOverYear(householdId: UUID, month: Int, yearsBack: Int): YearOverYearResponse {
        val currentYear = LocalDate.now().year
        val yearsAvailable = mutableListOf<Int>()
        val incomeByYear = mutableMapOf<Int, BigDecimal>()
        val expensesByYear = mutableMapOf<Int, BigDecimal>()
        val savingsRateByYear = mutableMapOf<Int, Double>()
        val perCategory = mutableMapOf<String, MutableMap<Int, BigDecimal>>()

        for (year in (currentYear - yearsBack + 1)..currentYear) {
            val ym = YearMonth.of(year, month)
            val tx = transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, ym.atDay(1), ym.atEndOfMonth())
            if (tx.isEmpty()) continue
            yearsAvailable += year
            val incomeAmt = tx.filter { it.direction == Direction.income }.sumOf { it.amount }
            val expenseAmt = tx.filter { it.direction == Direction.expense }.sumOf { it.amount }
            incomeByYear[year] = Money.normalize(incomeAmt)
            expensesByYear[year] = Money.normalize(expenseAmt)
            savingsRateByYear[year] = savingsRate(incomeAmt, expenseAmt)
            tx.filter { it.direction == Direction.expense }.groupBy { it.categoryCode }
                .forEach { (code, list) ->
                    val m = perCategory.getOrPut(code) { mutableMapOf() }
                    m[year] = Money.normalize(list.sumOf { it.amount })
                }
        }
        return YearOverYearResponse(
            month = month,
            years = yearsAvailable,
            incomeByYear = incomeByYear,
            expensesByYear = expensesByYear,
            savingsRateByYear = savingsRateByYear,
            categories = perCategory.map { (code, m) -> CategoryBreakdownRow(code, m) }.sortedBy { it.categoryCode },
        )
    }

    @Transactional(readOnly = true)
    fun yearByYear(householdId: UUID, years: List<Int>): YearByYearResponse {
        val series = years.map { year ->
            val income = MutableList(12) { BigDecimal.ZERO }
            val expenses = MutableList(12) { BigDecimal.ZERO }
            val ymStart = YearMonth.of(year, 1)
            val ymEnd = YearMonth.of(year, 12)
            val tx = transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, ymStart.atDay(1), ymEnd.atEndOfMonth())
            for (t in tx) {
                val idx = t.occurrenceDate.monthValue - 1
                if (t.direction == Direction.income) income[idx] = income[idx] + t.amount
                else expenses[idx] = expenses[idx] + t.amount
            }
            val savings = income.mapIndexed { i, inc -> Money.normalize(inc - expenses[i]) }
            val rates = income.mapIndexed { i, inc -> savingsRate(inc, expenses[i]) }
            YearByYearSeries(
                year,
                income.map { Money.normalize(it) },
                expenses.map { Money.normalize(it) },
                savings,
                rates,
            )
        }
        return YearByYearResponse(series)
    }

    @Transactional(readOnly = true)
    fun trailing12(householdId: UUID, asOf: YearMonth): TrailingResponse {
        val points = mutableListOf<TrailingPoint>()
        var cursor = asOf.minusMonths(11)
        while (!cursor.isAfter(asOf)) {
            val tx = transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, cursor.atDay(1), cursor.atEndOfMonth())
            val inc = tx.filter { it.direction == Direction.income }.sumOf { it.amount }
            val exp = tx.filter { it.direction == Direction.expense }.sumOf { it.amount }
            val net = inc - exp
            val rate = if (inc.signum() > 0) net.divide(inc, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0 else 0.0
            points += TrailingPoint(
                cursor.year,
                cursor.monthValue,
                Money.normalize(inc),
                Money.normalize(exp),
                Money.normalize(net),
                rate,
            )
            cursor = cursor.plusMonths(1)
        }
        // Averages divide by the number of months in the range (zero months included),
        // matching the Explorer per-category averaging convention.
        val denom = points.size.coerceAtLeast(1).toBigDecimal()
        val avgIncome = points.fold(BigDecimal.ZERO) { acc, p -> acc + p.income }.divide(denom, 2, RoundingMode.HALF_EVEN)
        val avgExpenses = points.fold(BigDecimal.ZERO) { acc, p -> acc + p.expenses }.divide(denom, 2, RoundingMode.HALF_EVEN)
        val avgNetSavings = points.fold(BigDecimal.ZERO) { acc, p -> acc + p.netSavings }.divide(denom, 2, RoundingMode.HALF_EVEN)
        val summary = TrailingSummary(
            avgIncome = avgIncome,
            avgExpenses = avgExpenses,
            avgNetSavings = avgNetSavings,
            medianNetSavings = median(points.map { it.netSavings }),
        )
        return TrailingResponse(points, summary)
    }

    @Transactional(readOnly = true)
    fun forecast(householdId: UUID, horizonMonths: Int, windowMonths: Int): ForecastResponse {
        val today = LocalDate.now()
        val historyStart = YearMonth.from(today).minusMonths(windowMonths.toLong()).atDay(1)
        val historyEnd = YearMonth.from(today).atEndOfMonth()
        val history = transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, historyStart, historyEnd)
            .filter { it.direction == Direction.expense }
            .groupBy { it.categoryCode }

        val templatesByCategory = recurring.findAllByHouseholdIdAndActiveTrue(householdId)
            .filter { it.direction == Direction.expense }
            .groupBy { it.categoryCode }

        val categories = mutableListOf<CategoryForecast>()
        for ((code, list) in history) {
            val monthlyHistory = list.groupBy { YearMonth.from(it.occurrenceDate) }
                .mapValues { entry -> entry.value.sumOf { it.amount } }
            val historicalPoints = monthlyHistory.entries.sortedBy { it.key }
                .map { (ym, amount) -> HistoricalPoint(ym.year, ym.monthValue, Money.normalize(amount)) }

            val recurringAmount = templatesByCategory[code]?.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
            val movingAvg = if (monthlyHistory.isNotEmpty()) {
                monthlyHistory.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
                    .divide(BigDecimal.valueOf(monthlyHistory.size.toLong()), 2, RoundingMode.HALF_EVEN)
            } else BigDecimal.ZERO

            val projection = mutableListOf<ForecastPoint>()
            var cursor = YearMonth.from(today).plusMonths(1)
            for (i in 0 until horizonMonths) {
                val projected = recurringAmount ?: movingAvg
                projection += ForecastPoint(cursor.year, cursor.monthValue, Money.normalize(projected),
                    if (recurringAmount != null) "recurring" else "history")
                cursor = cursor.plusMonths(1)
            }
            categories += CategoryForecast(code, historicalPoints, projection)
        }
        return ForecastResponse(horizonMonths, windowMonths, categories.sortedBy { it.categoryCode })
    }

    @Transactional(readOnly = true)
    fun dashboardExtras(householdId: UUID, asOf: YearMonth): DashboardExtrasResponse {
        // Pull 35 months: 24 sparkline points each requiring 12 months → 23 + 12 = 35.
        val sparklineCount = 24
        val rangeStart = asOf.minusMonths((sparklineCount - 1 + 11).toLong()).atDay(1)
        val rangeEnd = asOf.atEndOfMonth()
        val rows = transactions.aggregationRows(householdId, rangeStart, rangeEnd)

        // Bucket per month: income, expense, recurring-expense, all-expense.
        data class MonthBucket(
            var income: BigDecimal = BigDecimal.ZERO,
            var expense: BigDecimal = BigDecimal.ZERO,
            var recurringExpense: BigDecimal = BigDecimal.ZERO,
        )

        val buckets = LinkedHashMap<Int, MonthBucket>()
        for (r in rows) {
            val k = YearMonth.from(r.occurrenceDate).key()
            val b = buckets.getOrPut(k) { MonthBucket() }
            when (r.direction) {
                Direction.income -> b.income += r.amount
                Direction.expense -> {
                    b.expense += r.amount
                    if (r.recurringTemplateId != null) b.recurringExpense += r.amount
                }
            }
        }

        fun bucket(ym: YearMonth): MonthBucket = buckets[ym.key()] ?: MonthBucket()

        fun trailingWindow(end: YearMonth, length: Int): Triple<BigDecimal, BigDecimal, BigDecimal> {
            var inc = BigDecimal.ZERO
            var exp = BigDecimal.ZERO
            var recExp = BigDecimal.ZERO
            var cursor = end.minusMonths((length - 1).toLong())
            while (!cursor.isAfter(end)) {
                val b = bucket(cursor)
                inc += b.income
                exp += b.expense
                recExp += b.recurringExpense
                cursor = cursor.plusMonths(1)
            }
            return Triple(inc, exp, recExp)
        }

        val (t12Inc, t12Exp, t12RecExp) = trailingWindow(asOf, 12)
        val trailing12Block = SavingsRateBlock(
            rate = savingsRate(t12Inc, t12Exp),
            income = Money.normalize(t12Inc),
            expenses = Money.normalize(t12Exp),
        )

        var ytdInc = BigDecimal.ZERO
        var ytdExp = BigDecimal.ZERO
        var ytdCursor = YearMonth.of(asOf.year, 1)
        while (!ytdCursor.isAfter(asOf)) {
            val b = bucket(ytdCursor)
            ytdInc += b.income
            ytdExp += b.expense
            ytdCursor = ytdCursor.plusMonths(1)
        }
        val ytdBlock = SavingsRateBlock(
            rate = savingsRate(ytdInc, ytdExp),
            income = Money.normalize(ytdInc),
            expenses = Money.normalize(ytdExp),
        )

        val currentBucket = bucket(asOf)
        val currentBlock = SavingsRateBlock(
            rate = savingsRate(currentBucket.income, currentBucket.expense),
            income = Money.normalize(currentBucket.income),
            expenses = Money.normalize(currentBucket.expense),
        )

        val sparkline = (0 until sparklineCount).map { idx ->
            val end = asOf.minusMonths((sparklineCount - 1 - idx).toLong())
            val (inc, exp, _) = trailingWindow(end, 12)
            TrailingSparklinePoint(end.year, end.monthValue, savingsRate(inc, exp))
        }

        val bounds = transactions.dateBounds(householdId)
        val monthsAvailable = bounds.minDate?.let { firstTx ->
            val firstYm = YearMonth.from(firstTx)
            val between = ChronoUnit.MONTHS.between(firstYm, asOf).toInt() + 1
            between.coerceIn(0, 12)
        } ?: 0

        val denom = monthsAvailable.coerceAtLeast(1).toBigDecimal()
        val recMonthlyAvg = t12RecExp.divide(denom, 2, RoundingMode.HALF_EVEN)
        val allMonthlyAvg = t12Exp.divide(denom, 2, RoundingMode.HALF_EVEN)
        val fixedRec = FixedCostBlock(
            monthlyAverage = Money.normalize(recMonthlyAvg),
            perDay = recMonthlyAvg.divide(BigDecimal.valueOf(DAYS_PER_MONTH), 2, RoundingMode.HALF_EVEN),
            perYear = Money.normalize(recMonthlyAvg.multiply(BigDecimal.valueOf(12L))),
        )
        val fixedAll = FixedCostBlock(
            monthlyAverage = Money.normalize(allMonthlyAvg),
            perDay = allMonthlyAvg.divide(BigDecimal.valueOf(DAYS_PER_MONTH), 2, RoundingMode.HALF_EVEN),
            perYear = Money.normalize(allMonthlyAvg.multiply(BigDecimal.valueOf(12L))),
        )

        return DashboardExtrasResponse(
            asOfYear = asOf.year,
            asOfMonth = asOf.monthValue,
            trailing12 = trailing12Block,
            ytd = ytdBlock,
            currentMonth = currentBlock,
            sparkline = sparkline,
            fixedRecurring = fixedRec,
            fixedAll = fixedAll,
            monthsAvailable = monthsAvailable,
        )
    }

    // Shared aggregation core for allocation() and moneyFlow(): both views MUST report
    // identical figures for the same period, so the bucketing lives in one place.
    private class FlowAggregate(
        val income: BigDecimal,
        val expenses: BigDecimal,
        val incomeByCategory: LinkedHashMap<String, BigDecimal>,
        val expensesByGroup: LinkedHashMap<String, BigDecimal>,
        val expensesByCategory: LinkedHashMap<String, BigDecimal>,
        val categoryGroups: Map<String, String>,
    )

    private fun aggregateFlows(householdId: UUID, from: LocalDate, to: LocalDate): FlowAggregate {
        val rows = transactions.aggregationRows(householdId, from, to)
        val catGroup = categoryService.listForHousehold(householdId).associate { it.code to (it.group ?: "ungrouped") }
        var income = BigDecimal.ZERO
        var expenses = BigDecimal.ZERO
        val incomeByCategory = LinkedHashMap<String, BigDecimal>()
        val expensesByGroup = LinkedHashMap<String, BigDecimal>()
        val expensesByCategory = LinkedHashMap<String, BigDecimal>()
        for (r in rows) {
            when (r.direction) {
                Direction.income -> {
                    income += r.amount
                    incomeByCategory.merge(r.categoryCode, r.amount) { a, b -> a + b }
                }
                Direction.expense -> {
                    expenses += r.amount
                    val g = catGroup[r.categoryCode] ?: "ungrouped"
                    expensesByGroup.merge(g, r.amount) { a, b -> a + b }
                    expensesByCategory.merge(r.categoryCode, r.amount) { a, b -> a + b }
                }
            }
        }
        return FlowAggregate(income, expenses, incomeByCategory, expensesByGroup, expensesByCategory, catGroup)
    }

    @Transactional(readOnly = true)
    fun allocation(householdId: UUID, year: Int, month: Int?): AllocationResponse {
        val (from, to) = if (month != null) {
            val ym = YearMonth.of(year, month)
            ym.atDay(1) to ym.atEndOfMonth()
        } else {
            LocalDate.of(year, 1, 1) to LocalDate.of(year, 12, 31)
        }
        val flows = aggregateFlows(householdId, from, to)
        val income = flows.income
        val saved = (income - flows.expenses).max(BigDecimal.ZERO)
        val slices = flows.expensesByGroup.entries
            .map { (group, amount) ->
                val pct = if (income.signum() > 0)
                    amount.divide(income, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0
                else 0.0
                AllocationSlice(groupCode = group, amount = Money.normalize(amount), percentOfIncome = pct)
            }
            .sortedByDescending { it.amount }

        return AllocationResponse(
            year = year,
            month = month,
            income = Money.normalize(income),
            expenses = Money.normalize(flows.expenses),
            saved = Money.normalize(saved),
            slices = slices,
        )
    }

    @Transactional(readOnly = true)
    fun moneyFlow(householdId: UUID, from: LocalDate, to: LocalDate, level: String): MoneyFlowResponse {
        val flows = aggregateFlows(householdId, from, to)
        val saved = (flows.income - flows.expenses).max(BigDecimal.ZERO)
        val deficit = (flows.expenses - flows.income).max(BigDecimal.ZERO)
        val nodes = mutableListOf<MoneyFlowNode>()
        val links = mutableListOf<MoneyFlowLink>()
        if (flows.income.signum() != 0 || flows.expenses.signum() != 0) {
            flows.incomeByCategory.entries.sortedByDescending { it.value }.forEach { (code, amount) ->
                nodes += MoneyFlowNode(id = code, side = "income", groupCode = null, amount = Money.normalize(amount))
                links += MoneyFlowLink(source = code, target = HUB_NODE_ID, amount = Money.normalize(amount))
            }
            if (deficit.signum() > 0) {
                nodes += MoneyFlowNode(id = DEFICIT_NODE_ID, side = "deficit", groupCode = null, amount = Money.normalize(deficit))
                links += MoneyFlowLink(source = DEFICIT_NODE_ID, target = HUB_NODE_ID, amount = Money.normalize(deficit))
            }
            nodes += MoneyFlowNode(id = HUB_NODE_ID, side = "hub", groupCode = null, amount = Money.normalize(flows.income + deficit))
            val expenseBuckets = if (level == "category") flows.expensesByCategory else flows.expensesByGroup
            expenseBuckets.entries.sortedByDescending { it.value }.forEach { (code, amount) ->
                val group = if (level == "category") flows.categoryGroups[code] ?: "ungrouped" else code
                nodes += MoneyFlowNode(id = code, side = "expense", groupCode = group, amount = Money.normalize(amount))
                links += MoneyFlowLink(source = HUB_NODE_ID, target = code, amount = Money.normalize(amount))
            }
            if (saved.signum() > 0) {
                nodes += MoneyFlowNode(id = SAVED_NODE_ID, side = "saved", groupCode = null, amount = Money.normalize(saved))
                links += MoneyFlowLink(source = HUB_NODE_ID, target = SAVED_NODE_ID, amount = Money.normalize(saved))
            }
        }
        return MoneyFlowResponse(
            from = from,
            to = to,
            level = level,
            income = Money.normalize(flows.income),
            expenses = Money.normalize(flows.expenses),
            saved = Money.normalize(saved),
            deficit = Money.normalize(deficit),
            nodes = nodes,
            links = links,
        )
    }

    @Transactional(readOnly = true)
    fun topMovers(householdId: UUID, year: Int, month: Int, baseline: String): TopMoversResponse {
        val period = YearMonth.of(year, month)
        val baselineMonths: List<YearMonth> = when (baseline) {
            "trailing6_avg" -> (1..6).map { period.minusMonths(it.toLong()) }
            else -> listOf(period.minusYears(1))
        }
        val rangeStart = baselineMonths.min().atDay(1)
        val rangeEnd = period.atEndOfMonth()
        val rows = transactions.aggregationRows(householdId, rangeStart, rangeEnd)
        val catGroup = categoryService.listForHousehold(householdId).associate { it.code to it.group }

        val periodTotals = LinkedHashMap<String, BigDecimal>()
        val baselineTotals = LinkedHashMap<String, BigDecimal>()
        val baselineKeys = baselineMonths.map { it.key() }.toHashSet()
        val periodKey = period.key()

        for (r in rows) {
            if (r.direction != Direction.expense) continue
            val k = YearMonth.from(r.occurrenceDate).key()
            if (k == periodKey) periodTotals.merge(r.categoryCode, r.amount) { a, b -> a + b }
            if (k in baselineKeys) baselineTotals.merge(r.categoryCode, r.amount) { a, b -> a + b }
        }
        val baselineDivisor = if (baseline == "trailing6_avg") BigDecimal.valueOf(6L) else BigDecimal.ONE
        val baselineAvg = baselineTotals.mapValues { (_, v) ->
            v.divide(baselineDivisor, 2, RoundingMode.HALF_EVEN)
        }

        val categoryCodes = periodTotals.keys + baselineAvg.keys
        val rowsForRanking = mutableListOf<MoverRow>()
        val newActivity = mutableListOf<MoverRow>()

        for (code in categoryCodes) {
            val p = periodTotals[code] ?: BigDecimal.ZERO
            val b = baselineAvg[code] ?: BigDecimal.ZERO
            val delta = p - b
            val pct: Double? = if (b.signum() > 0)
                delta.divide(b, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0
            else null

            val row = MoverRow(
                categoryCode = code,
                groupCode = catGroup[code],
                periodAmount = Money.normalize(p),
                baselineAmount = Money.normalize(b),
                deltaAbs = Money.normalize(delta),
                deltaPct = pct,
            )
            when {
                b.signum() == 0 && p.signum() > 0 -> newActivity += row
                p.signum() > 0 && b.signum() > 0 -> rowsForRanking += row
            }
        }

        val increases = rowsForRanking.filter { it.deltaAbs.signum() > 0 }
            .sortedByDescending { it.deltaAbs }.take(5)
        val decreases = rowsForRanking.filter { it.deltaAbs.signum() < 0 }
            .sortedBy { it.deltaAbs }.take(5)
        val totalIncrease = increases.fold(BigDecimal.ZERO) { acc, r -> acc + r.deltaAbs }
        val totalDecrease = decreases.fold(BigDecimal.ZERO) { acc, r -> acc + r.deltaAbs.abs() }

        return TopMoversResponse(
            year = year,
            month = month,
            baseline = baseline,
            increases = increases,
            decreases = decreases,
            newActivity = newActivity.sortedByDescending { it.periodAmount }.take(10),
            totalIncrease = Money.normalize(totalIncrease),
            totalDecrease = Money.normalize(totalDecrease),
        )
    }

    @Transactional(readOnly = true)
    fun recurringShare(
        householdId: UUID,
        scope: String,
        year: Int?,
        month: Int?,
    ): RecurringShareResponse {
        val today = LocalDate.now()
        val (from, to) = when (scope) {
            "month" -> {
                val ym = YearMonth.of(year ?: today.year, month ?: today.monthValue)
                ym.atDay(1) to ym.atEndOfMonth()
            }
            "trailing12" -> {
                val end = YearMonth.from(today)
                end.minusMonths(11).atDay(1) to end.atEndOfMonth()
            }
            "ytd" -> LocalDate.of(today.year, 1, 1) to today
            "year" -> {
                val y = year ?: today.year
                LocalDate.of(y, 1, 1) to LocalDate.of(y, 12, 31)
            }
            else -> throw IllegalArgumentException("INVALID_SCOPE")
        }
        val rows = transactions.aggregationRows(householdId, from, to)
        var recurring = BigDecimal.ZERO
        var discretionary = BigDecimal.ZERO
        for (r in rows) {
            if (r.direction != Direction.expense) continue
            if (r.recurringTemplateId != null) recurring += r.amount else discretionary += r.amount
        }
        val total = recurring + discretionary
        val recShare = if (total.signum() > 0)
            recurring.divide(total, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0 else 0.0
        val discShare = if (total.signum() > 0)
            discretionary.divide(total, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0 else 0.0
        return RecurringShareResponse(
            scope = scope,
            year = year,
            month = month,
            recurring = Money.normalize(recurring),
            discretionary = Money.normalize(discretionary),
            total = Money.normalize(total),
            recurringShare = recShare,
            discretionaryShare = discShare,
        )
    }

    @Transactional(readOnly = true)
    fun heatmap(
        householdId: UUID,
        months: Int,
        direction: Direction,
    ): HeatmapResponse {
        val today = YearMonth.now()
        val safeMonths = months.coerceAtLeast(1)
        val start = if (months >= 9999) {
            // "full history"
            val first = transactions.dateBounds(householdId).minDate?.let { YearMonth.from(it) } ?: today
            first
        } else {
            today.minusMonths((safeMonths - 1).toLong())
        }
        val from = start.atDay(1)
        val to = today.atEndOfMonth()

        val monthList = mutableListOf<YearMonth>()
        run {
            var cursor = start
            while (!cursor.isAfter(today)) { monthList += cursor; cursor = cursor.plusMonths(1) }
        }
        val monthIndex = monthList.withIndex().associate { (i, ym) -> ym.key() to i }

        val allCategories = categoryService.listForHousehold(householdId)
            .filter { it.kind == direction.name }
            .sortedWith(compareBy({ it.group ?: "" }, { it.sortOrder }, { it.code }))

        val cells: MutableMap<String, Array<BigDecimal?>> = LinkedHashMap()
        for (cat in allCategories) {
            cells[cat.code] = arrayOfNulls(monthList.size)
        }
        val rows = transactions.aggregationRows(householdId, from, to)
        for (r in rows) {
            if (r.direction != direction) continue
            val col = monthIndex[YearMonth.from(r.occurrenceDate).key()] ?: continue
            val arr = cells[r.categoryCode] ?: continue
            val cur = arr[col] ?: BigDecimal.ZERO
            arr[col] = cur + r.amount
        }

        val categoryRows = allCategories.map { cat ->
            HeatmapCategoryRow(
                categoryCode = cat.code,
                groupCode = cat.group,
                values = cells[cat.code]!!.map { v -> v?.let { Money.normalize(it) } },
            )
        }

        return HeatmapResponse(
            direction = direction.name,
            months = monthList.map { HeatmapMonth(it.year, it.monthValue) },
            categories = categoryRows,
        )
    }

    @Transactional(readOnly = true)
    fun daily(householdId: UUID, from: LocalDate, to: LocalDate, direction: Direction): DailyResponse {
        val rows = transactions.aggregationRows(householdId, from, to)
        val totals = LinkedHashMap<LocalDate, BigDecimal>()
        for (r in rows) {
            if (r.direction != direction) continue
            totals.merge(r.occurrenceDate, r.amount) { a, b -> a + b }
        }
        val days = totals.entries.asSequence()
            .filter { it.value.signum() > 0 }
            .sortedBy { it.key }
            .map { (d, amt) -> DailyPoint(d, Money.normalize(amt)) }
            .toList()
        return DailyResponse(from, to, direction.name, days)
    }

    @Transactional(readOnly = true)
    fun contributionSeries(householdId: UUID): ContributionSeriesResponse {
        val ms = movements.findInRange(householdId, LocalDate.of(1970, 1, 1), LocalDate.now().plusYears(100))
            .filter { it.type != MovementType.debt_payment }
            .sortedBy { it.movementDate }
        if (ms.isEmpty()) return ContributionSeriesResponse(hasMovements = false, points = emptyList())

        val ss = snapshots.findAllOrdered(householdId)
        if (ss.isEmpty()) return ContributionSeriesResponse(hasMovements = true, points = emptyList())

        val points = mutableListOf<ContributionPoint>()
        var idx = 0
        var cumContrib = BigDecimal.ZERO
        var cumWithdraw = BigDecimal.ZERO
        for (snap in ss) {
            while (idx < ms.size && !ms[idx].movementDate.isAfter(snap.snapshotDate)) {
                val m = ms[idx]
                when (m.type) {
                    MovementType.contribution -> cumContrib += m.amount
                    MovementType.withdrawal -> cumWithdraw += m.amount
                    MovementType.debt_payment -> { /* filtered out */ }
                }
                idx++
            }
            points += ContributionPoint(
                snapshotDate = snap.snapshotDate,
                cumulativeContribution = Money.normalize(cumContrib),
                cumulativeWithdrawal = Money.normalize(cumWithdraw),
                netContribution = Money.normalize(cumContrib - cumWithdraw),
            )
        }
        return ContributionSeriesResponse(hasMovements = true, points = points)
    }

    @Transactional(readOnly = true)
    fun costOfLiving(householdId: UUID, asOf: YearMonth): CostOfLivingResponse {
        val from = asOf.minusMonths(11).atDay(1)
        val to = asOf.atEndOfMonth()
        val rows = transactions.aggregationRows(householdId, from, to)

        val catMeta = categoryService.listForHousehold(householdId).associate { it.code to (it.essential to it.group) }

        var essentialTotal = BigDecimal.ZERO
        var nonEssentialTotal = BigDecimal.ZERO
        val perEssentialCategory = LinkedHashMap<String, BigDecimal>()
        val perNonEssentialCategory = LinkedHashMap<String, BigDecimal>()
        for (r in rows) {
            if (r.direction != Direction.expense) continue
            val meta = catMeta[r.categoryCode] ?: continue
            if (meta.first) {
                essentialTotal += r.amount
                perEssentialCategory.merge(r.categoryCode, r.amount) { a, b -> a + b }
            } else {
                nonEssentialTotal += r.amount
                perNonEssentialCategory.merge(r.categoryCode, r.amount) { a, b -> a + b }
            }
        }
        val allTotal = essentialTotal + nonEssentialTotal

        val bounds = transactions.dateBounds(householdId)
        val monthsAvailable = bounds.minDate?.let { firstTx ->
            val firstYm = YearMonth.from(firstTx)
            val between = ChronoUnit.MONTHS.between(firstYm, asOf).toInt() + 1
            between.coerceIn(0, 12)
        } ?: 0
        val denom = monthsAvailable.coerceAtLeast(1).toBigDecimal()

        val essentialMonthly = essentialTotal.divide(denom, 2, RoundingMode.HALF_EVEN)
        val nonEssentialMonthly = nonEssentialTotal.divide(denom, 2, RoundingMode.HALF_EVEN)
        val totalMonthly = allTotal.divide(denom, 2, RoundingMode.HALF_EVEN)
        val essentialShare = if (allTotal.signum() > 0)
            essentialTotal.divide(allTotal, 4, RoundingMode.HALF_EVEN).toDouble() * 100.0
        else 0.0

        fun rowsFor(source: Map<String, BigDecimal>) = source.entries
            .map { (code, sum) ->
                CostOfLivingCategoryRow(
                    categoryCode = code,
                    groupCode = catMeta[code]?.second,
                    monthlyAverage = Money.normalize(sum.divide(denom, 2, RoundingMode.HALF_EVEN)),
                )
            }
            .sortedByDescending { it.monthlyAverage }

        return CostOfLivingResponse(
            asOfYear = asOf.year,
            asOfMonth = asOf.monthValue,
            monthsAvailable = monthsAvailable,
            essentialMonthlyAverage = Money.normalize(essentialMonthly),
            nonEssentialMonthlyAverage = Money.normalize(nonEssentialMonthly),
            totalMonthlyAverage = Money.normalize(totalMonthly),
            essentialPerYear = Money.normalize(essentialMonthly.multiply(BigDecimal.valueOf(12L))),
            nonEssentialPerYear = Money.normalize(nonEssentialMonthly.multiply(BigDecimal.valueOf(12L))),
            totalPerYear = Money.normalize(totalMonthly.multiply(BigDecimal.valueOf(12L))),
            essentialShare = essentialShare,
            essentialCategories = rowsFor(perEssentialCategory),
            nonEssentialCategories = rowsFor(perNonEssentialCategory),
        )
    }

    @Transactional(readOnly = true)
    fun explorer(
        householdId: UUID,
        scopeType: String?,
        scopeCode: String?,
        months: Int,
        yoyOverlay: Boolean,
    ): ExplorerResponse {
        val today = YearMonth.now()
        val bounds = transactions.dateBounds(householdId)
        val firstTxYm = bounds.minDate?.let { YearMonth.from(it) }

        val resolvedStart = if (months >= 9999) {
            firstTxYm ?: today
        } else {
            today.minusMonths((months - 1).toLong())
        }
        val resolvedMonths = mutableListOf<YearMonth>().also { list ->
            var cursor = resolvedStart
            while (!cursor.isAfter(today)) { list += cursor; cursor = cursor.plusMonths(1) }
        }

        // Categories grouped by group code (e.g. "home", "transport", ...). Only expense kind matters for scope.
        val allCategories = categoryService.listForHousehold(householdId).filter { it.kind == Direction.expense.name }
        val groupByCategory = allCategories.associate { it.code to (it.group ?: "ungrouped") }

        // Resolve default scope: group with the highest trailing-12-month spend.
        val effectiveType: String
        val effectiveCode: String
        if (scopeType != null && scopeCode != null) {
            effectiveType = scopeType
            effectiveCode = scopeCode
        } else {
            val t12Start = today.minusMonths(11)
            val rowsT12 = transactions.aggregationRows(householdId, t12Start.atDay(1), today.atEndOfMonth())
            val perGroup = LinkedHashMap<String, BigDecimal>()
            for (r in rowsT12) {
                if (r.direction != Direction.expense) continue
                val g = groupByCategory[r.categoryCode] ?: "ungrouped"
                perGroup.merge(g, r.amount) { a, b -> a + b }
            }
            effectiveType = "group"
            effectiveCode = perGroup.entries.maxByOrNull { it.value }?.key
                ?: allCategories.firstOrNull()?.group
                ?: "home"
        }

        // Pull range we need: current window plus optional prior-year window.
        val priorStart = resolvedStart.minusYears(1)
        val priorEnd = today.minusYears(1)
        val rangeStart = (if (yoyOverlay) priorStart else resolvedStart).atDay(1)
        val rangeEnd = today.atEndOfMonth()
        val rows = transactions.aggregationRows(householdId, rangeStart, rangeEnd)

        fun matchesScope(categoryCode: String): Boolean = when (effectiveType) {
            "category" -> categoryCode == effectiveCode
            else -> (groupByCategory[categoryCode] ?: "ungrouped") == effectiveCode
        }

        // Bucket current and prior windows.
        val currentTotals = LinkedHashMap<Int, BigDecimal>()
        val priorTotals = if (yoyOverlay) LinkedHashMap<Int, BigDecimal>() else null
        for (r in rows) {
            if (r.direction != Direction.expense) continue
            if (!matchesScope(r.categoryCode)) continue
            val ym = YearMonth.from(r.occurrenceDate)
            val k = ym.key()
            when {
                !ym.isBefore(resolvedStart) && !ym.isAfter(today) ->
                    currentTotals.merge(k, r.amount) { a, b -> a + b }
                priorTotals != null && !ym.isBefore(priorStart) && !ym.isAfter(priorEnd) ->
                    priorTotals.merge(k, r.amount) { a, b -> a + b }
            }
        }

        val currentSeries = resolvedMonths.map { ym ->
            ExplorerMonth(ym.year, ym.monthValue, Money.normalize(currentTotals[ym.key()] ?: BigDecimal.ZERO))
        }
        val priorSeries = priorTotals?.let {
            resolvedMonths.map { ym ->
                val shifted = ym.minusYears(1)
                ExplorerMonth(shifted.year, shifted.monthValue, Money.normalize(it[shifted.key()] ?: BigDecimal.ZERO))
            }
        }

        // Quick stats over the current window (zero months count toward the average).
        val totalAcrossRange = currentSeries.fold(BigDecimal.ZERO) { acc, m -> acc + m.amount }
        val denom = currentSeries.size.coerceAtLeast(1).toBigDecimal()
        val averagePerMonth = totalAcrossRange.divide(denom, 2, RoundingMode.HALF_EVEN)
        val medianAmount = median(currentSeries.map { it.amount })

        val highest = currentSeries.maxByOrNull { it.amount }?.takeIf { it.amount.signum() > 0 }
            ?.let { ExplorerMonthLabel(it.year, it.month, it.amount) }
        val lowestNonZero = currentSeries.filter { it.amount.signum() > 0 }
            .minByOrNull { it.amount }
            ?.let { ExplorerMonthLabel(it.year, it.month, it.amount) }

        // Top descriptions: only meaningful for a single category.
        val topDescriptions: List<ExplorerDescriptionRow>? = if (effectiveType == "category") {
            val tx = transactions.findByHouseholdIdAndOccurrenceDateBetween(
                householdId,
                resolvedStart.atDay(1),
                today.atEndOfMonth(),
            )
            data class Bucket(var occurrences: Int = 0, var total: BigDecimal = BigDecimal.ZERO)
            val grouped = LinkedHashMap<String, Bucket>()
            for (t in tx) {
                if (t.direction != Direction.expense) continue
                if (t.categoryCode != effectiveCode) continue
                val key = (t.description ?: "").trim().ifEmpty { "" }
                val b = grouped.getOrPut(key) { Bucket() }
                b.occurrences += 1
                b.total += t.amount
            }
            grouped.entries
                .map { (desc, b) ->
                    val avg = if (b.occurrences == 0) BigDecimal.ZERO
                    else b.total.divide(BigDecimal.valueOf(b.occurrences.toLong()), 2, RoundingMode.HALF_EVEN)
                    ExplorerDescriptionRow(
                        description = desc,
                        occurrences = b.occurrences,
                        totalAmount = Money.normalize(b.total),
                        averagePerOccurrence = Money.normalize(avg),
                    )
                }
                .sortedByDescending { it.totalAmount }
        } else null

        val priorYearsAvailable = firstTxYm?.let {
            ChronoUnit.MONTHS.between(it, today).toInt() / 12
        } ?: 0

        return ExplorerResponse(
            scopeType = effectiveType,
            scopeCode = effectiveCode,
            months = currentSeries,
            priorMonths = priorSeries,
            priorYearsAvailable = priorYearsAvailable.coerceAtLeast(0),
            averagePerMonth = Money.normalize(averagePerMonth),
            medianPerMonth = Money.normalize(medianAmount),
            highestMonth = highest,
            lowestNonZeroMonth = lowestNonZero,
            topDescriptions = topDescriptions,
        )
    }

    @Transactional(readOnly = true)
    fun yearsAvailable(householdId: UUID): YearsAvailableResponse {
        val bounds = transactions.dateBounds(householdId)
        val first = bounds.minDate ?: return YearsAvailableResponse(emptyList())
        val last = bounds.maxDate ?: return YearsAvailableResponse(emptyList())
        val years = (first.year..last.year).sortedDescending()
        return YearsAvailableResponse(years)
    }
}
