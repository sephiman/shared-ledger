package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class QuestionAnswerParserTest {

    private val restaurantsSummer = """
        {"filters": {"direction": "expense",
                     "categories": [{"type": "category", "code": "outings.restaurants"}],
                     "range": {"from": "2025-06-01", "to": "2025-08-31"},
                     "isRefund": false,
                     "priorYear": true},
         "unsupported": [],
         "confidence": 0.93}
    """.trimIndent()

    private fun read(raw: String) = QuestionAnswerParser.parse(raw) as ParsedAnswer.Read

    @Test
    fun `reads filters into one typed object`() {
        val read = read(restaurantsSummer)

        assertThat(read.confidence).isEqualTo(0.93)
        assertThat(read.filters).isEqualTo(
            QuestionFilters(
                direction = Direction.expense,
                scopes = listOf(ScopeRef(ScopeType.category, "outings.restaurants")),
                range = QuestionRange.Between(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 8, 31)),
                isRefund = false,
                priorYear = true,
            ),
        )
    }

    @Test
    fun `how much income so far reads as income over all time`() {
        val read = read("""{"filters":{"direction":"income","categories":[],"range":{"preset":"all"},"isRefund":false},"unsupported":[],"confidence":0.9}""")

        assertThat(read.filters).isEqualTo(QuestionFilters(Direction.income, emptyList(), QuestionRange.Preset("all"), isRefund = false, priorYear = false))
    }

    @Test
    fun `restaurants so far this year reads as year to date, not all time`() {
        val read = read(restaurantsSummer.replace("""{"from": "2025-06-01", "to": "2025-08-31"}""", """{"preset": "ytd"}"""))

        assertThat(read.filters?.range).isEqualTo(QuestionRange.Preset("ytd"))
    }

    @Test
    fun `absent optional filters are left empty, and a range may be open on one side or absent`() {
        val read = read("""{"filters":{"range":{"from":"2026-10-01","to":null}},"unsupported":[],"confidence":0.9}""")
        val noPeriod = read("""{"filters":{"direction":"expense","range":null},"unsupported":[],"confidence":0.9}""")

        assertThat(read.filters).isEqualTo(QuestionFilters(null, emptyList(), QuestionRange.Between(LocalDate.of(2026, 10, 1), null), isRefund = false, priorYear = false))
        assertThat(noPeriod.filters?.range).isNull()
    }

    @Test
    fun `a target in the answer is ignored, as the app decides where filters go`() {
        val read = read(restaurantsSummer.replace("{\"filters\"", "{\"target\": \"budgets\", \"filters\""))

        assertThat(read.filters?.scopes).containsExactly(ScopeRef(ScopeType.category, "outings.restaurants"))
    }

    @Test
    fun `several categories and groups are kept in order, without repeats`() {
        val read = read(
            """{"filters":{"categories":[{"type":"group","code":"groceries"},{"type":"category","code":"outings.restaurants"},
               {"type":"group","code":"groceries"}],"range":{"preset":"ytd"}},"unsupported":[],"confidence":0.9}""",
        )

        assertThat(read.filters?.scopes).containsExactly(
            ScopeRef(ScopeType.group, "groceries"),
            ScopeRef(ScopeType.category, "outings.restaurants"),
        )
        assertThat(read.filters?.priorYear).isFalse()
    }

    @Test
    fun `categories that are not a list of category or group objects are malformed`() {
        assertThat(QuestionAnswerParser.parse("""{"filters":{"categories":{"type":"group","code":"groceries"}},"unsupported":[],"confidence":0.9}"""))
            .isEqualTo(ParsedAnswer.Unreadable(QuestionRejection.malformed))
    }

    @Test
    fun `tolerates markdown fences and prose around the object`() {
        assertThat(QuestionAnswerParser.parse("Sure! Here it is:\n```json\n$restaurantsSummer\n```\nHope that helps."))
            .isInstanceOf(ParsedAnswer.Read::class.java)
    }

    @Test
    fun `an answer without any JSON object is unreadable`() {
        assertThat(QuestionAnswerParser.parse("I think you spent a lot.")).isEqualTo(ParsedAnswer.Unreadable(QuestionRejection.unreadable))
        assertThat(QuestionAnswerParser.parse(null)).isEqualTo(ParsedAnswer.Unreadable(QuestionRejection.unreadable))
    }

    @Test
    fun `a missing confidence or filters field, or a wrongly shaped filter, is malformed`() {
        val withoutConfidence = restaurantsSummer.replace(Regex(""",\s*"confidence": 0.93"""), "")
        val withoutFilters = """{"unsupported":[],"confidence":0.9}"""
        val badDirection = restaurantsSummer.replace("\"expense\"", "\"outgoing\"")
        val badRange = restaurantsSummer.replace("""{"from": "2025-06-01", "to": "2025-08-31"}""", "\"last summer\"")
        val confidenceOutOfBounds = restaurantsSummer.replace("0.93", "1.7")

        listOf(withoutConfidence, withoutFilters, badDirection, badRange, confidenceOutOfBounds).forEach {
            assertThat(QuestionAnswerParser.parse(it)).isEqualTo(ParsedAnswer.Unreadable(QuestionRejection.malformed))
        }
    }

    @Test
    fun `a date that is not a calendar date is an invalid range`() {
        assertThat(QuestionAnswerParser.parse(restaurantsSummer.replace("2025-08-31", "2025-08-32")))
            .isEqualTo(ParsedAnswer.Unreadable(QuestionRejection.invalid_range))
    }

    @Test
    fun `unsupported entries keep their kind and a short label, unknown kinds becoming other`() {
        val read = read(
            restaurantsSummer.replace(
                "\"unsupported\": []",
                """"unsupported": [{"kind":"member","label":"member"},{"kind":"city","label":"${"x".repeat(60)}"}]""",
            ),
        )

        assertThat(read.unsupported).containsExactly(
            UnsupportedFilter(UnsupportedKind.member, "member"),
            UnsupportedFilter(UnsupportedKind.other, "x".repeat(QuestionSchema.MAX_UNSUPPORTED_LABEL)),
        )
    }

    @Test
    fun `a question asking only for filters that do not exist has no filters`() {
        val read = read("""{"filters":null,"unsupported":[{"kind":"member","label":"member"}],"confidence":0.95}""")

        assertThat(read.filters).isNull()
        assertThat(read.unsupported).hasSize(1)
    }
}
