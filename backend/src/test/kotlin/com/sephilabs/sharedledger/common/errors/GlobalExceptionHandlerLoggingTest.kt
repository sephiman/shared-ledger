package com.sephilabs.sharedledger.common.errors

import com.sephilabs.sharedledger.i18n.Messages
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.support.StaticMessageSource

/** A real 500 must leave its cause in the log; the client only ever sees INTERNAL_ERROR. */
@ExtendWith(OutputCaptureExtension::class)
class GlobalExceptionHandlerLoggingTest {

    private val handler = GlobalExceptionHandler(Messages(StaticMessageSource()))

    @Test
    fun `logs an unexpected exception with its stack trace before answering 500`(output: CapturedOutput) {
        val response = handler.handleGeneric(IllegalStateException("ledger exploded"))

        assertThat(response.statusCode.value()).isEqualTo(500)
        assertThat(response.body?.code).isEqualTo("INTERNAL_ERROR")
        assertThat(output).contains("ledger exploded")
        assertThat(output).contains("GlobalExceptionHandlerLoggingTest")
    }
}
