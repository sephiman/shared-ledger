package com.sephilabs.sharedledger.analytics.review

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class BankLinkMeta(
    val counterparty: String?,
    val description: String?,
)

data class LoanInterestAggregate(
    val total: BigDecimal,
    val count: Int,
)

/** Read-only SQL queries backing savings-candidate extraction and cache fingerprinting. */
@Repository
class SpendingReviewDataQueries(
    private val jdbc: NamedParameterJdbcTemplate,
) {

    /** Maps confirmed transaction IDs in `from..to` to their linked bank movement's counterparty and description. */
    fun bankMetadataForTransactions(householdId: UUID, from: LocalDate, to: LocalDate): Map<UUID, BankLinkMeta> {
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
        val result = LinkedHashMap<UUID, BankLinkMeta>()
        jdbc.query(sql, mapOf("hid" to householdId, "from" to from, "to" to to)) { rs ->
            val txId = rs.getObject("tx_id", UUID::class.java)
            result.putIfAbsent(txId, BankLinkMeta(rs.getString("counterparty"), rs.getString("description")))
        }
        return result
    }

    /** Total charged loan interest across amortizable liabilities within `from..to`. */
    fun chargedLoanInterest(householdId: UUID, from: LocalDate, to: LocalDate): LoanInterestAggregate? {
        val sql = """
            SELECT COALESCE(SUM(e.interest), 0) AS total_interest, COUNT(e.id) AS entry_count
            FROM amortization_entries e
            JOIN amortization_parts p ON p.id = e.part_id AND p.deleted_at IS NULL
            JOIN liabilities l ON l.id = p.liability_id AND l.deleted_at IS NULL
            WHERE l.household_id = :hid
              AND e.charge_date BETWEEN :from AND :to
              AND e.interest > 0
        """.trimIndent()
        return jdbc.queryForObject(sql, mapOf("hid" to householdId, "from" to from, "to" to to)) { rs, _ ->
            val cnt = rs.getInt("entry_count")
            if (cnt == 0) null else LoanInterestAggregate(rs.getBigDecimal("total_interest"), cnt)
        }
    }

    /** Email local-parts of household members so self-transfers are never treated as external merchants. */
    fun householdMemberTokens(householdId: UUID): Set<String> {
        val sql = """
            SELECT u.email
            FROM household_members hm
            JOIN users u ON u.id = hm.user_id
            WHERE hm.household_id = :hid
        """.trimIndent()
        val tokens = mutableSetOf<String>()
        jdbc.query(sql, mapOf("hid" to householdId)) { rs ->
            val email = rs.getString("email") ?: return@query
            val local = email.substringBefore("@")
            val folded = MerchantSanitizer.fold(local)
            if (folded.length >= 4) tokens += folded
            folded.split('.', '_', '-', '+').filter { it.length >= 4 }.forEach { tokens += it }
        }
        return tokens
    }

    /** Latest non-template expense date on or before [upTo], used to identify the last month with real spending. */
    fun latestNonTemplateExpenseDate(householdId: UUID, upTo: LocalDate): LocalDate? {
        val sql = """
            SELECT MAX(t.occurrence_date)
            FROM transactions t
            WHERE t.household_id = :hid
              AND t.deleted_at IS NULL
              AND t.direction = 'expense'
              AND t.recurring_template_id IS NULL
              AND t.occurrence_date <= :upTo
        """.trimIndent()
        return jdbc.queryForObject(sql, mapOf("hid" to householdId, "upTo" to upTo), LocalDate::class.java)
    }

    /** Monthly net income totals per category within `from..to` (refunds already stored signed). */
    fun incomeMonthlyTotalsByCategory(
        householdId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): Map<String, Map<YearMonth, BigDecimal>> {
        val sql = """
            SELECT t.category_code AS category_code,
                   EXTRACT(YEAR FROM t.occurrence_date)::int AS y,
                   EXTRACT(MONTH FROM t.occurrence_date)::int AS m,
                   COALESCE(SUM(t.amount), 0) AS total
            FROM transactions t
            WHERE t.household_id = :hid
              AND t.deleted_at IS NULL
              AND t.direction = 'income'
              AND t.occurrence_date BETWEEN :from AND :to
            GROUP BY t.category_code, y, m
        """.trimIndent()
        val out = LinkedHashMap<String, MutableMap<YearMonth, BigDecimal>>()
        jdbc.query(sql, mapOf("hid" to householdId, "from" to from, "to" to to)) { rs ->
            val cat = rs.getString("category_code")
            val ym = YearMonth.of(rs.getInt("y"), rs.getInt("m"))
            val total = rs.getBigDecimal("total") ?: BigDecimal.ZERO
            out.computeIfAbsent(cat) { LinkedHashMap() }[ym] = total
        }
        return out
    }

    /** Monthly net expense totals per requested category within `from..to`, used to evaluate budget fit over 6m and 12m. */
    fun expenseMonthlyTotalsByCategory(
        householdId: UUID,
        from: LocalDate,
        to: LocalDate,
        categoryCodes: Collection<String>,
    ): Map<String, Map<YearMonth, BigDecimal>> {
        if (categoryCodes.isEmpty()) return emptyMap()
        val sql = """
            SELECT t.category_code AS category_code,
                   EXTRACT(YEAR FROM t.occurrence_date)::int AS y,
                   EXTRACT(MONTH FROM t.occurrence_date)::int AS m,
                   COALESCE(SUM(t.amount), 0) AS total
            FROM transactions t
            WHERE t.household_id = :hid
              AND t.deleted_at IS NULL
              AND t.direction = 'expense'
              AND t.category_code IN (:codes)
              AND t.occurrence_date BETWEEN :from AND :to
            GROUP BY t.category_code, y, m
        """.trimIndent()
        val out = LinkedHashMap<String, MutableMap<YearMonth, BigDecimal>>()
        jdbc.query(sql, mapOf("hid" to householdId, "codes" to categoryCodes.toList(), "from" to from, "to" to to)) { rs ->
            val cat = rs.getString("category_code")
            val ym = YearMonth.of(rs.getInt("y"), rs.getInt("m"))
            val total = rs.getBigDecimal("total") ?: BigDecimal.ZERO
            out.computeIfAbsent(cat) { LinkedHashMap() }[ym] = total
        }
        return out
    }

    /** Changes whenever any input to savings candidates changes (transactions, recurring templates,
     *  categories, bank-movement links, or charged amortization entries). Mirrors FireSettingsRepository.dataFingerprint. */
    fun dataFingerprint(householdId: UUID): String {
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
                (SELECT string_agg(g.code || ':' || g.essential || ':' || g.active || ':' || coalesce(g.group_code, ''), ',' ORDER BY g.code)
                   FROM categories g),
                (SELECT string_agg(pmt.transaction_id::text || ':' || coalesce(pm.counterparty, ''), ',' ORDER BY pmt.transaction_id)
                   FROM pending_movement_transactions pmt
                   JOIN pending_movements pm ON pm.id = pmt.pending_movement_id
                  WHERE pm.household_id = :hid),
                (SELECT string_agg(e.id::text || ':' || e.charge_date || '=' || e.interest::text, ',' ORDER BY e.id)
                   FROM amortization_entries e
                   JOIN amortization_parts p ON p.id = e.part_id AND p.deleted_at IS NULL
                   JOIN liabilities l ON l.id = p.liability_id AND l.deleted_at IS NULL
                  WHERE l.household_id = :hid)
            ))
        """.trimIndent()
        return jdbc.queryForObject(sql, mapOf("hid" to householdId), String::class.java) ?: ""
    }
}
