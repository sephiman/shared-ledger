package com.sephilabs.sharedledger.bank

/** Consent lifecycle for a connection. The last two are credential states, not consent states:
 *  [credentials_required] when the household configured none, [credentials_mismatch] when the configured
 *  application isn't the one the bank granted to. Both re-check on every sync and clear themselves. */
enum class ConnectionStatus { active, expired, suspended, error, credentials_required, credentials_mismatch }

/** How often the scheduled sync runs a connection (within the ≤4 calls/consent/day budget). */
enum class SyncFrequency { daily, twice_daily }

/** Review-inbox lifecycle. `confirmed` items are linked to a generated transaction. */
enum class MovementStatus { pending, confirmed, rejected }

/** Filters the pending inbox by whether a movement already carries a (suggested) category. */
enum class PendingCategorisation { categorized, uncategorized }

enum class RuleField { counterparty, description, amount }

/** How the rule value is compared. `range` uses "min..max" against the amount. */
enum class RuleOp { equals, contains, range }

/** Whether a rule was authored by the user or learned from a confirmation. */
enum class RuleSource { manual, learned }

enum class SyncRunStatus { success, error }
