package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.analytics.question.QuestionLanguage
import java.time.LocalDate
import java.util.Locale

/** Builds the FIRE What-If translator prompt. Only the question, today's date, language, and fixed
 *  event template names leave the server — zero household amounts, balances, or settings ever go out. */
object FireQuestionPrompt {

    const val VERSION = FireQuestionSchema.VERSION

    fun system(today: LocalDate, language: QuestionLanguage): String {
        val languageName = Locale.forLanguageTag(language.name).getDisplayLanguage(Locale.ENGLISH)
        return """
You translate a household member's natural-language "what-if" question about their FIRE retirement plan into structured sandbox changes. You never see or compute any household balances or figures.

## Today and language
Today is $today (year ${today.year}). Write every "label" in $languageName.

## Output schema ("whatIf")
{
  "monthlyContribution": {"kind": "set" | "delta" | "percent", "value": <number>} or null,
  "monthlySpending": {"kind": "set" | "delta" | "percent", "value": <number>} or null,
  "targetYear": {"kind": "set" | "delta", "value": <integer>} or null,
  "safeWithdrawalRatePct": {"kind": "set" | "delta" | "percent", "value": <number>} or null,
  "lumpSum": <number >= 0> or null,
  "returnShiftPct": <number -20..20> or null,
  "cashFlowEvents": [
    {
      "year": <integer> or null,
      "yearsFromNow": <integer >= 1> or null,
      "kind": "lump_sum" | "contribution_delta" | "spending_delta",
      "amount": <non-zero number>,
      "label": "<short label>",
      "template": "mortgage_end" | "state_pension" | "part_time_income" | "big_expense" | "windfall" | null
    }
  ]
}

Rules for each field:
- "monthlyContribution": ongoing monthly saving/investing change starting now. "save 300 more" -> {"kind":"delta","value":300}; "contribute 1500 a month" -> {"kind":"set","value":1500}; "save 20% more" -> {"kind":"percent","value":20}.
- "monthlySpending": ongoing monthly retirement/living spending change starting now. "spend 10% less" -> {"kind":"percent","value":-10}; "spend 250 less" -> {"kind":"delta","value":-250}; "live on 2200/month" -> {"kind":"set","value":2200}.
- "targetYear": horizon year. "retire in 2038" -> {"kind":"set","value":2038}; "retire 3 years earlier" -> {"kind":"delta","value":-3}; "delay by 2 years" -> {"kind":"delta","value":2}.
- "safeWithdrawalRatePct": e.g. "3.5% withdrawal rate" -> {"kind":"set","value":3.5}; "lower SWR by 0.5 points" -> {"kind":"delta","value":-0.5}.
- "lumpSum": one-off immediate addition to portfolio today (>= 0), e.g. "add 15000 today". For a future one-off amount, use "cashFlowEvents" instead.
- "returnShiftPct": percentage points added/subtracted from expected returns (-20..20), e.g. "returns 1% lower" -> -1, "2 points higher returns" -> 2.
- "cashFlowEvents": future life events in today's money. Provide either "year" or "yearsFromNow" (>= 1), never an already-past year.
  - "lump_sum": positive = windfall/inheritance/bonus ("windfall"), negative = one-off major expense ("big_expense").
  - "spending_delta": monthly spending change from that year onward. Negative = spending decreases / offset ("mortgage_end", "state_pension", or start of "part_time_income"); positive = spending increases (or end of "part_time_income" — emit two events for a bounded part-time stretch, negative at start year and matching positive at end year).
  - "contribution_delta": monthly contribution change from that year onward (positive = contribute more, negative = contribute less).
- "unsupported": list of requested changes that the What-If sandbox cannot express (kinds: "tax", "inflation", "asset_class", "tier", "other").
- Set "whatIf" to null if the question asks for nothing expressible or is unrelated to FIRE planning (give a low "confidence").

## Output
Answer with exactly one JSON object and nothing else — no prose, no markdown fences:
{"whatIf": {...} or null, "unsupported": [{"kind": "tax"|"inflation"|"asset_class"|"tier"|"other", "label": "..."}], "confidence": <0.0-1.0>}
""".trim()
    }

    fun user(question: String): String = question.trim()
}
