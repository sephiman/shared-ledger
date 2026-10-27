package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RuleGeneralisationParserTest {

    private val known = setOf("r1", "r2", "r3", "r4")

    private fun parse(raw: String?) = RuleGeneralisationParser.parse(raw, known)

    private val clean = """
        [{"field":"counterparty","pattern":"Albert Heijn","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r1","r2"],"confidence":0.96},
         {"field":"counterparty","pattern":"Lidl","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r3","r4"],"confidence":0.9}]
    """.trimIndent()

    @Test
    fun `reads every proposal of a clean array`() {
        val answer = parse(clean)

        assertThat(answer.readable).isTrue()
        assertThat(answer.proposals).containsExactly(
            ParsedProposal.Valid("Albert Heijn", RuleField.counterparty, Direction.expense, "groceries.groceries", listOf("r1", "r2"), 0.96),
            ParsedProposal.Valid("Lidl", RuleField.counterparty, Direction.expense, "groceries.groceries", listOf("r3", "r4"), 0.9),
        )
    }

    @Test
    fun `tolerates markdown fences and prose around the array`() {
        val answer = parse("Sure [here]:\n```json\n$clean\n```\nDone.")

        assertThat(answer.proposals).hasSize(2).allMatch { it is ParsedProposal.Valid }
    }

    @Test
    fun `keeps the complete proposals of an array cut off mid-way`() {
        val cut = clean.substringBefore("""{"field":"counterparty","pattern":"Lidl""") + """{"field":"counterparty","pattern":"Li"""

        val answer = parse(cut)

        assertThat(answer.readable).isTrue()
        assertThat(answer.proposals).hasSize(1)
        assertThat((answer.proposals.single() as ParsedProposal.Valid).pattern).isEqualTo("Albert Heijn")
    }

    @Test
    fun `an empty array is a readable answer with nothing to propose`() {
        val answer = parse("[]")

        assertThat(answer.readable).isTrue()
        assertThat(answer.proposals).isEmpty()
    }

    @Test
    fun `an answer with no array at all is unreadable`() {
        assertThat(parse("I could not find any groups.").readable).isFalse()
        assertThat(parse(null).readable).isFalse()
    }

    @Test
    fun `a proposal absorbing an id that was never sent is discarded`() {
        val raw = """[{"field":"counterparty","pattern":"Vomar","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r1","r9"],"confidence":0.9}]"""

        assertThat(parse(raw).proposals).containsExactly(ParsedProposal.Invalid(ProposalParseFailure.unknown_rule))
    }

    @Test
    fun `a group of one, counting repeated ids once, is discarded`() {
        val raw = """[{"field":"counterparty","pattern":"Vomar","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r1"],"confidence":0.9},
                      {"field":"counterparty","pattern":"Vomar","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r2","r2"],"confidence":0.9}]"""

        assertThat(parse(raw).proposals).containsExactly(
            ParsedProposal.Invalid(ProposalParseFailure.single_rule),
            ParsedProposal.Invalid(ProposalParseFailure.single_rule),
        )
    }

    @Test
    fun `repeated ids in a group are kept once, in order`() {
        val raw = """[{"field":"counterparty","pattern":"Lidl","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r3","r4","r3"],"confidence":0.9}]"""

        assertThat((parse(raw).proposals.single() as ParsedProposal.Valid).absorbs).containsExactly("r3", "r4")
    }

    @Test
    fun `missing or mistyped fields make that proposal malformed and leave the others alone`() {
        val raw = """[{"direction":"expense","categoryCode":"groceries.groceries","absorbs":["r1","r2"],"confidence":0.9},
                      {"field":"counterparty","pattern":"Lidl","direction":"sideways","categoryCode":"groceries.groceries","absorbs":["r1","r2"],"confidence":0.9},
                      {"field":"counterparty","pattern":"Lidl","direction":"expense","absorbs":["r1","r2"],"confidence":0.9},
                      {"field":"counterparty","pattern":"Lidl","direction":"expense","categoryCode":"groceries.groceries","absorbs":"r1,r2","confidence":0.9},
                      {"field":"counterparty","pattern":"Lidl","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r1",2],"confidence":0.9},
                      {"field":"counterparty","pattern":"Lidl","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r1","r2"],"confidence":1.2},
                      {"field":"counterparty","pattern":"  ","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r1","r2"],"confidence":0.9},
                      {"pattern":"Lidl","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r1","r2"],"confidence":0.9},
                      {"field":"amount","pattern":"Lidl","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r1","r2"],"confidence":0.9},
                      {"field":"counterparty","pattern":"Lidl","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r3","r4"],"confidence":0.9}]"""

        val proposals = parse(raw).proposals

        assertThat(proposals.dropLast(1)).allMatch { it == ParsedProposal.Invalid(ProposalParseFailure.malformed) }
        assertThat(proposals.last()).isInstanceOf(ParsedProposal.Valid::class.java)
    }

    @Test
    fun `a description proposal keeps its field`() {
        val raw = """[{"field":"description","pattern":"Vodafone","direction":"expense","categoryCode":"home.utilities","absorbs":["r1","r2"],"confidence":0.9}]"""

        assertThat((parse(raw).proposals.single() as ParsedProposal.Valid).field).isEqualTo(RuleField.description)
    }

    @Test
    fun `the pattern is trimmed`() {
        val raw = """[{"field":"counterparty","pattern":"  Lidl ","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r3","r4"],"confidence":0.9}]"""

        assertThat((parse(raw).proposals.single() as ParsedProposal.Valid).pattern).isEqualTo("Lidl")
    }
}
