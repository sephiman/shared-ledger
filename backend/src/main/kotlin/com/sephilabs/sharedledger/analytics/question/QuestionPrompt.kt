package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** The one builder of the question prompt. Changing any text here changes measured behaviour: bump [VERSION] so
 *  logs show which prompt ran. Only the question, today's date, the language and the catalogue go out. */
object QuestionPrompt {

    const val VERSION = "q2.1"

    private const val TASK =
        "You turn a member's question about their household's money into the filters of a shared-ledger app. " +
            "You only translate the question into filters: the app then shows its own view with those filters and " +
            "the member reads the figures there. Never answer the question itself, never compute, " +
            "estimate or mention amounts, and never invent a category: use only codes from the catalogue below."

    private val FILTERS = """
        ## Filters
        {"direction": "expense" or "income" or null, "categories": [{"type": "category" or "group", "code": "<code>"}], "range": {"preset": <preset>} or {"from": "YYYY-MM-DD" or null, "to": "YYYY-MM-DD" or null} or null, "isRefund": true or false, "priorYear": true or false}
        - "direction": what the question is about, or null when it does not say. Every category or group must agree with it. Refunds are expenses.
        - "categories": the category or group the question is about, or several categories or groups when it compares or splits them; [] when it names none.
        - "range" presets: ${QuestionSchema.RANGE_PRESETS.joinToString(", ") { "\"$it\"" }}. "3m", "6m", "1y" and "2y" are that many months back including the current one; "ytd" starts on 1 January of this year; "all" is the whole history up to today. Use a preset only when the period is exactly one of them; otherwise give from and/or to ("since March" has no "to"). null when the question names no period.
        - Open-ended periods: "so far", "to date", "hasta ahora" or "hasta la fecha" with no other period mean {"preset": "all"}. "so far this year" or "en lo que va de año" is {"preset": "ytd"}, not "all".
        - "isRefund": true only when the question is about refunds.
        - "priorYear": true only when the question compares with the same period a year earlier.
        Which screen shows these filters is not yours to decide: return the filters only.
    """.trimIndent()

    private val NOT_EXISTING = """
        ## Filters that do NOT exist
        Neither view can filter by member (who paid or recorded it), by account or bank, by merchant, shop, place, city or any free text in a description, or by amount (more than, less than, between). When the question asks for any of these, do not force it into another filter: leave it out and report it in "unsupported" with a "kind" of "member", "account", "merchant", "text", "amount" or "other", and a "label" of one to three words naming what cannot be filtered (e.g. "member", "city"), to be read after "can't filter by". If nothing in the question maps to a filter, answer "filters": null with the "unsupported" entries.
    """.trimIndent()

    private const val OUTPUT =
        "## Output\nAnswer with one JSON object and nothing else — no prose, no markdown fences:\n" +
            "{\"filters\": {...} or null, \"unsupported\": [{\"kind\": \"...\", \"label\": \"...\"}], " +
            "\"confidence\": <0.0-1.0>}\n" +
            "confidence is your probability that the filters capture what the member asked. A question that is not " +
            "about the household's money, or that you cannot map without guessing, gets a low confidence."

    fun system(catalogue: List<CatalogueEntry>, today: LocalDate, language: QuestionLanguage): String {
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val languageName = Locale.forLanguageTag(language.name).getDisplayLanguage(Locale.ENGLISH)
        val groups = catalogue.mapNotNull { it.group }.distinct().sorted()
        return listOf(
            TASK,
            "## Today and language\n" +
                "Today is $today ($weekday). The current month ends on $monthEnd.\n" +
                "Resolve relative dates (\"this month\", \"last summer\", \"since the year started\") from today into " +
                "concrete dates. No date may be after $monthEnd, and no range may start after today.\n" +
                "The member writes in $languageName; write every \"label\" in $languageName.",
            "## Category catalogue\ncode | name | group | direction\n" +
                catalogue.joinToString("\n") { "${it.code} | ${it.name} | ${it.group.orEmpty()} | ${it.direction}" } +
                "\nGroups: ${groups.joinToString(", ")}. A group covers all of its categories.",
            FILTERS,
            NOT_EXISTING,
            OUTPUT,
        ).joinToString("\n\n")
    }

    fun user(question: String): String = question.trim()
}
