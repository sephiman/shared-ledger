package com.sephilabs.sharedledger.i18n

import com.sephilabs.sharedledger.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.Locale
import java.util.Properties

/** Backend half of the frontend's guard (fireRegister.test.ts): Spanish says "retirada" for withdrawing money. */
class SpanishWithdrawalWordingTest @Autowired constructor(
    private val messages: Messages,
) : IntegrationTestBase() {

    @Test
    fun `no Spanish backend message uses retiro`() {
        val props = Properties()
        javaClass.getResourceAsStream("/i18n/messages_es.properties")!!.reader(Charsets.UTF_8).use { props.load(it) }
        val offending = props.entries
            .filter { (_, value) -> Regex("""\bretiros?\b""", RegexOption.IGNORE_CASE).containsMatchIn(value.toString()) }
            .map { (key, value) -> "$key=$value" }
        assertThat(offending).isEmpty()
    }

    @Test
    fun `a withdrawal reads Retirada in Spanish notifications`() {
        assertThat(messages.resolve("networth.withdrawal", locale = Locale.forLanguageTag("es"))).isEqualTo("Retirada")
    }
}
