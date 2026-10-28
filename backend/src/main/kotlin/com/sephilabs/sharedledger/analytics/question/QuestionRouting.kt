package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.transaction.Direction

/** Why a question asked from Explorer opens the transactions list instead. */
enum class RouteReason { income, refunds, no_category, open_start }

/** [target] is where the filters are shown; [expressibleIn] every view that could show them, for the cross-link. */
data class QuestionRoute(val target: QuestionTarget, val reason: RouteReason?, val expressibleIn: Set<QuestionTarget>)

/** Deterministic: the transactions list can show any valid filters (with several categories it shows them all and
 *  says it filters one at a time); Explorer only expenses of one or more categories or groups (several fill its
 *  stacked panel) over a period with a start. A question goes where it was asked, unless Explorer cannot show it. */
object QuestionRouting {

    fun route(filters: QuestionFilters, askedFrom: QuestionTarget, catalogue: List<CatalogueEntry>): QuestionRoute {
        val explorerProblem = explorerProblem(filters, catalogue)
        val expressibleIn = if (explorerProblem == null) {
            setOf(QuestionTarget.explorer, QuestionTarget.transactions)
        } else {
            setOf(QuestionTarget.transactions)
        }
        if (askedFrom == QuestionTarget.transactions || explorerProblem != null) {
            val reason = explorerProblem.takeIf { askedFrom == QuestionTarget.explorer }
            return QuestionRoute(QuestionTarget.transactions, reason, expressibleIn)
        }
        return QuestionRoute(QuestionTarget.explorer, null, expressibleIn)
    }

    private fun explorerProblem(filters: QuestionFilters, catalogue: List<CatalogueEntry>): RouteReason? {
        val expenseOnly = filters.scopes.all { scope ->
            val directions = when (scope.type) {
                ScopeType.category -> catalogue.filter { it.code == scope.code }.map { it.direction }
                ScopeType.group -> catalogue.filter { it.group == scope.code }.map { it.direction }
            }
            Direction.expense in directions
        }
        return when {
            filters.direction == Direction.income || !expenseOnly -> RouteReason.income
            filters.isRefund -> RouteReason.refunds
            filters.scopes.isEmpty() -> RouteReason.no_category
            filters.range is QuestionRange.Between && filters.range.from == null -> RouteReason.open_start
            else -> null
        }
    }
}
