package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.ai.AiCitationMapper
import com.sephilabs.sharedledger.ai.AiJsonUtils
import com.sephilabs.sharedledger.ai.AiReviewRateLimiter
import com.sephilabs.sharedledger.ai.AiRunStatus
import com.sephilabs.sharedledger.bank.aisuggestion.AI_FEATURES_DISABLED
import com.sephilabs.sharedledger.bank.aisuggestion.AiAvailability
import com.sephilabs.sharedledger.bank.aisuggestion.AiSuggestionSettingsService
import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.bank.aisuggestion.ChatPrompt
import com.sephilabs.sharedledger.bank.aisuggestion.JsonObjectReader
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModel
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModelException
import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.catalog.CategoryView
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.transaction.Direction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.Executor

@Service
class AnalyticsAiReviewService(
    private val availability: AiAvailability,
    private val aiSettings: AiSuggestionSettingsService,
    private val savingsCandidates: SavingsCandidatesService,
    private val skeletonService: SpendingReviewSkeletonService,
    private val categoryService: CategoryService,
    private val runs: AnalyticsAiReviewRunRepository,
    private val followups: AnalyticsAiReviewFollowupRepository,
    private val model: SuggestionModel,
    private val runner: AnalyticsAiReviewRunner,
    private val limiter: AiReviewRateLimiter,
    private val clock: Clock,
    @Qualifier("aiSuggestionExecutor") private val executor: Executor,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper.builder().build()

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<AnalyticsAiRunDto> {
        requireAiEnabled(householdId)
        val categories = categoryService.listForHousehold(householdId)
        val stored = runs.findAllByHouseholdIdOrderByCreatedAtDesc(householdId)
        val byRun = followups.findAllByHouseholdIdOrderByCreatedAtAsc(householdId).groupBy { it.runId }
        return stored.map { it.toDto(byRun[it.id].orEmpty(), categories) }
    }

    fun start(householdId: UUID, by: User, request: StartAnalyticsAiReviewRequest?): AnalyticsAiRunDto {
        val provider = requireAiEnabled(householdId)

        val today = LocalDate.now(clock)
        val period = savingsCandidates.resolvePeriod(request?.year, request?.month, today)
        val candidatesResp = savingsCandidates.candidates(householdId, period.year, period.monthValue)
        val wantsSavingsOnly = request?.mode.equals("savings_only", ignoreCase = true)

        if (!wantsSavingsOnly) {
            if (!candidatesResp.period.paceReviewEligible) {
                throw AppException.badRequest(
                    "AI_REVIEW_PACE_TOO_EARLY",
                    candidatesResp.period.minDaysElapsed,
                    candidatesResp.period.daysElapsed,
                )
            }
            if (candidatesResp.period.reviewBlockedReason == "empty_or_stale") {
                throw AppException.badRequest(
                    "AI_REVIEW_PERIOD_EMPTY_OR_STALE",
                    candidatesResp.period.lastMonthWithData ?: "",
                )
            }
        }
        if (!limiter.tryAcquire(RATE_LIMIT_FEATURE, by.id)) throw AppException.tooManyRequests()

        val mode = if (wantsSavingsOnly) ReviewMode.SAVINGS_ONLY else ReviewMode.FULL
        val locale = normalizeLocale(by.locale)
        val categories = categoryService.listForHousehold(householdId)
        val selected = skeletonService.selectForPeriod(householdId, period, mode, today, candidatesResp, categories)
        val built = AnalyticsAiPayloadBuilder.build(
            review = selected,
            locale = locale,
            categoryLabel = skeletonService.categoryLabelResolver(categories, locale),
        )

        val run = try {
            runs.saveAndFlush(
                AnalyticsAiReviewRun(
                    householdId = householdId,
                    userId = by.id,
                    model = provider.model,
                    promptVersion = AnalyticsAiReviewPrompt.VERSION,
                    locale = locale,
                    periodYear = period.year,
                    periodMonth = period.monthValue,
                    reviewKind = selected.period.reviewKind,
                    daysElapsed = candidatesResp.period.daysElapsed,
                    daysInMonth = candidatesResp.period.daysInMonth,
                    staleSpendingSince = candidatesResp.period.staleSpendingSince,
                    payloadJson = built.json,
                    localLabelsJson = built.localLabelsJson,
                ),
            )
        } catch (_: DataIntegrityViolationException) {
            throw AppException.conflict("ANALYTICS_AI_RUN_IN_PROGRESS")
        }

        log.info(
            "analytics_ai_review_started run={} household={} user={} period={}-{} kind={} model={} prompt={} locale={}",
            run.id, householdId, by.id, run.periodYear, run.periodMonth, run.reviewKind, run.model, run.promptVersion, run.locale,
        )
        executor.execute { runner.execute(run.id) }
        val latest = runs.findById(run.id).orElse(null) ?: run
        val runFollowups = followups.findAllByRunIdOrderByCreatedAtAsc(run.id)
        return latest.toDto(runFollowups, categories)
    }

    fun askFollowup(
        householdId: UUID,
        runId: UUID,
        by: User,
        request: AnalyticsAiFollowupRequest,
    ): AnalyticsAiFollowupDto {
        requireAiEnabled(householdId)
        val question = request.question?.trim() ?: ""
        if (question.isBlank() || question.length > SpendingReviewConstants.ANALYTICS_AI_FOLLOWUP_MAX_CHARS) {
            throw AppException.badRequest("ANALYTICS_AI_QUESTION_INVALID", SpendingReviewConstants.ANALYTICS_AI_FOLLOWUP_MAX_CHARS)
        }
        if (!limiter.tryAcquire(RATE_LIMIT_FEATURE, by.id)) throw AppException.tooManyRequests()

        val run = runs.findByIdAndHouseholdId(runId, householdId)
            ?: throw AppException.notFound("ANALYTICS_AI_RUN_NOT_FOUND")
        if (run.status != AiRunStatus.completed) {
            throw AppException.conflict("ANALYTICS_AI_RUN_NOT_COMPLETED")
        }

        val payloadNode = mapper.readTree(run.payloadJson)
        val localLabels = runCatching { mapper.readTree(run.localLabelsJson) }.getOrNull()
        val summary = run.resultJson?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?.path("summary")?.takeIf { it.isString }?.asString()
        val prior = followups.findAllByRunIdOrderByCreatedAtAsc(runId).map { it.question to it.answer }

        val prompt = ChatPrompt(
            system = AnalyticsAiReviewPrompt.followupSystem(run.locale),
            user = AnalyticsAiReviewPrompt.followupUser(
                AiJsonUtils.canonicalJson(payloadNode),
                summary,
                prior,
                question,
            ),
            sessionId = UUID.randomUUID(),
        )
        val rawAnswer = try {
            model.complete(prompt)
        } catch (ex: SuggestionModelException) {
            log.warn("analytics_ai_followup run={} household={} outcome=failed error={}", runId, householdId, ex.message)
            throw AppException(code = "ANALYTICS_AI_FOLLOWUP_FAILED", httpStatus = 502, cause = ex)
        }
        val parsed = JsonObjectReader.first(rawAnswer.content)
        val answerText = parsed?.path("answer")?.takeIf { it.isString }?.asString()?.trim()?.ifBlank { null }
            ?: throw AppException(code = "ANALYTICS_AI_FOLLOWUP_FAILED", httpStatus = 502)

        val citationsNode = parsed.path("citations").takeIf { it.isArray } ?: mapper.createArrayNode()
        val grounding = AnalyticsAiReviewVerifier.verifyFollowupCitations(payloadNode, citationsNode)
        val saved = followups.save(
            AnalyticsAiReviewFollowup(
                runId = run.id,
                householdId = householdId,
                userId = by.id,
                question = question,
                answer = answerText,
                citationsJson = mapper.writeValueAsString(citationsNode),
                citationsTotal = grounding.total,
                citationsUngrounded = grounding.ungrounded,
                inputTokens = rawAnswer.inputTokens,
                outputTokens = rawAnswer.outputTokens,
                reasoningTokens = rawAnswer.reasoningTokens,
                latencyMs = rawAnswer.latencyMs,
            ),
        )
        log.info(
            "analytics_ai_followup run={} followup={} household={} citations={} ungrounded={} latency_ms={}",
            run.id, saved.id, householdId, grounding.total, grounding.ungrounded, rawAnswer.latencyMs,
        )
        return saved.toDto(payloadNode, localLabels)
    }

    @Transactional
    fun delete(householdId: UUID, runId: UUID) {
        requireAiEnabled(householdId)
        val run = runs.findByIdAndHouseholdId(runId, householdId)
            ?: throw AppException.notFound("ANALYTICS_AI_RUN_NOT_FOUND")
        runs.delete(run)
        log.info("analytics_ai_review_deleted run={} household={}", runId, householdId)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun interruptLeftoverRuns() {
        val count = runs.interruptRunning(Instant.now())
        if (count > 0) log.warn("analytics_ai_review_runs_interrupted count={}", count)
    }

    /** Owner-only preview returning the exact serialized payload bytes that [start] sends to the provider. */
    @Transactional(readOnly = true)
    fun previewPayload(
        householdId: UUID,
        year: Int? = null,
        month: Int? = null,
        modeParam: String? = null,
        locale: String = "en",
    ): AnalyticsAiPayloadPreviewDto {
        val provider = requireAiEnabled(householdId)
        val today = LocalDate.now(clock)
        val period = savingsCandidates.resolvePeriod(year, month, today)
        val candidatesResp = savingsCandidates.candidates(householdId, period.year, period.monthValue)
        val mode = if (modeParam.equals("savings_only", ignoreCase = true)) ReviewMode.SAVINGS_ONLY else ReviewMode.FULL
        val normLocale = normalizeLocale(locale)
        val categories = categoryService.listForHousehold(householdId)
        val selected = skeletonService.selectForPeriod(householdId, period, mode, today, candidatesResp, categories)
        val built = AnalyticsAiPayloadBuilder.build(
            review = selected,
            locale = normLocale,
            categoryLabel = skeletonService.categoryLabelResolver(categories, normLocale),
        )
        return AnalyticsAiPayloadPreviewDto(
            periodYear = period.year,
            periodMonth = period.monthValue,
            reviewKind = selected.period.reviewKind,
            model = provider.model,
            providerHost = provider.host,
            byteSize = built.json.toByteArray(Charsets.UTF_8).size,
            truncatedSections = built.truncatedSections,
            payloadJson = built.json,
        )
    }

    /** Exposed for payload-privacy and determinism tests. */
    @Transactional(readOnly = true)
    fun buildPayloadForPeriod(
        householdId: UUID,
        year: Int? = null,
        month: Int? = null,
        mode: ReviewMode = ReviewMode.FULL,
        locale: String = "en",
    ): AnalyticsAiPayloadBuilder.BuiltPayload {
        val today = LocalDate.now(clock)
        val period = savingsCandidates.resolvePeriod(year, month, today)
        val candidatesResp = savingsCandidates.candidates(householdId, period.year, period.monthValue)
        val categories = categoryService.listForHousehold(householdId)
        val selected = skeletonService.selectForPeriod(householdId, period, mode, today, candidatesResp, categories)
        return AnalyticsAiPayloadBuilder.build(
            review = selected,
            locale = normalizeLocale(locale),
            categoryLabel = skeletonService.categoryLabelResolver(categories, normalizeLocale(locale)),
        )
    }

    private fun requireAiEnabled(householdId: UUID) = availability.requireConfigured().also {
        if (!aiSettings.isEnabled(householdId)) throw AppException.conflict(AI_FEATURES_DISABLED)
    }

    private fun normalizeLocale(raw: String?): String =
        if (raw?.take(2).equals("es", ignoreCase = true)) "es" else "en"

    // --- DTO mapping ---------------------------------------------------------

    private fun AnalyticsAiReviewRun.toDto(
        runFollowups: List<AnalyticsAiReviewFollowup>,
        categories: List<CategoryView>,
    ): AnalyticsAiRunDto {
        val payload = runCatching { mapper.readTree(payloadJson) }.getOrNull()
        val localLabels = runCatching { mapper.readTree(localLabelsJson) }.getOrNull()
        val result = resultJson?.let { runCatching { mapper.readTree(it) }.getOrNull() }
        val catByCode = categories.associateBy { it.code }
        val catalogue = categories.map { CatalogueEntry(it.code, it.code, it.group, Direction.valueOf(it.kind), it.essential) }
        val hasBudgets = payload?.path("scope")?.path("hasBudgets")?.asBoolean(false) ?: false

        val savingsList = mutableListOf<AnalyticsSavingsFindingDto>()
        val changedList = mutableListOf<AnalyticsChangeFindingDto>()
        val budgetsList = mutableListOf<AnalyticsBudgetFindingDto>()

        val resultFindings = result?.path("findings")?.takeIf { it.isArray }
        val localFindings = localLabels?.path("findings")?.takeIf { it.isObject }

        if (resultFindings != null) {
            // Current deterministic-selection format (ar6+)
            for (item in resultFindings) {
                val fid = item.path("id").asString("")
                val meta = localFindings?.path(fid)?.takeIf { it.isObject } ?: continue
                val section = meta.path("section").asString("")
                val title = item.path("title").asString("")
                val detail = item.path("detail").asString("")
                val catCode = meta.path("categoryCode").takeIf { it.isString }?.asString()?.ifBlank { null }
                val groupCode = meta.path("groupCode").takeIf { it.isString }?.asString()?.ifBlank { null }
                    ?: catCode?.let { catByCode[it]?.group }
                val localName = meta.path("localName").takeIf { it.isString }?.asString()
                val mergedVariants = meta.path("mergedVariants").takeIf { it.isArray }?.mapNotNull {
                    it.takeIf { n -> n.isString }?.asString()
                } ?: emptyList()
                val metrics = readMetrics(meta.path("metrics"))
                val txFilter = meta.path("txFilter").takeIf { it.isObject }?.let { readTxFilter(it) }
                val explorerLink = meta.path("explorerLink").takeIf { it.isObject }?.let { readExplorerLink(it) }
                val severity = meta.path("severity").asString("opportunity")

                when (section) {
                    FindingSection.savings.name -> {
                        val scenarioNode = meta.path("simulationScenario").takeIf { !it.isMissingNode && !it.isNull }
                        val validatedScenario = scenarioNode?.let {
                            AnalyticsAiReviewVerifier.sanitizeScenario(it, catalogue, catByCode)?.toScenarioDto()
                        }
                        savingsList += AnalyticsSavingsFindingDto(
                            findingId = fid,
                            candidateId = meta.path("candidateId").asString(fid),
                            localName = localName,
                            mergedVariants = mergedVariants,
                            categoryCode = catCode,
                            groupCode = groupCode,
                            title = title,
                            detail = detail,
                            whyFlagged = "",
                            severity = severity,
                            action = meta.path("action").takeIf { it.isString }?.asString(),
                            potentialMonthlySaving = parseDecimal(meta.path("potentialMonthlySaving")),
                            potentialAnnualSaving = parseDecimal(meta.path("potentialAnnualSaving")),
                            metrics = metrics,
                            citations = emptyList(),
                            txFilter = txFilter,
                            explorerLink = explorerLink,
                            simulationScenario = validatedScenario,
                        )
                    }
                    FindingSection.whatChanged.name -> {
                        changedList += AnalyticsChangeFindingDto(
                            findingId = fid,
                            categoryCode = catCode,
                            groupCode = groupCode,
                            localName = localName,
                            mergedVariants = mergedVariants,
                            title = title,
                            detail = detail,
                            changeKind = meta.path("changeKind").asString("drift"),
                            severity = severity,
                            metrics = metrics,
                            citations = emptyList(),
                            txFilter = txFilter,
                            explorerLink = explorerLink,
                        )
                    }
                    FindingSection.budgets.name -> {
                        budgetsList += AnalyticsBudgetFindingDto(
                            findingId = fid,
                            categoryCode = catCode,
                            title = title,
                            detail = detail,
                            severity = severity,
                            metrics = metrics,
                            citations = emptyList(),
                            txFilter = txFilter,
                            explorerLink = explorerLink,
                        )
                    }
                }
            }
        } else if (result != null) {
            // Backwards compatibility for pre-ar6 persisted runs
            mapLegacySections(result, payload, localLabels, catByCode, catalogue, hasBudgets, periodYear, periodMonth, savingsList, changedList, budgetsList)
        }

        val dataGaps = result?.path("dataGaps")?.takeIf { it.isArray }?.toList()?.map { item ->
            AnalyticsDataGapDto(
                topic = item.path("topic").asString(""),
                reason = item.path("reason").asString(""),
                citations = AiCitationMapper.fromNode(item, payload) { parent -> resolveContext(parent, localLabels) },
            )
        } ?: emptyList()

        return AnalyticsAiRunDto(
            id = id,
            status = status,
            createdAt = createdAt,
            finishedAt = finishedAt,
            scope = AnalyticsAiScopeDto(
                periodYear = periodYear,
                periodMonth = periodMonth,
                reviewKind = reviewKind,
                daysElapsed = daysElapsed,
                daysInMonth = daysInMonth,
                staleSpendingSince = staleSpendingSince,
                hasBudgets = hasBudgets,
            ),
            model = model,
            promptVersion = promptVersion,
            summary = result?.path("summary")?.takeIf { it.isString }?.asString()?.ifBlank { null },
            savingsOpportunities = savingsList,
            whatChanged = changedList,
            budgetFindings = budgetsList,
            dataGaps = dataGaps,
            savingsOmitted = localLabels?.path("savingsOmitted")?.asInt(0) ?: 0,
            whatChangedOmitted = localLabels?.path("whatChangedOmitted")?.asInt(0) ?: 0,
            budgetsOmitted = localLabels?.path("budgetsOmitted")?.asInt(0) ?: 0,
            modelWrittenCount = result?.path("modelWrittenCount")?.takeIf { it.isNumber }?.asInt(),
            templateFallbackCount = result?.path("templateFallbackCount")?.takeIf { it.isNumber }?.asInt(),
            findingsDropped = findingsDropped,
            citationsTotal = citationsTotal,
            citationsUngrounded = citationsUngrounded,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            latencyMs = latencyMs,
            errorCode = errorCode,
            followups = runFollowups.map { it.toDto(payload, localLabels) },
        )
    }

    private fun mapLegacySections(
        result: JsonNode,
        payload: JsonNode?,
        localLabels: JsonNode?,
        catByCode: Map<String, CategoryView>,
        catalogue: List<CatalogueEntry>,
        hasBudgets: Boolean,
        periodYear: Int,
        periodMonth: Int,
        savingsOut: MutableList<AnalyticsSavingsFindingDto>,
        changedOut: MutableList<AnalyticsChangeFindingDto>,
        budgetsOut: MutableList<AnalyticsBudgetFindingDto>,
    ) {
        val periodYm = YearMonth.of(periodYear, periodMonth)
        val periodFrom = periodYm.atDay(1)
        val periodTo = periodYm.atEndOfMonth()
        val windowFrom = periodYm.minusMonths(11).atDay(1)

        result.path("savingsOpportunities").takeIf { it.isArray }?.forEach { item ->
            val cid = item.path("candidateId").asString("")
            val meta = localLabels?.path(cid)?.takeIf { it.isObject }
            val scenario = item.path("simulationScenario").takeIf { !it.isMissingNode && !it.isNull }
                ?.let { AnalyticsAiReviewVerifier.sanitizeScenario(it, catalogue, catByCode)?.toScenarioDto() }
            savingsOut += AnalyticsSavingsFindingDto(
                findingId = cid,
                candidateId = cid,
                localName = meta?.path("localName")?.takeIf { it.isString }?.asString(),
                categoryCode = meta?.path("categoryCode")?.takeIf { it.isString }?.asString(),
                groupCode = meta?.path("groupCode")?.takeIf { it.isString }?.asString(),
                title = item.path("title").asString(""),
                detail = item.path("detail").asString(""),
                whyFlagged = item.path("whyFlagged").asString(""),
                severity = item.path("severity").asString("opportunity"),
                action = meta?.path("action")?.takeIf { it.isString }?.asString(),
                potentialMonthlySaving = parseDecimal(meta?.path("potentialMonthlySaving")),
                potentialAnnualSaving = parseDecimal(meta?.path("potentialAnnualSaving")),
                metrics = emptyList(),
                citations = AiCitationMapper.fromNode(item, payload) { p -> resolveContext(p, localLabels) },
                txFilter = meta?.path("txFilter")?.takeIf { it.isObject }?.let { readTxFilter(it) },
                explorerLink = meta?.path("explorerLink")?.takeIf { it.isObject }?.let { readExplorerLink(it) },
                simulationScenario = scenario,
            )
        }
        result.path("whatChanged").takeIf { it.isArray }?.forEach { item ->
            val catCode = item.path("categoryCode").takeIf { it.isString }?.asString()?.ifBlank { null }
            val groupCode = item.path("groupCode").takeIf { it.isString }?.asString()?.ifBlank { null }
                ?: catCode?.let { catByCode[it]?.group }
            changedOut += AnalyticsChangeFindingDto(
                categoryCode = catCode,
                groupCode = groupCode,
                title = item.path("title").asString(""),
                detail = item.path("detail").asString(""),
                changeKind = item.path("changeKind").asString("drift"),
                severity = item.path("severity").asString("warning"),
                metrics = emptyList(),
                citations = AiCitationMapper.fromNode(item, payload) { p -> resolveContext(p, localLabels) },
                txFilter = catCode?.let { TransactionLinkFilterDto(from = periodFrom, to = periodTo, direction = "expense", categoryCode = it) },
                explorerLink = catCode?.let { ExplorerLinkDto("category", it, preset = "custom", from = windowFrom, to = periodTo) },
            )
        }
        if (hasBudgets) {
            result.path("budgetFindings").takeIf { it.isArray }?.forEach { item ->
                val catCode = item.path("categoryCode").takeIf { it.isString }?.asString()?.ifBlank { null }
                budgetsOut += AnalyticsBudgetFindingDto(
                    categoryCode = catCode,
                    title = item.path("title").asString(""),
                    detail = item.path("detail").asString(""),
                    severity = item.path("severity").asString("warning"),
                    metrics = emptyList(),
                    citations = AiCitationMapper.fromNode(item, payload) { p -> resolveContext(p, localLabels) },
                    txFilter = catCode?.let { TransactionLinkFilterDto(from = periodFrom, to = periodTo, direction = "expense", categoryCode = it) },
                    explorerLink = catCode?.let { ExplorerLinkDto("category", it, preset = "custom", from = windowFrom, to = periodTo) },
                )
            }
        }
    }

    private fun AnalyticsAiReviewFollowup.toDto(payload: JsonNode?, localLabels: JsonNode?): AnalyticsAiFollowupDto {
        val citationsNode = runCatching { mapper.readTree(citationsJson) }.getOrNull()
        val citations = AiCitationMapper.fromArray(citationsNode, payload) { parent -> resolveContext(parent, localLabels) }
        return AnalyticsAiFollowupDto(
            id = id,
            question = question,
            answer = answer,
            citations = citations,
            citationsTotal = if (payload != null) citations.size else citationsTotal,
            citationsUngrounded = if (payload != null) citations.count { !it.grounded } else citationsUngrounded,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            latencyMs = latencyMs,
            createdAt = createdAt,
        )
    }

    private fun resolveContext(parent: JsonNode, localLabels: JsonNode?): String? {
        val id = parent.path("id").takeIf { it.isString }?.asString()
        if (!id.isNullOrBlank()) {
            val localName = localLabels?.path("findings")?.path(id)?.path("localName")?.takeIf { it.isString }?.asString()
                ?: localLabels?.path(id)?.path("localName")?.takeIf { it.isString }?.asString()
            if (!localName.isNullOrBlank()) return localName
        }
        val subject = parent.path("subject").takeIf { it.isString }?.asString()
        if (!subject.isNullOrBlank()) return subject
        val cat = parent.path("categoryName").takeIf { it.isString }?.asString()
            ?: parent.path("categoryCode").takeIf { it.isString }?.asString()
        if (!cat.isNullOrBlank()) return cat
        val target = parent.path("targetMonthly").takeIf { it.isNumber }?.asInt()
        if (target != null) return "€$target/mo"
        return null
    }

    private fun readMetrics(node: JsonNode): List<VerifiedMetricDto> {
        if (!node.isArray) return emptyList()
        return node.mapNotNull { m ->
            val key = m.path("key").takeIf { it.isString }?.asString() ?: return@mapNotNull null
            val kind = m.path("kind").takeIf { it.isString }?.asString() ?: "money"
            val value = m.path("value").takeIf { it.isString || it.isNumber }?.asString() ?: return@mapNotNull null
            VerifiedMetricDto(key = key, kind = kind, value = value)
        }
    }

    private fun parseDecimal(node: JsonNode?): BigDecimal? =
        node?.takeIf { it.isNumber || it.isString }?.let { runCatching { BigDecimal(it.asString()) }.getOrNull() }

    private fun readTxFilter(node: JsonNode): TransactionLinkFilterDto? {
        val from = node.path("from").takeIf { it.isString }?.asString()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val to = node.path("to").takeIf { it.isString }?.asString()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return TransactionLinkFilterDto(
            from = from,
            to = to,
            direction = node.path("direction").takeIf { it.isString }?.asString() ?: "expense",
            categoryCode = node.path("categoryCode").takeIf { it.isString }?.asString()?.ifBlank { null },
            categoryGroup = node.path("categoryGroup").takeIf { it.isString }?.asString()?.ifBlank { null },
            q = node.path("q").takeIf { it.isString }?.asString()?.ifBlank { null },
        )
    }

    private fun readExplorerLink(node: JsonNode): ExplorerLinkDto? {
        val scopeType = node.path("scopeType").takeIf { it.isString }?.asString() ?: return null
        val scopeCode = node.path("scopeCode").takeIf { it.isString }?.asString() ?: return null
        return ExplorerLinkDto(
            scopeType = scopeType,
            scopeCode = scopeCode,
            preset = node.path("preset").takeIf { it.isString }?.asString() ?: "1y",
            from = node.path("from").takeIf { it.isString }?.asString()?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            to = node.path("to").takeIf { it.isString }?.asString()?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }

    private companion object {
        const val RATE_LIMIT_FEATURE = "analytics_ai_review"
    }
}
