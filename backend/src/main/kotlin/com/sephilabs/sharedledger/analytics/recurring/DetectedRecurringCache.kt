package com.sephilabs.sharedledger.analytics.recurring

import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.recurring.RecurringTemplate
import com.sephilabs.sharedledger.transaction.Direction
import com.sephilabs.sharedledger.transaction.TransactionRepository
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class BankLinkLabel(
    val counterparty: String?,
    val description: String?,
)

data class ExcludedPayeeRule(
    val categoryCode: String,
    val anchorKey: String,
    val memberKeys: Set<String>,
) {
    fun matches(categoryCode: String, candidateKeys: Set<String>): Boolean {
        if (this.categoryCode != categoryCode || candidateKeys.isEmpty()) return false
        return (anchorKey.isNotEmpty() && anchorKey in candidateKeys) ||
            memberKeys.any { it in candidateKeys }
    }
}

/** Read-only SQL queries shared by Forecast/Money flow and Spending review without depending on analytics.review. */
@Repository
class RecurringDataQueries(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val mapper = JsonMapper.builder().build()

    fun bankMetadataForTransactions(householdId: UUID, from: LocalDate, to: LocalDate): Map<UUID, BankLinkLabel> {
        val sql = """
            SELECT pmt.transaction_id AS tx_id, pm.counterparty AS counterparty, pm.description AS description
            FROM pending_movement_transactions pmt
            JOIN pending_movements pm ON pm.id = pmt.pending_movement_id
            JOIN transactions t ON t.id = pmt.transaction_id
            WHERE pm.household_id = :hid
              AND t.household_id = :hid
              AND t.deleted_at IS NULL
              AND t.occurrence_date BETWEEN :from AND :to
        """.trimIndent()
        val result = LinkedHashMap<UUID, BankLinkLabel>()
        jdbc.query(sql, mapOf("hid" to householdId, "from" to from, "to" to to)) { rs ->
            val txId = rs.getObject("tx_id", UUID::class.java)
            result.putIfAbsent(txId, BankLinkLabel(rs.getString("counterparty"), rs.getString("description")))
        }
        return result
    }

    /** Loads user-recorded `split` and `not_recurring` rules directly from `spending_review_decisions`. */
    fun loadDetectionRules(householdId: UUID): Pair<List<PayeeVariantClusterer.SplitRule>, List<ExcludedPayeeRule>> {
        val sql = """
            SELECT subject_key, category_code, decision_type, member_keys_json, split_groups_json
            FROM spending_review_decisions
            WHERE household_id = :hid
              AND decision_type IN ('split', 'not_recurring')
        """.trimIndent()
        val splits = mutableListOf<PayeeVariantClusterer.SplitRule>()
        val excluded = mutableListOf<ExcludedPayeeRule>()
        jdbc.query(sql, mapOf("hid" to householdId)) { rs ->
            val type = rs.getString("decision_type")
            val cat = rs.getString("category_code")
            if (type == "split") {
                val raw = rs.getString("split_groups_json") ?: return@query
                val groups = decodeGroups(raw)
                if (groups.size >= 2) splits += PayeeVariantClusterer.SplitRule(cat, groups)
            } else if (type == "not_recurring") {
                val subjectKey = rs.getString("subject_key") ?: ""
                val anchor = subjectKey.substringAfterLast(':', "")
                val keys = decodeKeys(rs.getString("member_keys_json") ?: "[]")
                excluded += ExcludedPayeeRule(cat, anchor, keys)
            }
        }
        return splits to excluded
    }

    /** Fast fingerprint changing whenever transactions, templates, categories, bank links, or split/not_recurring rules change. */
    fun detectionFingerprint(householdId: UUID): String {
        val sql = """
            SELECT md5(concat_ws('|',
                (SELECT string_agg(
                    t.id::text || ':' || t.occurrence_date || ':' || t.direction || ':' || t.category_code || '=' ||
                    t.amount::text || ':' || t.is_refund || ':' || coalesce(t.recurring_template_id::text, '') || ':' ||
                    coalesce(t.description, ''), ',' ORDER BY t.id)
                   FROM transactions t WHERE t.household_id = :hid AND t.deleted_at IS NULL),
                (SELECT string_agg(
                    r.id::text || ':' || r.active || ':' || r.cadence || ':' || r.category_code || '=' ||
                    r.amount::text || ':' || coalesce(r.description, ''), ',' ORDER BY r.id)
                   FROM recurring_templates r WHERE r.household_id = :hid AND r.deleted_at IS NULL),
                (SELECT string_agg(c.code || ':' || c.essential || ':' || coalesce(c.group_code, ''), ',' ORDER BY c.code)
                   FROM custom_categories c WHERE c.household_id = :hid),
                (SELECT string_agg(pmt.transaction_id::text || ':' || coalesce(pm.counterparty, ''), ',' ORDER BY pmt.transaction_id)
                   FROM pending_movement_transactions pmt
                   JOIN pending_movements pm ON pm.id = pmt.pending_movement_id
                  WHERE pm.household_id = :hid),
                (SELECT string_agg(
                    d.id::text || ':' || d.decision_type || ':' || d.member_keys_json || ':' || coalesce(d.split_groups_json, ''),
                    ',' ORDER BY d.id)
                   FROM spending_review_decisions d
                  WHERE d.household_id = :hid AND d.decision_type IN ('split', 'not_recurring'))
            ))
        """.trimIndent()
        return jdbc.queryForObject(sql, mapOf("hid" to householdId), String::class.java) ?: ""
    }

    private fun decodeKeys(json: String): Set<String> = runCatching {
        val node = mapper.readTree(json)
        if (node.isArray) node.toList().mapNotNull { it.takeIf { s -> s.isString }?.asString() }.toSet() else emptySet()
    }.getOrDefault(emptySet())

    private fun decodeGroups(json: String): List<Set<String>> = runCatching {
        val node = mapper.readTree(json)
        if (node.isArray) {
            node.toList().mapNotNull { arr ->
                if (arr.isArray) arr.toList().mapNotNull { it.takeIf { s -> s.isString }?.asString() }.toSet().takeIf { it.isNotEmpty() }
                else null
            }
        } else emptyList()
    }.getOrDefault(emptyList())
}

/** Cached detection output shared by ForecastEstimator and Money flow projection. */
data class DetectedRecurringSnapshot(
    val allCharges: List<DetectedRecurringCharge>,
    val detectedOnly: List<DetectedRecurringCharge>,
    val detectedMatchedTxIds: Set<UUID>,
    private val matchedAmountByKey: Map<Pair<String, YearMonth>, BigDecimal>,
    private val matchedCountByKey: Map<Pair<String, YearMonth>, Int>,
) {
    fun matchedAmountInMonth(charge: DetectedRecurringCharge, month: YearMonth): BigDecimal =
        matchedAmountByKey[chargeKey(charge) to month] ?: BigDecimal.ZERO

    fun matchedCountInMonth(charge: DetectedRecurringCharge, month: YearMonth): Int =
        matchedCountByKey[chargeKey(charge) to month] ?: 0

    companion object {
        fun chargeKey(c: DetectedRecurringCharge): String = "${c.categoryCode}:${c.anchorKey}"
        val EMPTY = DetectedRecurringSnapshot(emptyList(), emptyList(), emptySet(), emptyMap(), emptyMap())
    }
}

/** Fingerprint-keyed LRU cache for detected recurring charges so Money flow projection & Forecast stay fast. */
@Component
class DetectedRecurringCache(
    private val transactions: TransactionRepository,
    private val queries: RecurringDataQueries,
) {
    private data class CacheKey(
        val householdId: UUID,
        val asOfDate: LocalDate,
        val twoYearWindowEnd: YearMonth,
        val fingerprint: String,
    )

    private val entries = object : LinkedHashMap<CacheKey, DetectedRecurringSnapshot>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, DetectedRecurringSnapshot>?): Boolean =
            size > MAX_ENTRIES
    }

    fun detectForProjection(
        householdId: UUID,
        asOfDate: LocalDate,
        twoYearWindowEnd: YearMonth,
        activeExpenseTemplates: List<RecurringTemplate>,
        categoryMeta: Map<String, Pair<String?, Boolean>>,
        householdMinDate: LocalDate?,
    ): DetectedRecurringSnapshot {
        val fp = queries.detectionFingerprint(householdId)
        val key = CacheKey(householdId, asOfDate, twoYearWindowEnd, fp)
        synchronized(entries) { entries[key] }?.let { return it }

        val lookbackFrom = twoYearWindowEnd.minusMonths((RecurringDetectionConstants.RECURRING_LOOKBACK_MONTHS - 1).toLong()).atDay(1)
        val trailing12Start = twoYearWindowEnd.minusMonths(11).atDay(1)
        val loadThrough = maxOf(asOfDate, twoYearWindowEnd.plusMonths(1).atEndOfMonth())
        val allTx = transactions.findByHouseholdIdAndOccurrenceDateBetween(householdId, lookbackFrom, loadThrough)
        if (allTx.isEmpty()) {
            val empty = DetectedRecurringSnapshot.EMPTY
            synchronized(entries) { entries[key] = empty }
            return empty
        }
        val bankMeta = queries.bankMetadataForTransactions(householdId, lookbackFrom, loadThrough)
        val (splitRules, excludedRules) = queries.loadDetectionRules(householdId)

        val records = allTx
            .filter { it.direction == Direction.expense && !it.isRefund && it.amount.signum() > 0 }
            .map { tx ->
                val link = bankMeta[tx.id]
                val meta = categoryMeta[tx.categoryCode]
                ExpenseRecord(
                    id = tx.id,
                    occurrenceDate = tx.occurrenceDate,
                    categoryCode = tx.categoryCode,
                    groupCode = meta?.first,
                    essential = meta?.second ?: false,
                    amount = tx.amount,
                    rawLabel = extractLocalLabel(link?.counterparty, tx.description),
                    bankDescription = link?.description ?: tx.description,
                    recurringTemplateId = tx.recurringTemplateId,
                )
            }

        val allCharges = RecurringChargeDetector.detect(
            history24m = records,
            activeTemplates = activeExpenseTemplates,
            categoryMeta = categoryMeta,
            trailing12Start = trailing12Start,
            splitRules = splitRules,
            excludedPayeeMatcher = { cat, mKeys -> excludedRules.any { it.matches(cat, mKeys) } },
            recencyReferenceDate = asOfDate,
            householdMinDate = householdMinDate,
            twoYearWindowEnd = twoYearWindowEnd,
        )

        val detectedOnly = allCharges.filter { it.source == RecurringSource.detected }
        val txById = records.associateBy { it.id }
        val detectedMatchedIds = mutableSetOf<UUID>()
        val amountByKey = HashMap<Pair<String, YearMonth>, BigDecimal>()
        val countByKey = HashMap<Pair<String, YearMonth>, Int>()

        for (c in detectedOnly) {
            val ck = DetectedRecurringSnapshot.chargeKey(c)
            for (txId in c.matchedTxIds) {
                detectedMatchedIds += txId
                val r = txById[txId] ?: continue
                val ym = YearMonth.from(r.occurrenceDate)
                val pair = ck to ym
                amountByKey.merge(pair, Money.normalize(r.amount)) { a, b -> a + b }
                countByKey.merge(pair, 1) { a, b -> a + b }
            }
        }

        val snapshot = DetectedRecurringSnapshot(
            allCharges = allCharges,
            detectedOnly = detectedOnly,
            detectedMatchedTxIds = detectedMatchedIds,
            matchedAmountByKey = amountByKey,
            matchedCountByKey = countByKey,
        )
        synchronized(entries) { entries[key] = snapshot }
        return snapshot
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private fun extractLocalLabel(counterparty: String?, txDescription: String?): String? {
        val fromCp = counterparty?.trim()?.takeIf { it.isNotEmpty() }
        if (fromCp != null) return fromCp
        val desc = txDescription?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sep = desc.indexOf(" – ")
        return if (sep > 0) desc.substring(0, sep).trim().ifEmpty { desc } else desc
    }

    private companion object {
        const val MAX_ENTRIES = 64
    }
}
