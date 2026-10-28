package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.AI_SUGGESTION_MIN_CONFIDENCE
import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class QuestionAnswerValidatorTest {

    private val catalogue = listOf(
        CatalogueEntry("outings.restaurants", "Restaurants", "outings", Direction.expense, essential = false),
        CatalogueEntry("income.salary", "Salary", "income", Direction.income, essential = false),
    )
    private val today = LocalDate.of(2026, 10, 28)
    private val restaurants = ScopeRef(ScopeType.category, "outings.restaurants")

    private fun answer(
        direction: Direction? = null,
        scope: ScopeRef? = restaurants,
        range: QuestionRange? = QuestionRange.Preset("1y"),
        isRefund: Boolean = false,
        confidence: Double = 0.9,
        unsupported: List<UnsupportedFilter> = emptyList(),
        scopes: List<ScopeRef> = listOfNotNull(scope),
    ) = ParsedAnswer.Read(QuestionFilters(direction, scopes, range, isRefund, priorYear = false), unsupported, confidence)

    private fun verdict(answer: ParsedAnswer.Read) = QuestionAnswerValidator.validate(answer, catalogue, today)

    private fun rejected(reason: QuestionRejection) = Interpretation.NotUnderstood(reason)

    private fun between(from: LocalDate?, to: LocalDate?) = QuestionRange.Between(from, to)

    @Test
    fun `a valid answer is understood as it came, unsupported parts included`() {
        val unsupported = listOf(UnsupportedFilter(UnsupportedKind.member, "member"))
        val valid = answer(unsupported = unsupported)

        assertThat(verdict(valid)).isEqualTo(Interpretation.Understood(valid.filters, unsupported))
    }

    @Test
    fun `how much income so far, and restaurants so far this year, are both valid`() {
        assertThat(verdict(answer(direction = Direction.income, scope = null, range = QuestionRange.Preset("all"))))
            .isInstanceOf(Interpretation.Understood::class.java)
        assertThat(verdict(answer(direction = Direction.expense, range = QuestionRange.Preset("ytd"))))
            .isInstanceOf(Interpretation.Understood::class.java)
    }

    @Test
    fun `an invented category or group is not understood`() {
        assertThat(verdict(answer(scope = ScopeRef(ScopeType.category, "outings.cinema")))).isEqualTo(rejected(QuestionRejection.unknown_category))
        assertThat(verdict(answer(scope = ScopeRef(ScopeType.group, "pets")))).isEqualTo(rejected(QuestionRejection.unknown_category))
        assertThat(verdict(answer(scope = ScopeRef(ScopeType.group, "ungrouped")))).isEqualTo(rejected(QuestionRejection.unknown_category))
    }

    @Test
    fun `every one of several categories and groups must exist and agree with the direction`() {
        val outings = ScopeRef(ScopeType.group, "outings")
        assertThat(verdict(answer(direction = Direction.expense, scopes = listOf(restaurants, outings)))).isInstanceOf(Interpretation.Understood::class.java)
        assertThat(verdict(answer(scopes = listOf(restaurants, ScopeRef(ScopeType.group, "pets"))))).isEqualTo(rejected(QuestionRejection.unknown_category))
        assertThat(verdict(answer(direction = Direction.expense, scopes = listOf(restaurants, ScopeRef(ScopeType.category, "income.salary")))))
            .isEqualTo(rejected(QuestionRejection.wrong_direction))
    }

    @Test
    fun `the direction must agree with the category, the group and refunds`() {
        assertThat(verdict(answer(direction = Direction.income, scope = restaurants))).isEqualTo(rejected(QuestionRejection.wrong_direction))
        assertThat(verdict(answer(direction = Direction.income, scope = ScopeRef(ScopeType.group, "outings")))).isEqualTo(rejected(QuestionRejection.wrong_direction))
        assertThat(verdict(answer(direction = Direction.income, scope = null, isRefund = true))).isEqualTo(rejected(QuestionRejection.wrong_direction))
        assertThat(verdict(answer(direction = Direction.expense, isRefund = true))).isInstanceOf(Interpretation.Understood::class.java)
    }

    @Test
    fun `a reversed range is invalid`() {
        assertThat(verdict(answer(range = between(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 6, 1))))).isEqualTo(rejected(QuestionRejection.invalid_range))
    }

    @Test
    fun `a range may end on the last day of the current month but not start after today or reach past the month`() {
        assertThat(verdict(answer(range = between(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))))).isInstanceOf(Interpretation.Understood::class.java)
        assertThat(verdict(answer(range = between(LocalDate.of(2026, 10, 29), null)))).isEqualTo(rejected(QuestionRejection.future_range))
        assertThat(verdict(answer(range = between(null, LocalDate.of(2026, 11, 1))))).isEqualTo(rejected(QuestionRejection.future_range))
    }

    @Test
    fun `a span wider than the endpoints accept, or a preset that does not exist, is invalid`() {
        assertThat(verdict(answer(range = between(LocalDate.of(2000, 1, 1), LocalDate.of(2026, 10, 1))))).isEqualTo(rejected(QuestionRejection.invalid_range))
        assertThat(verdict(answer(range = QuestionRange.Preset("5y")))).isEqualTo(rejected(QuestionRejection.invalid_range))
    }

    @Test
    fun `below the shared confidence threshold nothing is applied`() {
        assertThat(verdict(answer(confidence = AI_SUGGESTION_MIN_CONFIDENCE - 0.01))).isEqualTo(rejected(QuestionRejection.low_confidence))
        assertThat(verdict(answer(confidence = AI_SUGGESTION_MIN_CONFIDENCE))).isInstanceOf(Interpretation.Understood::class.java)
    }

    @Test
    fun `without filters the answer is understood only when it reports what could not be applied`() {
        val unsupported = listOf(UnsupportedFilter(UnsupportedKind.account, "account"))

        assertThat(verdict(ParsedAnswer.Read(null, unsupported, 0.95))).isEqualTo(Interpretation.Understood(null, unsupported))
        assertThat(verdict(ParsedAnswer.Read(null, emptyList(), 0.95))).isEqualTo(rejected(QuestionRejection.nothing_asked))
    }
}
