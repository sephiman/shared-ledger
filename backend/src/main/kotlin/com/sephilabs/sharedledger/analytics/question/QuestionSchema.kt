package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.transaction.Direction
import java.time.LocalDate

enum class QuestionTarget { explorer, transactions }

enum class ScopeType { group, category }

enum class QuestionLanguage { en, es }

data class ScopeRef(val type: ScopeType, val code: String)

sealed interface QuestionRange {
    /** One of [QuestionSchema.RANGE_PRESETS], resolved by the app exactly as the range selector does. */
    data class Preset(val preset: String) : QuestionRange

    /** Either end may be open ("since March"), never both. */
    data class Between(val from: LocalDate?, val to: LocalDate?) : QuestionRange
}

/** The filters the two views share, plus [priorYear], which only Explorer has (the list ignores it). The model fills
 *  these and nothing else; which view shows them is the app's decision ([QuestionRouting]). One scope fills
 *  Explorer's main panel, several its stacked panel. A null range means the question named no period: all time. */
data class QuestionFilters(
    val direction: Direction?,
    val scopes: List<ScopeRef>,
    val range: QuestionRange?,
    val isRefund: Boolean,
    val priorYear: Boolean,
)

/** Filters the views do not have. Only the kind is ever logged: a label can echo words of the question. */
enum class UnsupportedKind { member, account, merchant, text, amount, other }

data class UnsupportedFilter(val kind: UnsupportedKind, val label: String)

/**
 * The contract between the prompt, the parser and the validator: what the model may answer and what the app
 * accepts. Changing a field, a preset or a rule here changes what the model is told: bump [VERSION].
 */
object QuestionSchema {

    const val VERSION = "q1"

    /** The range selector's presets, minus "custom", which is a from–to pair instead. "all" ends today. */
    val RANGE_PRESETS = listOf("3m", "6m", "ytd", "1y", "2y", "all")

    const val MAX_UNSUPPORTED = 5
    const val MAX_UNSUPPORTED_LABEL = 40

    /** Explorer's month-bucketed endpoints refuse wider spans; a question may not ask for more. */
    const val MAX_SPAN_YEARS = 20L

    /** Both views filter by the real group of a category, so a category without one is reachable only by its code. */
    fun groups(catalogue: List<CatalogueEntry>): Set<String> = catalogue.mapNotNull { it.group }.toSet()
}
