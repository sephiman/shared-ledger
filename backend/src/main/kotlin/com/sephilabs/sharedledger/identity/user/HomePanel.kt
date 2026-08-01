package com.sephilabs.sharedledger.identity.user

/** The Home panels a user can hide. Ids are the wire/storage format (users.hidden_home_panels CSV and the
 *  /auth/me payload) and must match the frontend registry in lib/homePanels.ts. The always-visible parts
 *  of Home (month/year summary, expenses chart) are intentionally not listed. */
enum class HomePanel(val id: String) {
    SAVINGS_RATE("savings_rate"),
    COST_OF_LIVING("cost_of_living"),
    CURRENT_WEALTH("current_wealth"),
    PORTFOLIO("portfolio"),
    MONEY_LENT("money_lent"),
    PENDING_BANK("pending_bank"),
    ;

    companion object {
        val ids: Set<String> = entries.map { it.id }.toSet()
    }
}
