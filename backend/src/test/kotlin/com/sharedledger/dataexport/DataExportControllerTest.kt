package com.sharedledger.dataexport

import com.sharedledger.IntegrationTestBase
import com.sharedledger.household.Household
import com.sharedledger.household.HouseholdRepository
import com.sharedledger.identity.user.User
import com.sharedledger.identity.user.UserRepository
import com.sharedledger.recurring.Cadence
import com.sharedledger.recurring.RecurringService
import com.sharedledger.recurring.RecurringTemplateRequest
import com.sharedledger.transaction.Direction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.zip.ZipInputStream

class DataExportControllerTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val recurring: RecurringService,
    private val controller: DataExportController,
) : IntegrationTestBase() {

    @Test
    fun `exportAll returns a zip with all dataset CSVs`() {
        val (user, household) = seed()
        recurring.create(
            household.id,
            RecurringTemplateRequest(
                direction = Direction.expense,
                categoryCode = "home.rent",
                amount = BigDecimal("1200.00"),
                description = "Rent",
                cadence = Cadence.monthly,
                dayOfMonth = 1,
                startDate = LocalDate.of(2026, 1, 1),
                active = true,
            ),
            user,
        )

        val response = controller.exportAll(household.id)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType).isEqualTo(MediaType.parseMediaType("application/zip"))
        val disposition = response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION) ?: ""
        assertThat(disposition).contains("attachment").contains("-all.zip\"")

        val body = response.body ?: error("expected zip body")
        val entries = readZipEntries(body)

        // All datasets, all CSVs.
        assertThat(entries.keys).hasSize(6)
        assertThat(entries.keys).allSatisfy { name -> assertThat(name).endsWith(".csv") }
        val byDataset = entries.mapKeys { (k, _) -> datasetOf(k) }
        assertThat(byDataset.keys).containsExactlyInAnyOrder(
            "transactions",
            "recurring-templates",
            "movements",
            "snapshots",
            "loans",
            "loan-payments",
        )

        // Each CSV has a header line.
        byDataset.values.forEach { csv ->
            assertThat(csv.lines().first()).isNotBlank
        }

        // The seeded recurring template lands in the recurring-templates CSV.
        val recurringCsv = byDataset.getValue("recurring-templates")
        assertThat(recurringCsv).contains("home.rent")
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readAllBytes().toString(StandardCharsets.UTF_8)
                zip.closeEntry()
            }
        }
        return result
    }

    /** Maps a filename like `20260526-h-recurring-templates.csv` to its dataset suffix. */
    private fun datasetOf(filename: String): String {
        val stem = filename.removeSuffix(".csv")
        return when {
            stem.endsWith("-recurring-templates") -> "recurring-templates"
            stem.endsWith("-transactions") -> "transactions"
            stem.endsWith("-movements") -> "movements"
            stem.endsWith("-snapshots") -> "snapshots"
            stem.endsWith("-loan-payments") -> "loan-payments"
            stem.endsWith("-loans") -> "loans"
            else -> error("unrecognised filename: $filename")
        }
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "exp${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
