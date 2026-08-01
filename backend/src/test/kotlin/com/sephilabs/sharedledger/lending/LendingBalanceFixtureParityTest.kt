package com.sephilabs.sharedledger.lending

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Checks the Kotlin calculator against the fixtures the frontend Vitest test consumes
 *  (backend/src/test/resources/lending-balance-fixtures.json), so both ports agree. */
class LendingBalanceFixtureParityTest {

    private val mapper = ObjectMapper()

    @TestFactory
    fun fixtures(): List<DynamicTest> {
        val stream = javaClass.classLoader.getResourceAsStream("lending-balance-fixtures.json")
            ?: error("fixture not found")
        val root = mapper.readTree(stream)
        return root.map { fx ->
            DynamicTest.dynamicTest(fx["name"].asText()) {
                val lending = toLending(fx["lending"])
                val payments = fx["payments"].map { toPayment(lending.id, it) }
                val asOf = LocalDate.parse(fx["asOfDate"].asText())
                val out = LendingBalanceCalculator.compute(lending, payments, asOf)
                val exp = fx["expected"]
                assertThat(out.principalRemaining).isEqualByComparingTo(exp["principalRemaining"].asText())
                assertThat(out.accruedInterest).isEqualByComparingTo(exp["accruedInterest"].asText())
                assertThat(out.totalOutstanding).isEqualByComparingTo(exp["totalOutstanding"].asText())
            }
        }
    }

    private fun toLending(node: JsonNode): Lending = Lending(
        householdId = UUID.randomUUID(),
        borrowerName = "fixture",
        principalAmount = BigDecimal(node["principalAmount"].asText()),
        startDate = LocalDate.parse(node["startDate"].asText()),
        interestType = InterestType.valueOf(node["interestType"].asText()),
        annualInterestRate = node["annualInterestRate"].takeIf { !it.isNull }?.let { BigDecimal(it.asText()) },
        compoundingPeriod = node["compoundingPeriod"].takeIf { !it.isNull }?.let { CompoundingPeriod.valueOf(it.asText()) },
        status = LendingStatus.valueOf(node["status"].asText()),
        closedDate = node["closedDate"].takeIf { !it.isNull }?.let { LocalDate.parse(it.asText()) },
        createdByUserId = UUID.randomUUID(),
        updatedByUserId = UUID.randomUUID(),
    )

    private fun toPayment(lendingId: UUID, node: JsonNode): LendingPayment = LendingPayment(
        id = UUID.fromString(node["id"].asText()),
        lendingId = lendingId,
        paymentDate = LocalDate.parse(node["paymentDate"].asText()),
        amount = BigDecimal(node["amount"].asText()),
        createdByUserId = UUID.randomUUID(),
        updatedByUserId = UUID.randomUUID(),
    )
}
