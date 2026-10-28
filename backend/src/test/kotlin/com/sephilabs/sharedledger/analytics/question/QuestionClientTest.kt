package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.bank.aisuggestion.AiProviderSettings
import com.sephilabs.sharedledger.bank.aisuggestion.CatalogueEntry
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
import java.time.LocalDate

/** The interpreter over the real OpenAI-compatible client, with the provider simulated as a server. */
class QuestionClientTest {

    private val settings = AiProviderSettings.Configured("https://ai.test/v1", "secret-key", "test-model", timeoutMs = 1000)
    private val endpoint = "https://ai.test/v1/chat/completions"
    private val catalogue = listOf(
        CatalogueEntry("outings.restaurants", "Restaurants", "outings", Direction.expense, essential = false),
    )
    private val today = LocalDate.of(2026, 10, 28)

    private fun interpreter(): Pair<QuestionInterpreter, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return QuestionInterpreter(OpenAiCompatibleChatClient(settings, builder, "1.2.3")) to server
    }

    private fun completion(content: String) = JsonMapper.builder().build().writeValueAsString(
        mapOf(
            "choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to content))),
            "usage" to mapOf("prompt_tokens" to 900, "completion_tokens" to 40),
        ),
    )

    private val valid = """{"filters":{"direction":"expense","categories":[{"type":"category","code":"outings.restaurants"}],
        "range":{"preset":"ytd"},"isRefund":false},"unsupported":[],"confidence":0.9}"""

    private fun ask(interpreter: QuestionInterpreter) =
        interpreter.interpret("How much on restaurants this year vs last?", QuestionLanguage.en, catalogue, today)

    @Test
    fun `one plain call with the question as the user message comes back understood`() {
        val (interpreter, server) = interpreter()
        server.expect(times(1), requestTo(endpoint))
            .andExpect(jsonPath("$.temperature").value(0))
            .andExpect(jsonPath("$.stream").value(false))
            .andExpect(jsonPath("$.tools").doesNotExist())
            .andExpect(jsonPath("$.response_format").doesNotExist())
            .andExpect(jsonPath("$.messages[1].content").value("How much on restaurants this year vs last?"))
            .andRespond(withSuccess(completion(valid), MediaType.APPLICATION_JSON))

        val outcome = ask(interpreter)

        server.verify()
        assertThat(outcome.interpretation).isInstanceOf(Interpretation.Understood::class.java)
        assertThat(outcome.answer.inputTokens).isEqualTo(900)
    }

    @Test
    fun `a 5xx is retried once and the retry's answer is used`() {
        val (interpreter, server) = interpreter()
        server.expect(requestTo(endpoint)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("busy"))
        server.expect(requestTo(endpoint)).andRespond(withSuccess(completion(valid), MediaType.APPLICATION_JSON))

        val outcome = ask(interpreter)

        server.verify()
        assertThat(outcome.interpretation).isInstanceOf(Interpretation.Understood::class.java)
        assertThat(outcome.answer.attempts).isEqualTo(2)
    }

    @Test
    fun `two timeouts are a provider failure, not a misunderstanding`() {
        val (interpreter, server) = interpreter()
        server.expect(times(2), requestTo(endpoint)).andRespond(withException(HttpTimeoutException("request timed out")))

        assertThatThrownBy { ask(interpreter) }.isInstanceOf(SuggestionModelException::class.java)
        server.verify()
    }

    @Test
    fun `an unparseable answer is not understood`() {
        val (interpreter, server) = interpreter()
        server.expect(times(1), requestTo(endpoint))
            .andRespond(withSuccess(completion("You spent 420 euros on restaurants."), MediaType.APPLICATION_JSON))

        assertThat(ask(interpreter).interpretation).isEqualTo(Interpretation.NotUnderstood(QuestionRejection.unreadable))
    }
}
