package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CategorySuggestionParserTest {

    private val catalogue = mapOf(
        "groceries.groceries" to Direction.expense,
        "outings.restaurants" to Direction.expense,
        "income.salary" to Direction.income,
    )
    private val batch = mapOf("m1" to Direction.expense, "m2" to Direction.expense, "m3" to Direction.income)

    private fun parse(raw: String?) = CategorySuggestionParser.parse(raw, batch, catalogue)

    private val clean = """
        [{"id":"m1","categoryCode":"groceries.groceries","confidence":0.97},
         {"id":"m2","categoryCode":"outings.restaurants","confidence":0.6},
         {"id":"m3","categoryCode":"income.salary","confidence":1}]
    """.trimIndent()

    @Test
    fun `reads one suggestion per movement from a clean array`() {
        assertThat(parse(clean)).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "m1" to ParsedSuggestion.Valid("groceries.groceries", 0.97),
                "m2" to ParsedSuggestion.Valid("outings.restaurants", 0.6),
                "m3" to ParsedSuggestion.Valid("income.salary", 1.0),
            ),
        )
    }

    @Test
    fun `tolerates markdown fences around the array`() {
        assertThat(parse("```json\n$clean\n```").values).allMatch { it is ParsedSuggestion.Valid }
    }

    @Test
    fun `tolerates prose before and after the array, including brackets in the prose`() {
        val raw = "Here are the results [as requested]:\n$clean\nLet me know [if] you need more."
        assertThat(parse(raw).values).allMatch { it is ParsedSuggestion.Valid }
    }

    @Test
    fun `a code outside the catalogue is no suggestion for that item only`() {
        val raw = """[{"id":"m1","categoryCode":"groceries.supermarket","confidence":0.99},
                      {"id":"m2","categoryCode":"outings.restaurants","confidence":0.9}]"""
        val parsed = parse(raw)
        assertThat(parsed["m1"]).isEqualTo(ParsedSuggestion.Invalid(InvalidReason.unknown_code))
        assertThat(parsed["m2"]).isEqualTo(ParsedSuggestion.Valid("outings.restaurants", 0.9))
    }

    @Test
    fun `an income category for an expense is a direction mismatch`() {
        val raw = """[{"id":"m1","categoryCode":"income.salary","confidence":0.99}]"""
        assertThat(parse(raw)["m1"]).isEqualTo(ParsedSuggestion.Invalid(InvalidReason.direction_mismatch))
    }

    @Test
    fun `a movement the answer leaves out is missing`() {
        val raw = """[{"id":"m1","categoryCode":"groceries.groceries","confidence":0.99}]"""
        val parsed = parse(raw)
        assertThat(parsed["m2"]).isEqualTo(ParsedSuggestion.Invalid(InvalidReason.missing))
        assertThat(parsed["m3"]).isEqualTo(ParsedSuggestion.Invalid(InvalidReason.missing))
    }

    @Test
    fun `a cut-off array keeps every complete entry before the cut`() {
        val raw = """[{"id":"m1","categoryCode":"groceries.groceries","confidence":0.95},
                      {"id":"m2","categoryCode":"outings.restaurants","confidence":0.9},
                      {"id":"m3","categoryCo"""
        val parsed = parse(raw)
        assertThat(parsed["m1"]).isEqualTo(ParsedSuggestion.Valid("groceries.groceries", 0.95))
        assertThat(parsed["m2"]).isEqualTo(ParsedSuggestion.Valid("outings.restaurants", 0.9))
        assertThat(parsed["m3"]).isEqualTo(ParsedSuggestion.Invalid(InvalidReason.missing))
    }

    @Test
    fun `a confidence that is not a number in 0 to 1 is invalid`() {
        val raw = """[{"id":"m1","categoryCode":"groceries.groceries","confidence":"high"},
                      {"id":"m2","categoryCode":"outings.restaurants","confidence":1.4}]"""
        val parsed = parse(raw)
        assertThat(parsed["m1"]).isEqualTo(ParsedSuggestion.Invalid(InvalidReason.invalid_confidence))
        assertThat(parsed["m2"]).isEqualTo(ParsedSuggestion.Invalid(InvalidReason.invalid_confidence))
    }

    @Test
    fun `ids not in the batch are ignored and the first answer for an id wins`() {
        val raw = """[{"id":"m9","categoryCode":"groceries.groceries","confidence":0.9},
                      {"id":"m1","categoryCode":"groceries.groceries","confidence":0.9},
                      {"id":"m1","categoryCode":"outings.restaurants","confidence":0.99}]"""
        val parsed = parse(raw)
        assertThat(parsed).doesNotContainKey("m9")
        assertThat(parsed["m1"]).isEqualTo(ParsedSuggestion.Valid("groceries.groceries", 0.9))
    }

    @Test
    fun `prose without any array, or no content at all, leaves every item missing`() {
        for (raw in listOf("I cannot categorise these movements.", "", null, "[1, 2, 3]")) {
            assertThat(parse(raw).values).allMatch { it == ParsedSuggestion.Invalid(InvalidReason.missing) }
        }
    }
}
