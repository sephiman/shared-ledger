package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Builds the Month-projection AI prompt. Only the question, today's date, language and category catalogue go out;
 *  no amounts, balances or transactions ever leave the server. */
object ProjectionQuestionPrompt {

    const val VERSION = "pq1"

    private const val TASK =
        "You turn a member's question about their current month's Money Flow Projection into either a what-if " +
            "scenario overlay, a deterministic explanation request, or both. Never compute or invent household figures " +
            "and never invent category or group codes: use only codes from the catalogue below."

    private val SCENARIO = """
        ## What-if scenario ("scenario")
        {"freeze": [{"type": "category" or "group", "code": "<code>"}], "adjustments": [{"scope": {"type": "category" or "group", "code": "<code>"}, "kind": "amount" or "percent", "value": <number>}], "level": "group" or "category" or null, "windowMonths": <1..24> or null, "modeOverride": "ENVELOPE" or "PRORATE" or null, "savingsTarget": <number >= 0> or null}
        - "freeze": categories or groups to cut back to actuals-only (no more spending or income expected this month). Use when the member asks "what if we spend nothing more on X", "freeze dining", etc.
        - "adjustments": explicit +/- changes to a category or group mentioned in the question ("extra 400 on car" -> kind "amount", value 400; "cut groceries by 20%" -> kind "percent", value -20; "500 bonus" -> salary/income +500 amount).
        - "level": "category" or "group" only when the member explicitly asks to switch breakdown level; otherwise null.
        - "windowMonths": integer 1..24 only when the member asks for a specific moving-average window (e.g. "12-month window" -> 12); otherwise null.
        - "modeOverride": "PRORATE" or "ENVELOPE" only when the member asks to preview prorated vs envelope estimation; otherwise null.
        - "savingsTarget": non-negative number when the member asks about reaching a savings goal or breaking even ("save 500" -> 500, "break even" -> 0); otherwise null.
        Set "scenario" to null if the question is purely asking for an explanation.
    """.trimIndent()

    private val EXPLAIN = """
        ## Explanation request ("explain")
        {"kind": "node" | "recurring_due" | "top_expenses" | "deficit_drivers" | "outlook", "scope": {"type": "category" or "group", "code": "<code>"} or null}
        - "node": explain why a specific category or group is projected to its amount (requires "scope").
        - "recurring_due": list recurring bills/income still due this month.
        - "top_expenses": list the largest projected expense categories/groups.
        - "deficit_drivers": explain what is driving a deficit or eating into savings.
        - "outlook": summarize the overall month-end projection (income, expenses, saved/deficit, remaining due).
        Set "explain" to null if the question is purely a what-if scenario.
    """.trimIndent()

    private const val OUTPUT =
        "## Output\nAnswer with one JSON object and nothing else — no prose, no markdown fences:\n" +
            "{\"scenario\": {...} or null, \"explain\": {...} or null, " +
            "\"unsupported\": [{\"kind\": \"member\"|\"account\"|\"merchant\"|\"text\"|\"amount\"|\"other\", \"label\": \"...\"}], " +
            "\"confidence\": <0.0-1.0>}\n" +
            "Leave \"unsupported\" as [] unless the question asks for something the projection cannot do (e.g. filtering by member, account, or merchant). " +
            "A question unrelated to the household's month projection gets a low confidence."

    fun system(catalogue: List<CatalogueEntry>, today: LocalDate, language: QuestionLanguage): String {
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val languageName = Locale.forLanguageTag(language.name).getDisplayLanguage(Locale.ENGLISH)
        val groups = catalogue.mapNotNull { it.group }.distinct().sorted()
        return listOf(
            TASK,
            "## Today and language\nToday is $today ($weekday). Write every \"label\" in $languageName.",
            "## Category catalogue\ncode | name | group | direction\n" +
                catalogue.joinToString("\n") { "${it.code} | ${it.name} | ${it.group.orEmpty()} | ${it.direction}" } +
                "\nGroups: ${groups.joinToString(", ")}.",
            SCENARIO,
            EXPLAIN,
            OUTPUT,
        ).joinToString("\n\n")
    }

    fun user(question: String): String = question.trim()
}
