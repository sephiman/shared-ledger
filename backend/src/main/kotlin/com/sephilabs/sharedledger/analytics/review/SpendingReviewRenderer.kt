package com.sephilabs.sharedledger.analytics.review

import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Month
import java.time.format.TextStyle
import java.util.Currency
import java.util.Locale

data class RenderedFindingText(
    val findingId: String,
    val title: String,
    val detail: String,
    val usedModel: Boolean,
    val fallbackReason: String? = null,
)

data class RenderedReview(
    val summary: String?,
    val summaryUsedModel: Boolean,
    val summaryFallbackReason: String? = null,
    val findings: List<RenderedFindingText>,
) {
    val modelWrittenCount: Int get() = findings.count { it.usedModel } + (if (summary != null && summaryUsedModel) 1 else 0)
    val templateFallbackCount: Int get() = findings.count { !it.usedModel } + (if (summary != null && !summaryUsedModel) 1 else 0)
}

/** Formats every figure deterministically, validates AI-written placeholder prose against raw-number
 *  and banned-term rules, and falls back per finding (or summary) to localized templates. */
object SpendingReviewRenderer {

    private val PLACEHOLDER_REGEX = Regex("""\{([a-zA-Z0-9_]+)}""")
    private val ANY_BRACE_REGEX = Regex("""[{}]""")
    private val ANY_DIGIT_REGEX = Regex("""\d""")
    private val CORE_SUMMARY_KEYS = setOf("f1", "f2", "f4", "f5", "f7", "f8", "f9", "f10")

    private val BANNED_PATTERNS: List<Pair<String, Regex>> =
        SpendingReviewConstants.BANNED_TERMS_ALL.map { term ->
            term to Regex("""(?i)(?<![\p{L}\p{N}])${Regex.escape(term)}(?![\p{L}\p{N}])""")
        }

    private val RECURRING_KIND_LABEL_ES: Map<String, String> = mapOf(
        "outings.lottery" to "Lotería",
        "outings.subscriptions" to "Suscripción",
        "home.services" to "Suscripción",
        "outings.hobbies" to "Afición",
        "home.utilities" to "Suministro",
        "home.insurance_fees" to "Seguro",
        "home.mortgage" to "Hipoteca",
        "home.taxes" to "Impuesto",
        "home.rent" to "Alquiler",
    )

    private val DIAG_CAUSAL_CUE = Regex(
        """(?i)(due to|comes? from|came from|driven by|because|stems? from|caused by|explains?|explained by|result of|reflects?|thanks to|down to|weighed on|contribut\w*|reduced|dragged|pulled|pushed|offset|se debe|debió|debido a|proviene|viene de|porque|ya que|como tu|al ser|\bgracias\b|a causa de|explica|obedece a|redujeron|contribuyeron|pesaron|compensó|compenso|por unos ingresos|por los menores ingresos|por la caída|por un gasto|por el aumento|por el mayor gasto)""",
    )
    private val DIAG_INCOME_CAUSE = Regex(
        """(?i)(lower income|less income|weaker income|smaller income|drop in income|income drop|income shortfall|from income|to income|by income|income below|from lower income|to lower income|ingresos más bajos|ingresos mas bajos|menores ingresos|caída de ingresos|caida de ingresos|bajada de ingresos|descenso de ingresos|a los ingresos|de los ingresos|por los ingresos|unos ingresos bajos|sobre todo los ingresos)""",
    )
    private val DIAG_SPENDING_CAUSE = Regex(
        """(?i)(higher spending|higher expenses|more spending|more expenses|increased spending|increased expenses|spending increase|expense increase|rise in spending|from spending|to spending|by spending|from expenses|to expenses|by expenses|spending above|expenses above|mayor gasto|aumento del gasto|subida del gasto|gastos más altos|gastos mas altos|gasto más alto|gasto mas alto|al gasto|del gasto|por el gasto|a los gastos|por los gastos|sobre todo el gasto)""",
    )
    private val DIAG_INCOME_BLAME = Regex(
        """(?i)(shortfall\s+(?:[\p{L}\p{N}]+\s+){0,2}(?:comes? from|is due to|driven by|stems? from)\s+(?:lower\s+)?income|se debe a (?:unos )?(?:menores )?ingresos)""",
    )
    private val DIAG_SPENDING_BLAME = Regex(
        """(?i)(shortfall\s+(?:[\p{L}\p{N}]+\s+){0,2}(?:comes? from|is due to|driven by|stems? from)\s+(?:higher\s+)?(?:spending|expenses)|se debe al (?:mayor|aumento del) gasto)""",
    )
    private val DIAG_SPENDING_WORD = Regex("""(?i)\b(spending|expenses?|gasto|gastos)\b""")
    private val DIAG_INCOME_WORD = Regex("""(?i)\b(income|ingresos?)\b""")
    private val DIAG_DOM_SPENDING = Regex(
        """(?i)((?:spending|expenses?)\s+(?:[\p{L}\p{N}]+\s+){0,3}(?:more|larger|bigger|greater|most|heavier|mostly|mainly)|(?:mostly|mainly|chiefly|primarily|largely)\s+(?:[\p{L}\p{N}]+\s+){0,2}(?:spending|expenses?)|sobre todo (?:el )?gasto|(?:el )?gasto\s+(?:[\p{L}\p{N}]+\s+){0,3}(?:más|mas|mayor)|(?:más|mas) (?:el )?gasto)""",
    )
    private val DIAG_DOM_INCOME = Regex(
        """(?i)(income\s+(?:[\p{L}\p{N}]+\s+){0,3}(?:more|larger|bigger|greater|most|heavier|mostly|mainly)|(?:mostly|mainly|chiefly|primarily|largely)\s+(?:[\p{L}\p{N}]+\s+){0,2}income|sobre todo (?:los )?ingresos|(?:los )?ingresos\s+(?:[\p{L}\p{N}]+\s+){0,3}(?:más|mas|mayor)|(?:más|mas) (?:los )?ingresos)""",
    )
    private val DIAG_DOM_EQUAL = Regex("""(?i)\b(equal|equally|evenly|same extent|same share|igual|por igual|misma medida|partes iguales)\b""")
    private val DIAG_IN_LINE = Regex("""(?i)(in line|on track|close to|near|matched|en línea|en linea|acorde|cerca de|a la par|alineado)""")
    private val DIAG_ABOVE = Regex("""(?i)(above|exceeded|beat|higher than|ahead of|topped|surpassed|por encima|superó|supero|superior)""")
    private val DIAG_SHORTFALL = Regex("""(?i)(savings shortfall|shortfall comes|menor ahorro se debe|redujeron tu ahorro)""")

    fun formatFigure(slot: FigureSlot, currency: String, locale: String): String {
        val loc = toJavaLocale(locale)
        return when (slot.kind) {
            FigureKind.MONEY -> formatMoney(slot.value, currency, loc, wholeOnly = false)
            FigureKind.WHOLE_MONEY -> formatMoney(slot.value, currency, loc, wholeOnly = true)
            FigureKind.PERCENT -> formatPercent(slot.value, locale, loc)
            FigureKind.COUNT -> NumberFormat.getIntegerInstance(loc).format(slot.value.setScale(0, RoundingMode.HALF_UP).toLong())
            FigureKind.MULTIPLE -> formatMultiple(slot.value, loc)
        }
    }

    fun containsBannedTerm(text: String): String? =
        BANNED_PATTERNS.firstOrNull { (_, rx) -> rx.containsMatchIn(text) }?.first

    fun validateTitle(title: String?, exemptTokens: List<String> = emptyList()): String? {
        val t = title?.trim()
        if (t.isNullOrBlank()) return "missing_title"
        if (ANY_BRACE_REGEX.containsMatchIn(t)) return "placeholder_in_title"
        val cleaned = stripExemptTokens(t, exemptTokens)
        if (ANY_DIGIT_REGEX.containsMatchIn(cleaned)) return "raw_number_in_title"
        containsBannedTerm(t)?.let { return "banned_term:$it" }
        return null
    }

    fun validatePlaceholderProse(
        prose: String?,
        allowedKeys: Set<String>,
        requiredKeys: Set<String>,
        requireAtLeastOne: Boolean = true,
        exemptTokens: List<String> = emptyList(),
    ): String? {
        val p = prose?.trim()
        if (p.isNullOrBlank()) return "missing_text"
        val usedKeys = mutableSetOf<String>()
        for (m in PLACEHOLDER_REGEX.findAll(p)) {
            val k = m.groupValues[1]
            if (k !in allowedKeys) return "unknown_placeholder:$k"
            usedKeys += k
        }
        val stripped = PLACEHOLDER_REGEX.replace(p, "")
        if (ANY_BRACE_REGEX.containsMatchIn(stripped)) return "malformed_placeholder"
        val cleaned = stripExemptTokens(stripped, exemptTokens)
        if (ANY_DIGIT_REGEX.containsMatchIn(cleaned)) return "raw_number"
        if (requireAtLeastOne && usedKeys.isEmpty()) return "no_placeholders_used"
        for (req in requiredKeys) {
            if (req !in usedKeys) return "missing_required_placeholder:$req"
        }
        containsBannedTerm(stripped)?.let { return "banned_term:$it" }
        return null
    }

    private fun stripExemptTokens(text: String, tokens: List<String>): String {
        var out = text
        for (tok in tokens) {
            if (tok.isNotBlank() && tok.any { it.isDigit() }) {
                out = out.replace(tok, "", ignoreCase = true)
            }
        }
        return out
    }

    fun substitute(template: String, figures: List<FigureSlot>, currency: String, locale: String): String {
        val byKey = figures.associate { it.key to formatFigure(it, currency, locale) }
        return PLACEHOLDER_REGEX.replace(template) { m -> byKey[m.groupValues[1]] ?: m.value }
    }

    /** Renders the deterministic report skeleton (templates only, zero AI) using real local names. */
    fun renderDeterministic(
        review: SelectedReview,
        locale: String,
        categoryLabel: (String) -> String,
    ): RenderedReview {
        val summary = review.summaryFacts?.let { sf ->
            val tpl = summaryTemplate(sf, locale)
            substitute(tpl, sf.figures, review.currency, locale)
        }
        val findings = review.allFindings.map { f ->
            val (t, d) = findingTemplate(f, locale, categoryLabel, useLocalName = true)
            RenderedFindingText(
                findingId = f.id,
                title = t,
                detail = substitute(d, f.figures, review.currency, locale),
                usedModel = false,
                fallbackReason = null,
            )
        }
        return RenderedReview(summary = summary, summaryUsedModel = false, findings = findings)
    }

    /** Renders an AI writer response over [review]: validates each item (no raw digits, no banned terms,
     *  valid placeholders, no numbers in title) and falls back per item to deterministic templates when invalid/omitted. */
    fun renderWithModel(
        review: SelectedReview,
        modelOutput: JsonNode?,
        locale: String,
        categoryLabel: (String) -> String,
    ): RenderedReview {
        // 1. Summary (null in savings_only mode)
        var summaryText: String? = null
        var summaryUsedModel = false
        var summaryReason: String? = null
        review.summaryFacts?.let { sf ->
            val rawSummary = modelOutput?.path("summary")?.takeIf { it.isString }?.asString()?.trim()
            val allowed = sf.figures.map { it.key }.toSet()
            val err = validatePlaceholderProse(rawSummary, allowed, CORE_SUMMARY_KEYS, requireAtLeastOne = true)
                ?: validateSummaryDiagnosis(rawSummary!!, sf)
            if (err == null) {
                summaryText = substitute(rawSummary!!, sf.figures, review.currency, locale)
                summaryUsedModel = true
            } else {
                summaryText = substitute(summaryTemplate(sf, locale), sf.figures, review.currency, locale)
                summaryUsedModel = false
                summaryReason = err
            }
        }

        // 2. Index model findings by id (model may never add, drop, or reorder findings).
        val modelById = LinkedHashMap<String, Pair<String?, String?>>()
        val rawFindings = modelOutput?.path("findings")
        if (rawFindings != null && rawFindings.isArray) {
            for (node in rawFindings) {
                if (!node.isObject) continue
                val id = node.path("id").takeIf { it.isString }?.asString()?.trim() ?: continue
                if (id in modelById) continue
                val title = node.path("title").takeIf { it.isString }?.asString()?.trim()
                val detail = node.path("detail").takeIf { it.isString }?.asString()?.trim()
                modelById[id] = title to detail
            }
        }

        // 3. Render every deterministically selected finding in exact selector order.
        val rendered = review.allFindings.map { f ->
            val pair = modelById[f.id]
            val allowed = f.figures.map { it.key }.toSet()
            val required = requiredPlaceholdersFor(f)
            val exempt = listOfNotNull(f.payloadSubject, f.context["topDriverName"])
            val err = when {
                pair == null -> "omitted_by_model"
                else -> validateTitle(pair.first, exempt)
                    ?: validatePlaceholderProse(pair.second, allowed, required, requireAtLeastOne = true, exemptTokens = exempt)
            }
            if (err == null) {
                RenderedFindingText(
                    findingId = f.id,
                    title = pair!!.first!!,
                    detail = substitute(pair.second!!, f.figures, review.currency, locale),
                    usedModel = true,
                    fallbackReason = null,
                )
            } else {
                val (tplTitle, tplDetail) = findingTemplate(f, locale, categoryLabel, useLocalName = true)
                RenderedFindingText(
                    findingId = f.id,
                    title = tplTitle,
                    detail = substitute(tplDetail, f.figures, review.currency, locale),
                    usedModel = false,
                    fallbackReason = err,
                )
            }
        }

        return RenderedReview(
            summary = summaryText,
            summaryUsedModel = summaryUsedModel,
            summaryFallbackReason = summaryReason,
            findings = rendered,
        )
    }

    /** Reconstructs the deterministic [SelectedReview] snapshot from persisted `payloadJson` + `localLabelsJson`
     *  so the async runner renders strictly against the frozen snapshot sent to the provider. */
    fun renderFromPersistedSnapshot(
        payload: JsonNode,
        localLabels: JsonNode?,
        modelOutput: JsonNode?,
        locale: String,
        categoryLabel: (String) -> String,
    ): RenderedReview {
        val scope = payload.path("scope")
        val currency = scope.path("currency").asString("EUR")
        val periodYear = scope.path("periodYear").asInt(2026)
        val periodMonth = scope.path("periodMonth").asInt(1)
        val reviewKind = scope.path("reviewKind").asString("complete_month")
        val daysElapsed = scope.path("daysElapsed").asInt(30)
        val daysInMonth = scope.path("daysInMonth").asInt(30)

        fun readFigures(node: JsonNode): List<FigureSlot> {
            if (!node.isObject) return emptyList()
            val list = mutableListOf<FigureSlot>()
            for ((k, v) in node.properties()) {
                val role = v.path("role").asString(k)
                val kind = runCatching { FigureKind.valueOf(v.path("kind").asString("money").uppercase()) }
                    .getOrDefault(FigureKind.MONEY)
                val value = v.path("value").takeIf { it.isNumber }?.decimalValue() ?: BigDecimal.ZERO
                list += FigureSlot(k, role, kind, value)
            }
            return list
        }

        val summaryNode = payload.path("summaryFacts")
        val summaryFacts = if (summaryNode.isObject) {
            val figs = readFigures(summaryNode.path("figures"))
            val expDir = summaryNode.path("expensesDirection").asString("at")
            val incDir = summaryNode.path("incomeDirection").asString("at")
            val savDir = summaryNode.path("savingsDirection").asString("at")
            val expDeltaAbs = figs.firstOrNull { it.key == "f3" }?.value ?: BigDecimal.ZERO
            val incDeltaAbs = figs.firstOrNull { it.key == "f6" }?.value ?: BigDecimal.ZERO
            val (computedDiag, computedDetail) = SpendingReviewSelector.diagnoseSummary(
                expDir,
                incDir,
                savDir,
                expDeltaAbs,
                incDeltaAbs,
            )
            val diagnosis = summaryNode.path("diagnosis").takeIf { it.isString }?.asString()
                ?.let { runCatching { SummaryDiagnosis.valueOf(it) }.getOrNull() }
                ?: computedDiag
            val diagnosisDetail = summaryNode.path("diagnosisDetail").takeIf { it.isString }?.asString()
                ?.takeIf { it.isNotBlank() }
                ?: computedDetail
            SelectedSummaryFacts(
                periodYear = periodYear,
                periodMonth = periodMonth,
                reviewKind = reviewKind,
                daysElapsed = daysElapsed,
                daysInMonth = daysInMonth,
                expensesDirection = expDir,
                incomeDirection = incDir,
                savingsDirection = savDir,
                diagnosis = diagnosis,
                diagnosisDetail = diagnosisDetail,
                hasDeficit = summaryNode.path("hasDeficit").asBoolean(false),
                hasProjectedDeficit = summaryNode.path("hasProjectedDeficit").asBoolean(false),
                figures = figs,
            )
        } else null

        val localFindings = localLabels?.path("findings")
        val findings = mutableListOf<SelectedFinding>()
        val rawArr = payload.path("findings")
        if (rawArr.isArray) {
            for (node in rawArr) {
                if (!node.isObject) continue
                val id = node.path("id").asString("")
                val section = runCatching { FindingSection.valueOf(node.path("section").asString("savings")) }
                    .getOrDefault(FindingSection.savings)
                val kind = runCatching { FindingKind.valueOf(node.path("kind").asString("cancellable_recurring")) }
                    .getOrDefault(FindingKind.cancellable_recurring)
                val meta = localFindings?.path(id)?.takeIf { it.isObject }
                val ctx = LinkedHashMap<String, String>()
                val ctxNode = node.path("context")
                if (ctxNode.isObject) {
                    for ((k, v) in ctxNode.properties()) ctx[k] = v.asString("")
                }
                val catCode = node.path("categoryCode").takeIf { it.isString }?.asString()
                val payloadSubject = node.path("subject").takeIf { it.isString }?.asString() ?: catCode.orEmpty()
                val localName = meta?.path("localName")?.takeIf { it.isString }?.asString()
                findings += SelectedFinding(
                    id = id,
                    section = section,
                    kind = kind,
                    candidateId = meta?.path("candidateId")?.takeIf { it.isString }?.asString(),
                    payloadSubject = payloadSubject,
                    localSubject = localName,
                    anonymized = node.path("anonymized").asBoolean(false),
                    mergedVariants = emptyList(),
                    categoryCode = catCode,
                    groupCode = meta?.path("groupCode")?.takeIf { it.isString }?.asString(),
                    action = node.path("action").takeIf { it.isString }?.asString()
                        ?.let { runCatching { RecurringAction.valueOf(it) }.getOrNull() },
                    severity = meta?.path("severity")?.asString("opportunity") ?: "opportunity",
                    changeKind = meta?.path("changeKind")?.takeIf { it.isString }?.asString(),
                    potentialMonthlySaving = null,
                    potentialAnnualSaving = null,
                    figures = readFigures(node.path("figures")),
                    context = ctx,
                    txFilter = null,
                    explorerLink = null,
                    simulationScenario = null,
                )
            }
        }

        val reconstructed = SelectedReview(
            mode = if (reviewKind == "savings_only") ReviewMode.SAVINGS_ONLY else ReviewMode.FULL,
            currency = currency,
            period = SpendingReviewPeriodDto(
                year = periodYear,
                month = periodMonth,
                reviewKind = reviewKind,
                windowFrom = java.time.LocalDate.of(periodYear, periodMonth, 1),
                windowTo = java.time.LocalDate.of(periodYear, periodMonth, 1),
                daysElapsed = daysElapsed,
                daysInMonth = daysInMonth,
                minDaysElapsed = SpendingReviewConstants.AI_REVIEW_MIN_DAYS_ELAPSED,
                paceReviewEligible = true,
                staleSpendingSince = null,
                periodHasNoExpenses = false,
                monthsAvailable = scope.path("monthsAvailable").asInt(12),
            ),
            summaryFacts = summaryFacts,
            savings = findings.filter { it.section == FindingSection.savings },
            whatChanged = findings.filter { it.section == FindingSection.whatChanged },
            budgets = findings.filter { it.section == FindingSection.budgets },
            savingsOmitted = localLabels?.path("savingsOmitted")?.asInt(0) ?: 0,
            whatChangedOmitted = localLabels?.path("whatChangedOmitted")?.asInt(0) ?: 0,
            budgetsOmitted = localLabels?.path("budgetsOmitted")?.asInt(0) ?: 0,
            underlyingMovers = emptyList(),
            underlyingBudgetOverruns = emptyList(),
            unchangedEssentialRecurringCount = 0,
            unchangedEssentialRecurringMonthly = BigDecimal.ZERO,
            cutCombinations = emptyList(),
            otherMerchants = OtherMerchantsAggregateDto(BigDecimal.ZERO, 0, 0),
            hasBudgets = scope.path("hasBudgets").asBoolean(false),
        )

        val fallbackLabel: (String) -> String = { code ->
            rawArr.firstOrNull { it.path("categoryCode").asString("") == code }
                ?.path("categoryName")?.takeIf { it.isString }?.asString()
                ?: categoryLabel(code)
        }
        return renderWithModel(reconstructed, modelOutput, locale, fallbackLabel)
    }

    /** Verifies that a model-written summary preserves the deterministic diagnosis meaning (cause of shortfall or on-track reason). */
    internal fun validateSummaryDiagnosis(rawSummary: String, sf: SelectedSummaryFacts): String? {
        val text = rawSummary.lowercase(Locale.ROOT)
        val ok = when (sf.diagnosis) {
            SummaryDiagnosis.shortfall_from_income ->
                DIAG_CAUSAL_CUE.containsMatchIn(text) &&
                    DIAG_INCOME_CAUSE.containsMatchIn(text) &&
                    !DIAG_SPENDING_BLAME.containsMatchIn(text)

            SummaryDiagnosis.shortfall_from_spending ->
                DIAG_CAUSAL_CUE.containsMatchIn(text) &&
                    DIAG_SPENDING_CAUSE.containsMatchIn(text) &&
                    !DIAG_INCOME_BLAME.containsMatchIn(text)

            SummaryDiagnosis.shortfall_from_both -> {
                val hasBothSides = DIAG_CAUSAL_CUE.containsMatchIn(text) &&
                    DIAG_SPENDING_WORD.containsMatchIn(text) &&
                    DIAG_INCOME_WORD.containsMatchIn(text)
                val dominanceMatches = when (sf.diagnosisDetail) {
                    "spending_dominant" -> DIAG_DOM_SPENDING.containsMatchIn(text) && !DIAG_DOM_INCOME.containsMatchIn(text)
                    "income_dominant" -> DIAG_DOM_INCOME.containsMatchIn(text) && !DIAG_DOM_SPENDING.containsMatchIn(text)
                    "equal" -> DIAG_DOM_EQUAL.containsMatchIn(text)
                    else -> true
                }
                hasBothSides && dominanceMatches
            }

            SummaryDiagnosis.on_track_or_above -> if (sf.savingsDirection == "at") {
                DIAG_IN_LINE.containsMatchIn(text) && !DIAG_SHORTFALL.containsMatchIn(text)
            } else {
                val aboveOk = DIAG_ABOVE.containsMatchIn(text) && !DIAG_SHORTFALL.containsMatchIn(text)
                val reasonOk = when (sf.diagnosisDetail) {
                    "lower_spending" -> DIAG_CAUSAL_CUE.containsMatchIn(text) && DIAG_SPENDING_WORD.containsMatchIn(text)
                    "higher_income" -> DIAG_CAUSAL_CUE.containsMatchIn(text) && DIAG_INCOME_WORD.containsMatchIn(text)
                    "both_favorable" -> DIAG_CAUSAL_CUE.containsMatchIn(text) &&
                        DIAG_SPENDING_WORD.containsMatchIn(text) &&
                        DIAG_INCOME_WORD.containsMatchIn(text)
                    else -> true
                }
                aboveOk && reasonOk
            }
        }
        return if (ok) null else "diagnosis_mismatch"
    }

    /** Returns the deterministic diagnosis sentence (EN/ES, zero raw digits, zero banned terms). */
    internal fun diagnosisSentence(sf: SelectedSummaryFacts, locale: String): String {
        val isEs = locale.startsWith("es", ignoreCase = true)
        val isPace = sf.reviewKind == "pace_review"
        return if (isEs) {
            val stayed = if (isPace) "está" else "quedó"
            when (sf.diagnosis) {
                SummaryDiagnosis.shortfall_from_income -> when (sf.diagnosisDetail) {
                    "expenses_in_line" -> "Como tu gasto $stayed en línea con la media, el menor ahorro se debe a unos ingresos más bajos."
                    else -> "Como tu gasto $stayed por debajo de la media, el menor ahorro se debe a unos ingresos más bajos."
                }
                SummaryDiagnosis.shortfall_from_spending -> when (sf.diagnosisDetail) {
                    "income_above" -> "Con unos ingresos por encima de tu media, el menor ahorro se debe al aumento del gasto."
                    else -> "Con unos ingresos en línea con tu media, el menor ahorro se debe al aumento del gasto."
                }
                SummaryDiagnosis.shortfall_from_both -> when (sf.diagnosisDetail) {
                    "spending_dominant" -> "Tanto el mayor gasto ({f3} por encima de tu media) como los menores ingresos ({f6} por debajo) redujeron tu ahorro, sobre todo el gasto."
                    "income_dominant" -> "Tanto el mayor gasto ({f3} por encima de tu media) como los menores ingresos ({f6} por debajo) redujeron tu ahorro, sobre todo los ingresos."
                    else -> "El mayor gasto ({f3} por encima de tu media) y los menores ingresos ({f6} por debajo) redujeron tu ahorro en la misma medida."
                }
                SummaryDiagnosis.on_track_or_above -> if (sf.savingsDirection == "at") {
                    when {
                        sf.expensesDirection == "below" && sf.incomeDirection == "below" ->
                            "Tu ahorro $stayed en línea con tu media porque el menor gasto compensó unos ingresos más bajos."
                        sf.expensesDirection == "above" && sf.incomeDirection == "above" ->
                            "Tu ahorro $stayed en línea con tu media porque los mayores ingresos compensaron el mayor gasto."
                        else ->
                            "Tu ahorro $stayed en línea con tu media mensual anual, con gastos e ingresos cerca de lo habitual."
                    }
                } else {
                    when (sf.diagnosisDetail) {
                        "both_favorable" -> "Tu ahorro $stayed por encima de tu media gracias tanto al menor gasto como a los mayores ingresos."
                        "lower_spending" -> "Tu ahorro $stayed por encima de tu media gracias a un gasto más bajo."
                        "higher_income" -> "Tu ahorro $stayed por encima de tu media gracias a unos ingresos más altos."
                        else -> "Tu ahorro $stayed por encima de tu media mensual anual."
                    }
                }
            }
        } else {
            val stayed = if (isPace) "is" else "stayed"
            val finished = if (isPace) "are running" else "finished"
            when (sf.diagnosis) {
                SummaryDiagnosis.shortfall_from_income -> when (sf.diagnosisDetail) {
                    "expenses_in_line" -> "Because spending $stayed in line with average, the savings shortfall comes from lower income."
                    else -> "Because spending $stayed below average, the savings shortfall comes from lower income."
                }
                SummaryDiagnosis.shortfall_from_spending -> when (sf.diagnosisDetail) {
                    "income_above" -> "With income above average, the savings shortfall comes from higher spending."
                    else -> "With income in line with average, the savings shortfall comes from higher spending."
                }
                SummaryDiagnosis.shortfall_from_both -> when (sf.diagnosisDetail) {
                    "spending_dominant" -> "Both higher spending ({f3} above average) and lower income ({f6} below) weighed on savings, with spending contributing more."
                    "income_dominant" -> "Both higher spending ({f3} above average) and lower income ({f6} below) weighed on savings, with lower income contributing more."
                    else -> "Higher spending ({f3} above average) and lower income ({f6} below) contributed equally to the shortfall."
                }
                SummaryDiagnosis.on_track_or_above -> if (sf.savingsDirection == "at") {
                    when {
                        sf.expensesDirection == "below" && sf.incomeDirection == "below" ->
                            "Savings $stayed in line with average as lower spending offset lower income."
                        sf.expensesDirection == "above" && sf.incomeDirection == "above" ->
                            "Savings $stayed in line with average as higher income offset higher spending."
                        else ->
                            "Savings $stayed in line with your annual monthly average, with spending and income close to normal."
                    }
                } else {
                    when (sf.diagnosisDetail) {
                        "both_favorable" -> "Savings $finished above average thanks to both lower spending and higher income."
                        "lower_spending" -> "Savings $finished above average thanks to lower spending."
                        "higher_income" -> "Savings $finished above average thanks to higher income."
                        else -> "Savings $finished above your annual monthly average."
                    }
                }
            }
        }
    }

    /** Returns the placeholder-only summary template (no raw digits, no banned terms). */
    internal fun summaryTemplate(sf: SelectedSummaryFacts, locale: String): String {
        val isEs = locale.startsWith("es", ignoreCase = true)
        val monthName = Month.of(sf.periodMonth)
            .getDisplayName(TextStyle.FULL, toJavaLocale(locale))
            .let { if (isEs) it.lowercase(toJavaLocale(locale)) else it.replaceFirstChar { c -> c.titlecase(toJavaLocale(locale)) } }
        val isPace = sf.reviewKind == "pace_review"
        val diag = diagnosisSentence(sf, locale)

        return if (isEs) {
            val expCmp = when (sf.expensesDirection) {
                "below" -> "{f3} por debajo de tu media anual de {f2}"
                "above" -> "{f3} por encima de tu media anual de {f2}"
                else -> "en línea con tu media anual de {f2}"
            }
            val incCmp = when (sf.incomeDirection) {
                "below" -> "{f6} por debajo de tu media de {f5}"
                "above" -> "{f6} por encima de tu media de {f5}"
                else -> "en línea con tu media de {f5}"
            }
            val base = if (isPace) {
                "En $monthName (día {f13} de {f14}) llevas {f1} en gastos ($expCmp) y {f4} en ingresos ($incCmp). " +
                    "El ahorro acumulado es de {f7} frente a una media mensual de {f8}, con una tasa de ahorro de {f9} frente a {f10}, " +
                    "y la proyección de cierre sitúa el gasto en {f11} y el ahorro neto en {f12}."
            } else {
                "En $monthName gastaste {f1} ($expCmp) e ingresaste {f4} ($incCmp). " +
                    "Tu ahorro neto quedó en {f7} frente a una media mensual de {f8}, con una tasa de ahorro de {f9} frente a {f10}."
            }
            "$base $diag"
        } else {
            val expCmp = when (sf.expensesDirection) {
                "below" -> "{f3} below your annual monthly average of {f2}"
                "above" -> "{f3} above your annual monthly average of {f2}"
                else -> "in line with your annual monthly average of {f2}"
            }
            val incCmp = when (sf.incomeDirection) {
                "below" -> "{f6} below the {f5} average"
                "above" -> "{f6} above the {f5} average"
                else -> "in line with the {f5} average"
            }
            val base = if (isPace) {
                "Through day {f13} of {f14} in $monthName, expenses stand at {f1} ($expCmp) and income at {f4} ($incCmp). " +
                    "Net savings are {f7} against a {f8} monthly average, a {f9} savings rate compared with {f10}, " +
                    "with month-end projected at {f11} in expenses and {f12} saved."
            } else {
                "$monthName closed with {f1} in expenses ($expCmp) and {f4} in income ($incCmp). " +
                    "Net savings came to {f7} versus a {f8} monthly average, a {f9} savings rate compared with {f10}."
            }
            "$base $diag"
        }
    }

    /** Returns `(title, placeholderDetail)` for a single finding (no raw digits, no banned terms). */
    internal fun findingTemplate(
        f: SelectedFinding,
        locale: String,
        categoryLabel: (String) -> String,
        useLocalName: Boolean = true,
    ): Pair<String, String> {
        val isEs = locale.startsWith("es", ignoreCase = true)
        val cat = f.categoryCode?.let(categoryLabel) ?: (f.groupCode ?: "")
        val subject = when {
            useLocalName && !f.localSubject.isNullOrBlank() -> f.localSubject
            !f.anonymized && f.payloadSubject.isNotBlank() && f.payloadSubject != f.categoryCode -> f.payloadSubject
            else -> cat
        }

        return when (f.kind) {
            FindingKind.cancellable_recurring -> {
                val yoyKind = f.context["yoyChangeKind"]
                val currentRef = if (f.context["cadence"] == DetectedCadence.yearly.name) "{f2}" else "{f1}"
                if (isEs) {
                    val kindEs = RECURRING_KIND_LABEL_ES[f.categoryCode] ?: cat
                    val title = if (subject == cat) "Cargo recurrente en $cat" else "$kindEs: $subject"
                    val detail = when (yoyKind) {
                        YoyChangeKind.price_increase.name -> "$subject te cuesta {f1} al mes ({f2} al año), un {f4} más que los {f3} del año pasado."
                        YoyChangeKind.changed.name -> "$subject te cuesta {f1} al mes ({f2} al año); el importe cambió de {f3} a $currentRef respecto al año pasado."
                        else -> "$subject te cuesta {f1} al mes, es decir {f2} al año."
                    }
                    title to detail
                } else {
                    val title = if (subject == cat) "Recurring charge in $cat" else "$subject recurring charge"
                    val detail = when (yoyKind) {
                        YoyChangeKind.price_increase.name -> "$subject costs {f1} a month ({f2} a year), up {f4} from {f3} a year earlier."
                        YoyChangeKind.changed.name -> "$subject costs {f1} a month ({f2} a year); the amount changed from {f3} to $currentRef compared with a year earlier."
                        else -> "$subject costs {f1} a month, or {f2} a year."
                    }
                    title to detail
                }
            }

            FindingKind.avoidable_fee -> if (isEs) {
                cat to "Pagaste {f1} en $cat en {f3} cargos durante el último año, una media de {f2} al mes."
            } else {
                cat to "$cat totalled {f1} across {f3} charges over the past year, an average of {f2} a month."
            }

            FindingKind.negotiable_price_increase -> if (isEs) {
                "Subida en $subject" to "$subject subió un {f3}, de {f2} a {f1} al mes ({f4} al año); te conviene comparar opciones."
            } else {
                "$subject price increase" to "$subject rose {f3}, from {f2} to {f1} a month ({f4} a year); worth comparing alternatives."
            }

            FindingKind.top_merchant -> if (isEs) {
                "Gasto frecuente en $subject" to "Gastaste {f1} en $subject en {f2} compras de $cat ({f3} al mes, un {f4} de la categoría)."
            } else {
                "Frequent spend at $subject" to "$subject totalled {f1} across {f2} purchases in $cat ({f3} a month, {f4} of the category)."
            }

            FindingKind.habitual_uneven -> if (isEs) {
                "Gasto desigual en $cat" to "$cat promedió {f1} al mes en {f5} meses activos frente a un mes habitual de {f2}, lo que deja {f3} al mes ({f4} al año) por encima de tu nivel habitual."
            } else {
                "Uneven spending in $cat" to "$cat averaged {f1} a month across {f5} active months against a typical month of {f2}, leaving {f3} a month ({f4} a year) above your typical level."
            }

            FindingKind.period_increase -> {
                val compKind = f.context["comparisonKind"]
                val driverKind = f.context["topDriverKind"]
                val driverName = f.context["topDriverName"]
                val driverPurchases = f.figures.firstOrNull { it.key == "f7" }?.value?.toInt() ?: 0
                if (isEs) {
                    val comp = if (compKind == "year_ago") "del mismo mes del año pasado" else "de tu media de seis meses"
                    val title = "Subida en $cat"
                    val detail = when (driverKind) {
                        "unlabelled" -> {
                            val compraWord = if (driverPurchases == 1) "compra sin etiqueta" else "compras sin etiqueta"
                            val verb = if (driverPurchases == 1) "supone" else "suponen"
                            "$cat llegó a {f1} ({f2} por encima $comp, {f3}) en {f4} compras, con {f7} $compraWord de {f5} que $verb el {f6} de la categoría."
                        }
                        "named" -> {
                            val compraWord = if (driverPurchases == 1) "compra" else "compras"
                            "$cat llegó a {f1} ({f2} por encima $comp, {f3}) en {f4} compras, con {f7} $compraWord en $driverName por {f5} ({f6} de la categoría)."
                        }
                        "anonymized" -> {
                            val compraWord = if (driverPurchases == 1) "compra" else "compras"
                            "$cat llegó a {f1} ({f2} por encima $comp, {f3}) en {f4} compras, con {f7} $compraWord en un mismo comercio por {f5} ({f6} de la categoría)."
                        }
                        else -> "$cat llegó a {f1}, {f2} por encima $comp ({f3})."
                    }
                    title to detail
                } else {
                    val comp = if (compKind == "year_ago") "same month last year" else "six-month average"
                    val title = "Higher spending in $cat"
                    val detail = when (driverKind) {
                        "unlabelled" -> {
                            val purchaseWord = if (driverPurchases == 1) "unlabelled purchase" else "unlabelled purchases"
                            val verb = if (driverPurchases == 1) "accounts" else "account"
                            "$cat reached {f1} ({f2} above the $comp of {f3}) across {f4} purchases, with {f7} $purchaseWord of {f5} that $verb for {f6} of the category."
                        }
                        "named" -> {
                            val purchaseWord = if (driverPurchases == 1) "purchase" else "purchases"
                            "$cat reached {f1} ({f2} above the $comp of {f3}) across {f4} purchases, with {f7} $purchaseWord at $driverName totalling {f5} ({f6} of the category)."
                        }
                        "anonymized" -> {
                            val purchaseWord = if (driverPurchases == 1) "purchase" else "purchases"
                            "$cat reached {f1} ({f2} above the $comp of {f3}) across {f4} purchases, with {f7} $purchaseWord at one payee totalling {f5} ({f6} of the category)."
                        }
                        else -> "$cat reached {f1}, {f2} above the $comp of {f3}."
                    }
                    title to detail
                }
            }

            FindingKind.non_actionable_price_change -> {
                val up = f.context["direction"] != "down"
                if (isEs) {
                    val title = if (up) "Subida en $subject" else "Bajada en $subject"
                    val verb = if (up) "subió" else "bajó"
                    title to "$subject $verb un {f3}, de {f2} a {f1} al mes ({f4} al año)."
                } else {
                    val title = if (up) "$subject increased" else "$subject decreased"
                    val verb = if (up) "rose" else "fell"
                    title to "$subject $verb {f3}, from {f2} to {f1} a month ({f4} a year)."
                }
            }

            FindingKind.zero_median_one_off -> if (isEs) {
                "Gasto puntual en $cat" to "$cat sumó {f1} en {f2} meses con gasto durante el último año, con un pico de {f3} en un solo mes."
            } else {
                "Occasional spending in $cat" to "$cat totalled {f1} across {f2} active months over the past year, peaking at {f3} in a single month."
            }

            FindingKind.budget_overrun -> {
                val isMult = f.context["overrunFormat"] == "multiple"
                if (isEs) {
                    val ratio = if (isMult) "{f4} el presupuesto" else "{f4} del presupuesto"
                    "$cat por encima del presupuesto" to "$cat llegó a {f1} frente a un presupuesto de {f2}: {f3} por encima ($ratio)."
                } else {
                    val ratio = if (isMult) "{f4} the budget" else "{f4} of the budget"
                    "$cat over budget" to "$cat reached {f1} against a {f2} budget — {f3} over ($ratio)."
                }
            }
        }
    }

    private fun requiredPlaceholdersFor(f: SelectedFinding): Set<String> = when (f.kind) {
        FindingKind.period_increase -> if (f.figures.any { it.key == "f5" }) setOf("f1", "f5", "f6") else setOf("f1", "f2")
        FindingKind.budget_overrun -> setOf("f1", "f2", "f4")
        else -> setOf("f1")
    }

    private fun toJavaLocale(locale: String): Locale =
        if (locale.startsWith("es", ignoreCase = true)) Locale.forLanguageTag("es-ES") else Locale.US

    private fun formatMoney(amount: BigDecimal, currencyCode: String, loc: Locale, wholeOnly: Boolean): String {
        val nf = NumberFormat.getCurrencyInstance(loc)
        runCatching { nf.currency = Currency.getInstance(currencyCode) }
        val norm = amount.setScale(if (wholeOnly) 0 else 2, RoundingMode.HALF_UP)
        val hasCents = !wholeOnly && norm.remainder(BigDecimal.ONE).signum() != 0
        nf.minimumFractionDigits = if (hasCents) 2 else 0
        nf.maximumFractionDigits = if (hasCents) 2 else 0
        // Replace non-breaking spaces from Java's es-ES formatter with standard space for clean prose & markdown.
        return nf.format(norm).replace('\u00A0', ' ').replace('\u202F', ' ')
    }

    private fun formatPercent(value: BigDecimal, locale: String, loc: Locale): String {
        val scaled = value.setScale(1, RoundingMode.HALF_UP)
        val isWhole = scaled.remainder(BigDecimal.ONE).signum() == 0
        val nf = NumberFormat.getNumberInstance(loc).apply {
            minimumFractionDigits = if (isWhole) 0 else 1
            maximumFractionDigits = if (isWhole) 0 else 1
        }
        val num = nf.format(scaled)
        return if (locale.startsWith("es", ignoreCase = true)) "$num %" else "$num%"
    }

    private fun formatMultiple(value: BigDecimal, loc: Locale): String {
        val scaled = value.setScale(1, RoundingMode.HALF_UP)
        val isWhole = scaled.remainder(BigDecimal.ONE).signum() == 0
        val nf = NumberFormat.getNumberInstance(loc).apply {
            minimumFractionDigits = if (isWhole) 0 else 1
            maximumFractionDigits = if (isWhole) 0 else 1
        }
        val sym = DecimalFormatSymbols.getInstance(loc)
        val num = nf.format(scaled).replace(sym.groupingSeparator.toString(), "")
        return "${num}×"
    }
}
