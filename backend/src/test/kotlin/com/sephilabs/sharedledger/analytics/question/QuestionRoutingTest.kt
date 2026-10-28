package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Which view shows the filters is the app's decision, never the model's. */
class QuestionRoutingTest {

    private val catalogue = listOf(
        CatalogueEntry("outings.restaurants", "Restaurants", "outings", Direction.expense, essential = false),
        CatalogueEntry("groceries.groceries", "Groceries", "groceries", Direction.expense, essential = true),
        CatalogueEntry("income.salary", "Salary", null, Direction.income, essential = false),
    )
    private val restaurants = ScopeRef(ScopeType.category, "outings.restaurants")
    private val groceries = ScopeRef(ScopeType.group, "groceries")
    private val ytd = QuestionRange.Preset("ytd")
    private val both = setOf(QuestionTarget.explorer, QuestionTarget.transactions)

    private fun filters(direction: Direction? = Direction.expense, scopes: List<ScopeRef> = listOf(restaurants), range: QuestionRange? = ytd, isRefund: Boolean = false) =
        QuestionFilters(direction, scopes, range, isRefund, priorYear = false)

    private fun route(filters: QuestionFilters, from: QuestionTarget) = QuestionRouting.route(filters, from, catalogue)

    @Test
    fun `an expense question about a category asked from Explorer stays on Explorer, and could also be listed`() {
        assertThat(route(filters(), QuestionTarget.explorer)).isEqualTo(QuestionRoute(QuestionTarget.explorer, null, both))
    }

    @Test
    fun `a group, or a category without a direction, is enough for Explorer`() {
        assertThat(route(filters(null, listOf(groceries), null), QuestionTarget.explorer).target).isEqualTo(QuestionTarget.explorer)
        assertThat(route(filters(null, range = null), QuestionTarget.explorer).target).isEqualTo(QuestionTarget.explorer)
    }

    @Test
    fun `several categories asked from Explorer fill its stacked panel, still linking to the list`() {
        assertThat(route(filters(scopes = listOf(groceries, restaurants)), QuestionTarget.explorer))
            .isEqualTo(QuestionRoute(QuestionTarget.explorer, null, both))
    }

    @Test
    fun `several categories asked from the list stay on the list, which offers Explorer for the full set`() {
        assertThat(route(filters(scopes = listOf(groceries, restaurants)), QuestionTarget.transactions))
            .isEqualTo(QuestionRoute(QuestionTarget.transactions, null, both))
    }

    @Test
    fun `how much income so far asked from Explorer opens the transactions list, saying why`() {
        assertThat(route(filters(Direction.income, emptyList(), QuestionRange.Preset("all")), QuestionTarget.explorer))
            .isEqualTo(QuestionRoute(QuestionTarget.transactions, RouteReason.income, setOf(QuestionTarget.transactions)))
    }

    @Test
    fun `an income category, even among expense ones, opens the transactions list for the same reason`() {
        val salary = ScopeRef(ScopeType.category, "income.salary")
        assertThat(route(filters(null, listOf(salary)), QuestionTarget.explorer).reason).isEqualTo(RouteReason.income)
    }

    @Test
    fun `Explorer needs a category or group, cannot show refunds alone, and needs a start to a period`() {
        assertThat(route(filters(scopes = emptyList()), QuestionTarget.explorer).reason).isEqualTo(RouteReason.no_category)
        assertThat(route(filters(isRefund = true), QuestionTarget.explorer).reason).isEqualTo(RouteReason.refunds)
        assertThat(route(filters(range = QuestionRange.Between(null, LocalDate.of(2026, 6, 30))), QuestionTarget.explorer).reason)
            .isEqualTo(RouteReason.open_start)
    }

    @Test
    fun `asked from the transactions list, the answer always stays on the list, offering Explorer when it fits`() {
        assertThat(route(filters(), QuestionTarget.transactions)).isEqualTo(QuestionRoute(QuestionTarget.transactions, null, both))
        assertThat(route(filters(Direction.income, emptyList(), QuestionRange.Preset("all")), QuestionTarget.transactions))
            .isEqualTo(QuestionRoute(QuestionTarget.transactions, null, setOf(QuestionTarget.transactions)))
    }
}
