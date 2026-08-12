package com.sephilabs.sharedledger.transaction

import com.sephilabs.sharedledger.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The wire names the frontend reads, asserted through the very mapper that serves the API. Worth pinning
 *  for [TransactionDto.isRefund] in particular: Jackson derives property names from getters, and an
 *  `is`-prefixed Kotlin boolean ships as "refund" unless it is named explicitly — which the service-level
 *  tests can't catch, since they never serialize anything. */
class TransactionDtoJsonTest @Autowired constructor(
    private val mapper: ObjectMapper,
) : IntegrationTestBase() {

    @Test
    fun `a refund keeps its isRefund flag and its link on the wire`() {
        val original = UUID.randomUUID()
        val json = mapper.readTree(mapper.writeValueAsString(dto(isRefund = true, refundOf = original)))

        assertThat(json.has("isRefund")).isTrue()
        assertThat(json.get("isRefund").asBoolean()).isTrue()
        assertThat(json.has("refund")).isFalse()
        assertThat(json.get("refundOfTransactionId").asString()).isEqualTo(original.toString())
        // Amounts travel as strings so no cent is lost to a double on the way out.
        assertThat(json.get("amount").asString()).isEqualTo("-30.00")
    }

    @Test
    fun `an ordinary transaction still says so`() {
        val json = mapper.readTree(mapper.writeValueAsString(dto(isRefund = false, refundOf = null)))
        assertThat(json.get("isRefund").asBoolean()).isFalse()
        assertThat(json.get("refundOfTransactionId").isNull).isTrue()
    }

    @Test
    fun `a request deserializes the same name the DTO publishes`() {
        val request = mapper.readValue(
            """
            {"occurrenceDate":"2025-03-01","direction":"expense","categoryCode":"groceries.groceries",
             "amount":"-30.00","isRefund":true,"refundOfTransactionId":null}
            """.trimIndent(),
            TransactionRequest::class.java,
        )
        assertThat(request.isRefund).isTrue()
        assertThat(request.amount).isEqualByComparingTo("-30.00")
    }

    private fun dto(isRefund: Boolean, refundOf: UUID?) = TransactionDto(
        id = UUID.randomUUID(),
        occurrenceDate = LocalDate.of(2025, 3, 20),
        direction = Direction.expense,
        categoryCode = "groceries.groceries",
        amount = BigDecimal(if (isRefund) "-30.00" else "30.00"),
        description = "Returned",
        recurringTemplateId = null,
        createdByUserId = UUID.randomUUID(),
        updatedByUserId = UUID.randomUUID(),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        isRefund = isRefund,
        refundOfTransactionId = refundOf,
    )
}
