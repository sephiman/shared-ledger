package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.transaction.Direction
import tools.jackson.databind.json.JsonMapper
import java.time.YearMonth

data class CatalogueEntry(
    val code: String,
    val name: String,
    val group: String?,
    val direction: Direction,
    val essential: Boolean,
)

data class RuleExample(
    val field: RuleField,
    val op: RuleOp,
    val value: String,
    val direction: Direction,
    val categoryCode: String,
)

/** Only these fields of a movement ever reach the provider — no amount, no account, no household. */
data class PromptMovement(
    val id: String,
    val counterparty: String?,
    val description: String?,
    val direction: Direction,
    val bookingMonth: YearMonth,
)

/** The experiment's V2 prompt (catalogue + sampled rules, no amount) plus the field-weighting paragraph.
 *  Changing any text here changes measured behaviour: bump [VERSION] so logs show which prompt ran. */
object CategorySuggestionPrompt {

    const val VERSION = "v2.1"
    const val RULES_IN_PROMPT = 150

    private const val TASK =
        "You categorise the bank movements of a household's shared ledger. Its accounts are Dutch and Spanish " +
            "banks.\n" +
            "Decide, for each movement, what kind of merchant or concept it represents, and pick the best category " +
            "from the catalogue below. Do not match strings mechanically: use the counterparty and the bank " +
            "description together, plus your general knowledge of merchants, brands, banks and Dutch and Spanish " +
            "billing conventions. An expense movement must get an expense category; an income movement must get " +
            "an income category."

    const val FIELD_WEIGHTING =
        "Field weighting: the counterparty is the primary signal — it is the merchant or payer name. The bank " +
            "description is supporting context. When the counterparty is empty or generic (a bank, a payment " +
            "processor, a person-to-person payment service), the description is the main signal; Spanish bank " +
            "lines such as \"/TXT/D|RECIB /Vodafone Servicios\" carry the concept after the /TXT/ prefix."

    private const val RULES_HEADER =
        "## How this household has categorised some counterparties before\n" +
            "These are EXAMPLES of the household's vocabulary and habits, not a lookup table. Most movements you " +
            "will see are from counterparties not listed here: classify those by the same logic (e.g. if one " +
            "supermarket is Groceries, another supermarket is Groceries too).\n" +
            "field | match | value | direction | category"

    private const val OUTPUT =
        "## Output\nAnswer with a JSON array and nothing else — no prose, no markdown fences:\n" +
            "[{\"id\": \"<movement id>\", \"categoryCode\": \"<code from the catalogue>\", \"confidence\": <0.0-1.0>}]\n" +
            "Exactly one entry per movement. confidence is your probability that the category is right."

    private val json = JsonMapper.builder().build()

    fun system(catalogue: List<CatalogueEntry>, rules: List<RuleExample>): String {
        val parts = mutableListOf(
            "$TASK\n$FIELD_WEIGHTING",
            "## Category catalogue\ncode | name | group | direction | essential\n" +
                catalogue.joinToString("\n") {
                    "${it.code} | ${it.name} | ${it.group.orEmpty()} | ${it.direction} | ${if (it.essential) "yes" else "no"}"
                },
        )
        val examples = sampleRules(rules)
        if (examples.isNotEmpty()) {
            parts += RULES_HEADER + "\n" + examples.joinToString("\n") {
                "${it.field} | ${it.op} | ${IbanRedaction.redact(it.value)} | ${it.direction} | ${it.categoryCode}"
            }
        }
        parts += OUTPUT
        return parts.joinToString("\n\n")
    }

    fun user(movements: List<PromptMovement>): String =
        "Categorise these movements (one JSON object per line):\n" + movements.joinToString("\n") {
            json.writeValueAsString(
                linkedMapOf(
                    "id" to it.id,
                    "counterparty" to IbanRedaction.redact(it.counterparty),
                    "description" to IbanRedaction.redact(it.description),
                    "direction" to it.direction.name,
                    "bookingMonth" to it.bookingMonth.toString(),
                ),
            )
        }

    /** Round-robin across categories (in [rules]' order within each) up to the cap, so one category with
     *  dozens of learned rules cannot crowd the others out. Amount rules are left out: no amounts leave. */
    fun sampleRules(rules: List<RuleExample>, cap: Int = RULES_IN_PROMPT): List<RuleExample> {
        val buckets = rules.filter { it.field != RuleField.amount }
            .groupBy { it.categoryCode }
            .toSortedMap()
            .values.map { ArrayDeque(it) }
        val sampled = mutableListOf<RuleExample>()
        while (sampled.size < cap && buckets.any { it.isNotEmpty() }) {
            for (bucket in buckets) {
                if (sampled.size >= cap) break
                bucket.removeFirstOrNull()?.let { sampled += it }
            }
        }
        return sampled
    }
}
