package com.sephilabs.sharedledger.bank.aisuggestion

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.util.Properties
import java.util.UUID

/** The client exactly as the app wires it, against a real socket: what MockRestServiceServer cannot see. */
class AiConfigWireTest {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val received = mutableListOf<Map<String, String?>>()

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `requests carry a Content-Length instead of a chunked body, and our own User-Agent`() {
        server.createContext("/v1/chat/completions") { exchange ->
            received += mapOf(
                "content-length" to exchange.requestHeaders.getFirst("Content-Length"),
                "transfer-encoding" to exchange.requestHeaders.getFirst("Transfer-Encoding"),
                "user-agent" to exchange.requestHeaders.getFirst("User-Agent"),
            )
            exchange.requestBody.readAllBytes()
            val body = """{"choices":[{"message":{"content":"[]"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val settings = AiProviderSettings.Configured(
            baseUrl = "http://127.0.0.1:${server.address.port}/v1",
            apiKey = "k",
            model = "m",
            timeoutMs = 5000,
        )
        val buildInfo = StaticListableBeanFactory(
            mapOf("buildProperties" to BuildProperties(Properties().apply { setProperty("version", "9.9.9") })),
        ).getBeanProvider(BuildProperties::class.java)

        AiConfig().suggestionModel(settings, RestClient.builder(), buildInfo)
            .complete(ChatPrompt("system", "user", UUID.randomUUID()))

        val headers = received.single()
        assertThat(headers["content-length"]?.toInt()).isPositive()
        assertThat(headers["transfer-encoding"]).isNull()
        assertThat(headers["user-agent"]).isEqualTo("shared-ledger/9.9.9")
    }
}
