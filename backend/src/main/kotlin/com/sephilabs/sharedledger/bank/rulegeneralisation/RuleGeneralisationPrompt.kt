package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.bank.aisuggestion.IbanRedaction
import com.sephilabs.sharedledger.transaction.Direction
import tools.jackson.databind.json.JsonMapper

/** One rule as the provider sees it: a per-run id (`r1…rN`) instead of the rule's own. */
data class PromptRule(
    val id: String,
    val field: RuleField,
    val op: RuleOp,
    val value: String,
    val direction: Direction,
    val categoryCode: String,
)

/** Changing any text here changes measured behaviour: bump [VERSION] so logs show which prompt ran. */
object RuleGeneralisationPrompt {

    const val VERSION = "rg-v2"

    private const val TASK =
        "You tidy up the categorisation rules of a household's shared ledger. Its accounts are Dutch and Spanish " +
            "banks. Each rule assigns a category to the bank movements whose counterparty or bank description " +
            "matches its value, exactly (equals) or as a substring (contains). Many rules were learned one " +
            "counterparty at a time, so the same merchant appears many times with different branch numbers, cities " +
            "or wordings.\n" +
            "Group the rules of the same field whose values belong to the same merchant, brand, employer, " +
            "institution or recurring payee AND share the same direction and category. For each group, propose one " +
            "rule \"<field> contains <pattern>\" on that same field that covers every rule of the group.\n" +
            "The pattern:\n" +
            "- must appear literally inside every value of its group (matching is a case-insensitive substring " +
            "test, nothing smarter); a contains rule's value is itself a substring, so the pattern must be part " +
            "of it;\n" +
            "- must be the distinctive part of the name as it appears in the values — not a city, not a legal " +
            "suffix (B.V., S.L.U., N.V.), not a payment-processor prefix (CCV*, BCK*, Zettle, SumUp, iZ*), not a " +
            "generic word;\n" +
            "- must be at least 3 characters long.\n" +
            "Never mix counterparty and description rules in one group. When one merchant appears under " +
            "wordings that share no such pattern, propose separate groups or leave them out. Never propose a group " +
            "of one rule."

    private const val OUTPUT =
        "## Output\nAnswer with a JSON array and nothing else — no prose, no markdown fences:\n" +
            "[{\"pattern\": \"<text>\", \"field\": \"<counterparty|description>\", \"direction\": \"<expense|income>\", \"categoryCode\": \"<code from the " +
            "catalogue>\", \"absorbs\": [\"<rule id>\", ...], \"confidence\": <0.0-1.0>}]\n" +
            "confidence is your probability that every absorbed rule is the same payee and the pattern names it. " +
            "If there is nothing to group, answer []."

    private val json = JsonMapper.builder().build()

    fun system(catalogue: List<CatalogueEntry>): String = listOf(
        TASK,
        "## Category catalogue\ncode | name | group | direction\n" +
            catalogue.joinToString("\n") { "${it.code} | ${it.name} | ${it.group.orEmpty()} | ${it.direction}" },
        OUTPUT,
    ).joinToString("\n\n")

    fun user(rules: List<PromptRule>): String =
        "The household's rules (one JSON object per line):\n" + rules.joinToString("\n") {
            json.writeValueAsString(
                linkedMapOf(
                    "id" to it.id,
                    "field" to it.field.name,
                    "match" to it.op.name,
                    "value" to IbanRedaction.redact(it.value),
                    "direction" to it.direction.name,
                    "category" to it.categoryCode,
                ),
            )
        }
}
