package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.RuleField
import com.sephilabs.sharedledger.bank.RuleOp
import com.sephilabs.sharedledger.bank.aisuggestion.AiProviderSettings
import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
import com.sephilabs.sharedledger.bank.aisuggestion.ChatPrompt
import com.sephilabs.sharedledger.bank.aisuggestion.OpenAiCompatibleChatClient
import com.sephilabs.sharedledger.bank.aisuggestion.SuggestionModelException
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.times
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpTimeoutException
import java.util.UUID

/** The generalisation prompt through the same OpenAI-compatible client the inbox uses, provider simulated. */
class RuleGeneralisationProviderTest {

    private val settings = AiProviderSettings.Configured("https://ai.test/v1", "secret-key", "test-model", 1000)
    private val endpoint = "https://ai.test/v1/chat/completions"
    private val json = JsonMapper.builder().build()
    private val prompt = ChatPrompt(
        system = RuleGeneralisationPrompt.system(
            listOf(CatalogueEntry("groceries.groceries", "Groceries", "groceries", Direction.expense, essential = true)),
        ),
        user = RuleGeneralisationPrompt.user(
            listOf(
                PromptRule("r1", RuleField.counterparty, RuleOp.equals, "Lidl 737 Almere", Direction.expense, "groceries.groceries"),
                PromptRule("r2", RuleField.counterparty, RuleOp.equals, "LIDL 1203", Direction.expense, "groceries.groceries"),
            ),
        ),
        sessionId = UUID.randomUUID(),
    )
    private val proposals =
        """[{"pattern":"Lidl","field":"counterparty","direction":"expense","categoryCode":"groceries.groceries","absorbs":["r1","r2"],"confidence":0.94}]"""

    private fun client(): Pair<OpenAiCompatibleChatClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return OpenAiCompatibleChatClient(settings, builder, "1.2.3") to server
    }

    private fun body(content: String) = json.writeValueAsString(
        mapOf(
            "choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to content))),
            "usage" to mapOf("prompt_tokens" to 900, "completion_tokens" to 60),
        ),
    )

    @Test
    fun `a plain completion at temperature 0 comes back as proposals`() {
        val (client, server) = client()
        server.expect(requestTo(endpoint))
            .andExpect(jsonPath("$.temperature").value(0))
            .andExpect(jsonPath("$.stream").value(false))
            .andExpect(jsonPath("$.tools").doesNotExist())
            .andExpect(jsonPath("$.response_format").doesNotExist())
            .andExpect(jsonPath("$.messages[1].content").value(prompt.user))
            .andRespond(withSuccess(body(proposals), MediaType.APPLICATION_JSON))

        val answer = client.complete(prompt)

        assertThat(RuleGeneralisationParser.parse(answer.content, setOf("r1", "r2")).proposals)
            .containsExactly(ParsedProposal.Valid("Lidl", RuleField.counterparty, Direction.expense, "groceries.groceries", listOf("r1", "r2"), 0.94))
        assertThat(answer.inputTokens).isEqualTo(900)
        server.verify()
    }

    @Test
    fun `a 5xx is retried once and the second answer is used`() {
        val (client, server) = client()
        server.expect(requestTo(endpoint)).andRespond(withStatus(HttpStatus.BAD_GATEWAY))
        server.expect(requestTo(endpoint)).andRespond(withSuccess(body(proposals), MediaType.APPLICATION_JSON))

        val answer = client.complete(prompt)

        assertThat(answer.attempts).isEqualTo(2)
        assertThat(RuleGeneralisationParser.parse(answer.content, setOf("r1", "r2")).proposals).hasSize(1)
    }

    @Test
    fun `two timeouts fail the call`() {
        val (client, server) = client()
        server.expect(times(2), requestTo(endpoint)).andRespond(withException(HttpTimeoutException("timed out")))

        assertThatThrownBy { client.complete(prompt) }
            .isInstanceOfSatisfying(SuggestionModelException::class.java) { assertThat(it.attempts).isEqualTo(2) }
    }

    @Test
    fun `an unreadable body fails the call, and prose without an array is an unreadable answer`() {
        val (client, server) = client()
        server.expect(times(1), requestTo(endpoint)).andRespond(withSuccess("<html>gateway</html>", MediaType.TEXT_HTML))
        assertThatThrownBy { client.complete(prompt) }.isInstanceOf(SuggestionModelException::class.java)

        val (prose, proseServer) = client()
        proseServer.expect(requestTo(endpoint)).andRespond(withSuccess(body("No groups found."), MediaType.APPLICATION_JSON))
        assertThat(RuleGeneralisationParser.parse(prose.complete(prompt).content, setOf("r1", "r2")).readable).isFalse()
    }
}
