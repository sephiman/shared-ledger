package com.sephilabs.sharedledger.bank.aisuggestion

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.times
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.http.HttpTimeoutException
import java.util.UUID

/** The provider simulated as an OpenAI-compatible server, the way the price clients are tested. */
class OpenAiCompatibleChatClientTest {

    private val settings = AiProviderSettings.Configured(
        baseUrl = "https://ai.test/v1",
        apiKey = "secret-key",
        model = "test-model",
        timeoutMs = 1000,
    )
    private val prompt = ChatPrompt(system = "system text", user = "user text", sessionId = UUID.randomUUID())
    private val endpoint = "https://ai.test/v1/chat/completions"

    private fun client(): Pair<OpenAiCompatibleChatClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return OpenAiCompatibleChatClient(settings, builder, "1.2.3") to server
    }

    private val okBody = """
        {"id":"x","model":"test-model",
         "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"[{\"id\":\"m1\"}]"}}],
         "usage":{"prompt_tokens":4126,"completion_tokens":1797,"completion_tokens_details":{"reasoning_tokens":1500}}}
    """.trimIndent()

    @Test
    fun `sends a plain chat completion with our own identity and reads content and usage`() {
        val (client, server) = client()
        server.expect(requestTo(endpoint))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer secret-key"))
            .andExpect(header("User-Agent", "shared-ledger/1.2.3"))
            .andExpect(header("x-opencode-session", prompt.sessionId.toString()))
            .andExpect(jsonPath("$.model").value("test-model"))
            .andExpect(jsonPath("$.temperature").value(0))
            .andExpect(jsonPath("$.stream").value(false))
            .andExpect(jsonPath("$.messages[0].role").value("system"))
            .andExpect(jsonPath("$.messages[0].content").value("system text"))
            .andExpect(jsonPath("$.messages[1].content").value("user text"))
            .andExpect(jsonPath("$.tools").doesNotExist())
            .andExpect(jsonPath("$.response_format").doesNotExist())
            .andRespond(withSuccess(okBody, MediaType.APPLICATION_JSON))

        val answer = client.complete(prompt)

        server.verify()
        assertThat(answer.content).isEqualTo("""[{"id":"m1"}]""")
        assertThat(answer.inputTokens).isEqualTo(4126)
        assertThat(answer.outputTokens).isEqualTo(1797)
        assertThat(answer.reasoningTokens).isEqualTo(1500)
        assertThat(answer.attempts).isEqualTo(1)
    }

    @Test
    fun `a 5xx is retried once and the second answer is used`() {
        val (client, server) = client()
        server.expect(requestTo(endpoint)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("upstream down"))
        server.expect(requestTo(endpoint)).andRespond(withSuccess(okBody, MediaType.APPLICATION_JSON))

        val answer = client.complete(prompt)

        server.verify()
        assertThat(answer.attempts).isEqualTo(2)
        assertThat(answer.inputTokens).isEqualTo(4126)
    }

    @Test
    fun `two 5xx in a row fail the call after exactly one retry`() {
        val (client, server) = client()
        server.expect(times(2), requestTo(endpoint)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("busy"))

        assertThatThrownBy { client.complete(prompt) }
            .isInstanceOf(SuggestionModelException::class.java)
            .hasMessageContaining("HTTP 503")
            .satisfies({ assertThat((it as SuggestionModelException).attempts).isEqualTo(2) })
        server.verify()
    }

    @Test
    fun `a timeout is retried once`() {
        val (client, server) = client()
        server.expect(requestTo(endpoint)).andRespond(withException(HttpTimeoutException("request timed out")))
        server.expect(requestTo(endpoint)).andRespond(withSuccess(okBody, MediaType.APPLICATION_JSON))

        assertThat(client.complete(prompt).attempts).isEqualTo(2)
        server.verify()
    }

    @Test
    fun `two timeouts fail the call`() {
        val (client, server) = client()
        server.expect(times(2), requestTo(endpoint)).andRespond(withException(HttpTimeoutException("request timed out")))

        assertThatThrownBy { client.complete(prompt) }
            .isInstanceOf(SuggestionModelException::class.java)
            .hasMessageContaining("timeout")
        server.verify()
    }

    @Test
    fun `a 4xx is not retried`() {
        val (client, server) = client()
        server.expect(times(1), requestTo(endpoint))
            .andRespond(withStatus(HttpStatus.GONE).body("""{"type":"ModelDeprecated"}"""))

        assertThatThrownBy { client.complete(prompt) }
            .isInstanceOf(SuggestionModelException::class.java)
            .hasMessageContaining("HTTP 410")
            .hasMessageContaining("ModelDeprecated")
        server.verify()
    }

    @Test
    fun `a 200 that is not JSON fails the call without a retry`() {
        val (client, server) = client()
        server.expect(times(1), requestTo(endpoint)).andRespond(withSuccess("<html>gateway</html>", MediaType.TEXT_HTML))

        assertThatThrownBy { client.complete(prompt) }.isInstanceOf(SuggestionModelException::class.java)
        server.verify()
    }

    @Test
    fun `an answer in prose comes back as content for the parser to reject, with usage still read`() {
        val (client, server) = client()
        server.expect(requestTo(endpoint)).andRespond(
            withSuccess(
                """{"choices":[{"message":{"content":"Sorry, I can't help with that."}}],
                    "usage":{"prompt_tokens":10,"completion_tokens":7}}""",
                MediaType.APPLICATION_JSON,
            ),
        )

        val answer = client.complete(prompt)

        assertThat(answer.content).isEqualTo("Sorry, I can't help with that.")
        assertThat(answer.outputTokens).isEqualTo(7)
        assertThat(answer.reasoningTokens).isNull()
    }
}
