package com.sephilabs.sharedledger.recurring

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.LocalDate

class RecurringCsvTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: RecurringService,
    private val importer: RecurringImportService,
    private val repo: RecurringTemplateRepository,
) : IntegrationTestBase() {

    // ---------------- Export ----------------

    @Test
    fun `export with no templates returns header line only`() {
        val (_, household) = seed()

        val csv = service.exportCsv(household.id)

        assertThat(csv.lines().filter { it.isNotBlank() }).hasSize(1)
        assertThat(csv).startsWith(EXPECTED_HEADER)
    }

    @Test
    fun `export emits every cadence-conditional column populated for each row`() {
        val (user, household) = seed()
        service.create(household.id, monthlyRent(amount = "1200.00"), user)
        service.create(household.id, weeklyGroceries(amount = "60.00"), user)
        service.create(household.id, yearlyInsurance(amount = "350.00"), user)

        val csv = service.exportCsv(household.id)

        val lines = csv.lines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(4) // header + 3 rows
        val monthly = lines.first { it.contains("home.rent") }
        val weekly = lines.first { it.contains(";groceries.groceries;") }
        val yearly = lines.first { it.contains("home.insurance_fees") }

        // monthly: day_of_month set, others empty
        assertThat(monthly).contains(";monthly;;1;;;") // dow empty, dom=1, moy empty, domy empty
        // weekly: day_of_week set, others empty
        assertThat(weekly).contains(";weekly;3;;;;")
        // yearly: month_of_year + day_of_month_yearly set, others empty
        assertThat(yearly).contains(";yearly;;;6;15;")
        // active column comes last
        assertThat(monthly).endsWith(";true\r\n".trim())
    }

    // ---------------- Import: happy path & round-trip ----------------

    @Test
    fun `import happy path creates templates with all fields persisted`() {
        val (user, household) = seed()

        val csv = """
            $EXPECTED_HEADER
            expense;home.rent;1200,00;Rent;monthly;;1;;;2026-01-01;;true
            income;income.salary;3200,00;Payroll;monthly;;25;;;2026-01-25;;true
            expense;outings.subscriptions;45,00;Netflix;weekly;3;;;;2026-02-01;2027-02-01;false
        """.trimIndent()

        val result = importer.execute(household.id, csv.bytes(), user)

        assertThat(result.inserted).isEqualTo(3)
        assertThat(result.skipped).isEqualTo(0)
        val all = repo.findAllByHouseholdId(household.id)
        assertThat(all).hasSize(3)
        val rent = all.first { it.categoryCode == "home.rent" }
        assertThat(rent.direction).isEqualTo(Direction.expense)
        assertThat(rent.amount).isEqualByComparingTo("1200.00")
        assertThat(rent.cadence).isEqualTo(Cadence.monthly)
        assertThat(rent.dayOfMonth).isEqualTo(1)
        assertThat(rent.active).isTrue
        val netflix = all.first { it.description == "Netflix" }
        assertThat(netflix.cadence).isEqualTo(Cadence.weekly)
        assertThat(netflix.dayOfWeek).isEqualTo(3)
        assertThat(netflix.endDate).isEqualTo(LocalDate.of(2027, 2, 1))
        assertThat(netflix.active).isFalse
    }

    @Test
    fun `re-importing an export is a no-op`() {
        val (user, household) = seed()
        service.create(household.id, monthlyRent(), user)
        service.create(household.id, weeklyGroceries(), user)
        val exported = service.exportCsv(household.id)

        val result = importer.execute(household.id, exported.bytes(), user)

        assertThat(result.inserted).isEqualTo(0)
        assertThat(result.skipped).isEqualTo(2)
        assertThat(repo.findAllByHouseholdId(household.id)).hasSize(2)
    }

    @Test
    fun `in-file duplicates get description suffixes and all are inserted`() {
        val (user, household) = seed()
        val csv = """
            $EXPECTED_HEADER
            expense;home.rent;1200,00;Rent;monthly;;1;;;2026-01-01;;true
            expense;home.rent;1200,00;Rent;monthly;;1;;;2026-01-01;;true
            expense;home.rent;1200,00;Rent;monthly;;1;;;2026-01-01;;true
        """.trimIndent()

        val result = importer.execute(household.id, csv.bytes(), user)

        assertThat(result.inserted).isEqualTo(3)
        assertThat(result.adjustedCount).isEqualTo(2)
        val descriptions = repo.findAllByHouseholdId(household.id).mapNotNull { it.description }.sorted()
        assertThat(descriptions).containsExactly("Rent", "Rent (2)", "Rent (3)")
    }

    @Test
    fun `preview does not write to the database`() {
        val (user, household) = seed()
        val csv = """
            $EXPECTED_HEADER
            expense;home.rent;1200,00;Rent;monthly;;1;;;2026-01-01;;true
        """.trimIndent()

        val preview = importer.preview(household.id, csv.bytes())

        assertThat(preview.wouldInsert).isEqualTo(1)
        assertThat(preview.errorCount).isEqualTo(0)
        assertThat(repo.findAllByHouseholdId(household.id)).isEmpty()
        // After-the-fact execute still inserts
        val result = importer.execute(household.id, csv.bytes(), user)
        assertThat(result.inserted).isEqualTo(1)
    }

    // ---------------- Import: validation ----------------

    @Test
    fun `execute aborts when any row has validation errors`() {
        val (user, household) = seed()
        val csv = """
            $EXPECTED_HEADER
            expense;home.rent;1200,00;Rent;monthly;;1;;;2026-01-01;;true
            expense;home.rent;abc;Bad;monthly;;1;;;2026-01-01;;true
        """.trimIndent()

        assertThatThrownBy { importer.execute(household.id, csv.bytes(), user) }
            .isInstanceOfSatisfying(AppException::class.java) { e ->
                assertThat(e.code).isEqualTo("IMPORT_VALIDATION_FAILED")
            }
        assertThat(repo.findAllByHouseholdId(household.id)).isEmpty()
    }

    @Test
    fun `preview reports header errors when columns are missing or extra`() {
        val (_, household) = seed()
        val csv = """
            direction;category_code;amount;active
            expense;home.rent;1200,00;true
        """.trimIndent()

        val preview = importer.preview(household.id, csv.bytes())

        assertThat(preview.errors).anySatisfy { e -> assertThat(e.code).isEqualTo("IMPORT_HEADER_INVALID") }
    }

    @Test
    fun `preview reports per-field errors for bad date, amount, cadence, day ranges, end before start, active flag`() {
        val (_, household) = seed()
        val csv = """
            $EXPECTED_HEADER
            expense;home.rent;1200,00;Bad date;monthly;;1;;;not-a-date;;true
            expense;home.rent;abc;Bad amount;monthly;;1;;;2026-01-01;;true
            expense;home.rent;10,00;Bad cadence;hourly;;1;;;2026-01-01;;true
            expense;home.rent;10,00;Bad dow;weekly;9;;;;2026-01-01;;true
            expense;home.rent;10,00;End before start;monthly;;1;;;2026-06-01;2026-05-01;true
            expense;home.rent;10,00;Bad active;monthly;;1;;;2026-01-01;;maybe
        """.trimIndent()

        val preview = importer.preview(household.id, csv.bytes())

        val codes = preview.errors.map { it.code }.toSet()
        assertThat(codes).contains(
            "IMPORT_DATE_INVALID",
            "IMPORT_AMOUNT_INVALID",
            "IMPORT_CADENCE_INVALID",
            "IMPORT_DAY_OF_WEEK_INVALID",
            "IMPORT_END_BEFORE_START",
            "IMPORT_ACTIVE_INVALID",
        )
        assertThat(preview.wouldInsert).isEqualTo(0)
    }

    @Test
    fun `preview rejects rows where cadence-conditional fields are wrong`() {
        val (_, household) = seed()
        val csv = """
            $EXPECTED_HEADER
            expense;home.rent;1200,00;monthly missing day_of_month;monthly;;;;;2026-01-01;;true
            expense;home.rent;1200,00;weekly has day_of_month;weekly;3;5;;;2026-01-01;;true
        """.trimIndent()

        val preview = importer.preview(household.id, csv.bytes())

        val fieldsErrors = preview.errors.filter { it.code == "IMPORT_CADENCE_FIELDS_INVALID" }
        assertThat(fieldsErrors).hasSize(2)
        assertThat(preview.wouldInsert).isEqualTo(0)
    }

    @Test
    fun `preview rejects unknown category and direction mismatch`() {
        val (_, household) = seed()
        val csv = """
            $EXPECTED_HEADER
            expense;does.not.exist;10,00;Unknown category;monthly;;1;;;2026-01-01;;true
            expense;income.salary;10,00;Wrong direction;monthly;;1;;;2026-01-01;;true
        """.trimIndent()

        val preview = importer.preview(household.id, csv.bytes())

        val codes = preview.errors.map { it.code }.toSet()
        assertThat(codes).contains("IMPORT_CATEGORY_UNKNOWN", "IMPORT_CATEGORY_DIRECTION_MISMATCH")
    }

    // ---------------- Helpers ----------------

    private fun monthlyRent(amount: String = "1200.00") = RecurringTemplateRequest(
        direction = Direction.expense,
        categoryCode = "home.rent",
        amount = BigDecimal(amount),
        description = "Rent",
        cadence = Cadence.monthly,
        dayOfMonth = 1,
        startDate = LocalDate.of(2026, 1, 1),
        active = true,
    )

    private fun weeklyGroceries(amount: String = "60.00") = RecurringTemplateRequest(
        direction = Direction.expense,
        categoryCode = "groceries.groceries",
        amount = BigDecimal(amount),
        description = "Groceries",
        cadence = Cadence.weekly,
        dayOfWeek = 3,
        startDate = LocalDate.of(2026, 1, 1),
        active = true,
    )

    private fun yearlyInsurance(amount: String = "350.00") = RecurringTemplateRequest(
        direction = Direction.expense,
        categoryCode = "home.insurance_fees",
        amount = BigDecimal(amount),
        description = "Insurance",
        cadence = Cadence.yearly,
        monthOfYear = 6,
        dayOfMonthYearly = 15,
        startDate = LocalDate.of(2026, 1, 1),
        active = true,
    )

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "rec${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }

    private fun String.bytes() = ByteArrayInputStream(toByteArray(StandardCharsets.UTF_8))

    companion object {
        private const val EXPECTED_HEADER =
            "direction;category_code;amount;description;cadence;day_of_week;day_of_month;month_of_year;day_of_month_yearly;start_date;end_date;active"
    }
}
