package com.sephilabs.sharedledger.identity.user

/**
 * How a user prefers the portfolio Realized % and Total return % to be based. The id is the
 * wire/storage format (users.portfolio_return_basis and the /auth/me payload) and must match
 * the frontend registry in features/portfolio/valuation.ts.
 *
 * Purely presentational: it selects the percentage denominators only — the euro amounts are
 * identical in every mode.
 */
enum class PortfolioReturnBasis(val id: String) {
    // Realized and total return over the cost of the currently-held (open) lots. Unrealized,
    // realized and total then share one base, so the percentages add up like the euro amounts.
    OPEN_COST("OPEN_COST"),

    // Realized over the cost of all sold lots, total return over open + sold cost. Grows with
    // sell-and-rebuy churn; the historical behavior before this preference existed.
    TURNOVER("TURNOVER"),

    // All three percentages over the net money contributed from outside the portfolio
    // (open-lots cost − realized P&L, so buys funded by prior sales cancel out) — "your own
    // money currently at risk". Like OPEN_COST, one shared base, so the percentages add up.
    NET_INVESTED("NET_INVESTED"),
    ;

    companion object {
        val DEFAULT: PortfolioReturnBasis = OPEN_COST
        val ids: Set<String> = entries.map { it.id }.toSet()
    }
}
