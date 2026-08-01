package com.sephilabs.sharedledger.bank

/**
 * Consent lifecycle for a connection, surfaced in Settings. The last two are credential states, not
 * consent states — what a connection shows instead of failing silently: [credentials_required] when
 * the household has none configured (the expected state right after the per-household migration),
 * [credentials_mismatch] when the configured application isn't the one the bank granted the consent
 * to, so re-linking is the only fix. Both are re-checked on every sync and clear themselves.
 */
enum class ConnectionStatus { active, expired, suspended, error, credentials_required, credentials_mismatch }

/** How often the scheduled sync runs a connection (within the ≤4 calls/consent/day budget). */
enum class SyncFrequency { daily, twice_daily }

/** Review-inbox lifecycle. `confirmed` items are linked to a generated transaction. */
enum class MovementStatus { pending, confirmed, rejected }

/** Filters the pending inbox by whether a movement already carries a (suggested) category. */
enum class PendingCategorisation { categorized, uncategorized }

/** What a categorisation rule matches on. */
enum class RuleField { counterparty, description, amount }

/** How the rule value is compared. `range` uses "min..max" against the amount. */
enum class RuleOp { equals, contains, range }

/** Whether a rule was authored by the user or learned from a confirmation. */
enum class RuleSource { manual, learned }

/** Outcome recorded for one sync run. */
enum class SyncRunStatus { success, error }
