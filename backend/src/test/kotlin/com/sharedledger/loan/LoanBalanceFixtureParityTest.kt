package com.sharedledger.loan

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Verifies the Kotlin calculator against the same fixtures the frontend Vitest test consumes
 * (backend/src/test/resources/loan-balance-fixtures.json), guaranteeing both ports agree.
 */
class LoanBalanceFixtureParityTest {

    private val mapper = ObjectMapper()

    @TestFactory
    fun fixtures(): List<DynamicTest> {
        val stream = javaClass.classLoader.getResourceAsStream("loan-balance-fixtures.json")
            ?: error("fixture not found")
        val root = mapper.readTree(stream)
        return root.map { fx ->
            DynamicTest.dynamicTest(fx["name"].asText()) {
                val loan = toLoan(fx["loan"])
                val payments = fx["payments"].map { toPayment(loan.id, it) }
                val asOf = LocalDate.parse(fx["asOfDate"].asText())
                val out = LoanBalanceCalculator.compute(loan, payments, asOf)
                val exp = fx["expected"]
                assertThat(out.principalRemaining).isEqualByComparingTo(exp["principalRemaining"].asText())
                assertThat(out.accruedInterest).isEqualByComparingTo(exp["accruedInterest"].asText())
                assertThat(out.totalOutstanding).isEqualByComparingTo(exp["totalOutstanding"].asText())
            }
        }
    }

    private fun toLoan(node: JsonNode): Loan = Loan(
        householdId = UUID.randomUUID(),
        borrowerName = "fixture",
        principalAmount = BigDecimal(node["principalAmount"].asText()),
        startDate = LocalDate.parse(node["startDate"].asText()),
        interestType = InterestType.valueOf(node["interestType"].asText()),
        annualInterestRate = node["annualInterestRate"].takeIf { !it.isNull }?.let { BigDecimal(it.asText()) },
        compoundingPeriod = node["compoundingPeriod"].takeIf { !it.isNull }?.let { CompoundingPeriod.valueOf(it.asText()) },
        status = LoanStatus.valueOf(node["status"].asText()),
        closedDate = node["closedDate"].takeIf { !it.isNull }?.let { LocalDate.parse(it.asText()) },
        createdByUserId = UUID.randomUUID(),
        updatedByUserId = UUID.randomUUID(),
    )

    private fun toPayment(loanId: UUID, node: JsonNode): LoanPayment = LoanPayment(
        id = UUID.fromString(node["id"].asText()),
        loanId = loanId,
        paymentDate = LocalDate.parse(node["paymentDate"].asText()),
        amount = BigDecimal(node["amount"].asText()),
        createdByUserId = UUID.randomUUID(),
        updatedByUserId = UUID.randomUUID(),
    )
}
