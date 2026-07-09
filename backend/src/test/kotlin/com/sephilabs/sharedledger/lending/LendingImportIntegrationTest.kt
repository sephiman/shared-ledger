package com.sephilabs.sharedledger.lending

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class LendingImportIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: LendingService,
    private val lendingImport: LendingImportService,
    private val paymentImport: LendingPaymentImportService,
) : IntegrationTestBase() {

    @Test
    fun `lendings round-trip - export then re-import skips all rows`() {
        val (user, household) = seed()
        service.create(
            household.id,
            LendingRequest("Hank ${System.nanoTime()}", BigDecimal("750.00"), LocalDate.of(2024, 5, 1), interestType = InterestType.simple, annualInterestRate = BigDecimal("4.5")),
            user,
        )
        service.create(
            household.id,
            LendingRequest("Iris ${System.nanoTime()}", BigDecimal("3000.00"), LocalDate.of(2023, 1, 15), interestType = InterestType.none),
            user,
        )

        val csv = service.exportLendingsCsv(household.id)
        val preview = lendingImport.preview(household.id, csv.byteInputStream(Charsets.UTF_8))
        assertThat(preview.errorCount).isEqualTo(0)
        assertThat(preview.wouldInsert).isEqualTo(0)
        assertThat(preview.wouldSkip).isEqualTo(2)
    }

    @Test
    fun `payment import rejects unknown borrower`() {
        val (user, household) = seed()
        service.create(
            household.id,
            LendingRequest("Existing ${System.nanoTime()}", BigDecimal("100.00"), LocalDate.of(2025, 1, 1), interestType = InterestType.none),
            user,
        )
        val csv = """
            borrower_name;lending_start_date;payment_date;amount;description
            Stranger;2025-01-01;2025-02-01;50,00;
        """.trimIndent()
        val preview = paymentImport.preview(household.id, csv.byteInputStream(Charsets.UTF_8))
        assertThat(preview.errorCount).isGreaterThan(0)
        assertThat(preview.errors.first().code).isEqualTo("IMPORT_LENDING_UNKNOWN")
    }

    @Test
    fun `payment import detects ambiguous lending when borrower + start_date matches multiple`() {
        val (user, household) = seed()
        val borrowerName = "Twin-${System.nanoTime()}"
        service.create(
            household.id,
            LendingRequest(borrowerName, BigDecimal("100.00"), LocalDate.of(2025, 1, 1), interestType = InterestType.none),
            user,
        )
        service.create(
            household.id,
            LendingRequest(borrowerName, BigDecimal("200.00"), LocalDate.of(2025, 1, 1), interestType = InterestType.none),
            user,
        )
        val csv = """
            borrower_name;lending_start_date;payment_date;amount;description
            $borrowerName;2025-01-01;2025-02-01;50,00;
        """.trimIndent()
        val preview = paymentImport.preview(household.id, csv.byteInputStream(Charsets.UTF_8))
        assertThat(preview.errors.first().code).isEqualTo("IMPORT_LENDING_AMBIGUOUS")
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "li${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
