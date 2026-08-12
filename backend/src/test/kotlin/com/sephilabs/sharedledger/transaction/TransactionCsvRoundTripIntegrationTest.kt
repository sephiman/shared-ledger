package com.sephilabs.sharedledger.transaction

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class TransactionCsvRoundTripIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: TransactionService,
    private val importer: TransactionImportService,
) : IntegrationTestBase() {

    @Test
    fun `exported refunds re-import into a fresh household with their link intact`() {
        val (user, household) = seed()
        val original = service.create(household.id, expense(day = 5, amount = "80.00", description = "Jacket"), user)
        service.create(household.id, refund(day = 12, amount = "-30.00", originalId = original.id, description = "Returned"), user)

        val csv = service.exportCsv(criteria(household.id))
        assertThat(csv.lines().first()).endsWith("is_refund;refund_of_key")
        // The refund names its original by natural key, since ids are not exported.
        assertThat(csv).contains("true;2025-03-05|expense|groceries.groceries|80.00|Jacket")

        val (otherUser, otherHousehold) = seed()
        val result = importer.execute(otherHousehold.id, csv.byteInputStream(), otherUser)

        assertThat(result.inserted).isEqualTo(2)
        assertThat(result.droppedRefundLinkCount).isZero()
        val imported = service.search(criteria(otherHousehold.id)).items
        val importedOriginal = imported.first { !it.isRefund }
        val importedRefund = imported.first { it.isRefund }
        assertThat(importedRefund.amount).isEqualByComparingTo("-30.00")
        assertThat(importedRefund.refundOfTransactionId).isEqualTo(importedOriginal.id)
        assertThat(importedOriginal.refundedTotal).isEqualByComparingTo("-30.00")

        // And the export is idempotent: importing it again changes nothing.
        val again = importer.execute(otherHousehold.id, csv.byteInputStream(), otherUser)
        assertThat(again.inserted).isZero()
        assertThat(again.skipped).isEqualTo(2)
    }

    @Test
    fun `an unlinked refund round-trips, and a file written before refunds existed still imports`() {
        val (user, household) = seed()
        service.create(household.id, refund(day = 9, amount = "-12.00", originalId = null, description = "Cash back"), user)

        val (otherUser, otherHousehold) = seed()
        importer.execute(otherHousehold.id, service.exportCsv(criteria(household.id)).byteInputStream(), otherUser)
        val imported = service.search(criteria(otherHousehold.id)).items.single()
        assertThat(imported.isRefund).isTrue()
        assertThat(imported.refundOfTransactionId).isNull()

        // The seven-column header predates refunds; every row in such a file is an ordinary transaction.
        val (legacyUser, legacyHousehold) = seed()
        val legacy = "date;direction;category_code;amount;description;created_at;updated_at\r\n" +
            "2025-03-04;expense;groceries.groceries;10,00;Legacy;;\r\n"
        val result = importer.execute(legacyHousehold.id, legacy.byteInputStream(), legacyUser)
        assertThat(result.inserted).isEqualTo(1)
        assertThat(service.search(criteria(legacyHousehold.id)).items.single().isRefund).isFalse()
    }

    @Test
    fun `a refund pointing at an original in the same file links to it whatever the row order`() {
        val (user, household) = seed()
        // The refund comes first: originals are inserted before refunds so the link can still resolve.
        // Note the key carries a dot-decimal amount — it is an identifier, not a displayed number.
        val csv = header() +
            "2025-03-12;expense;groceries.groceries;-30,00;Returned;;;true;2025-03-05|expense|groceries.groceries|80.00|Jacket\r\n" +
            "2025-03-05;expense;groceries.groceries;80,00;Jacket;;;false;\r\n"

        val result = importer.execute(household.id, csv.byteInputStream(), user)

        assertThat(result.inserted).isEqualTo(2)
        assertThat(result.droppedRefundLinkCount).isZero()
        val items = service.search(criteria(household.id)).items
        assertThat(items.first { it.isRefund }.refundOfTransactionId)
            .isEqualTo(items.first { !it.isRefund }.id)
    }

    @Test
    fun `a refund whose original is nowhere to be found imports unlinked and says so`() {
        val (user, household) = seed()
        val csv = header() +
            "2025-03-12;expense;groceries.groceries;-30,00;Returned;;;true;2025-01-02|expense|groceries.groceries|80,00|Vanished\r\n"

        val preview = importer.preview(household.id, csv.byteInputStream())
        assertThat(preview.droppedRefundLinkCount).isEqualTo(1)
        assertThat(preview.errorCount).isZero()

        val result = importer.execute(household.id, csv.byteInputStream(), user)

        // Never a row error: a missing original is not worth failing the whole file over.
        assertThat(result.inserted).isEqualTo(1)
        assertThat(result.droppedRefundLinkCount).isEqualTo(1)
        val imported = service.search(criteria(household.id)).items.single()
        assertThat(imported.isRefund).isTrue()
        assertThat(imported.refundOfTransactionId).isNull()
    }

    @Test
    fun `an original outside the file's date window is still found`() {
        val (user, household) = seed()
        val original = service.create(household.id, expense(day = 5, amount = "80.00", description = "Jacket"), user)
        // Only the refund is in the file, months after the purchase it nets.
        val csv = header() +
            "2025-09-12;expense;groceries.groceries;-30,00;Returned;;;true;2025-03-05|expense|groceries.groceries|80.00|Jacket\r\n"

        importer.execute(household.id, csv.byteInputStream(), user)

        val refundRow = service.search(criteria(household.id)).items.first { it.isRefund }
        assertThat(refundRow.refundOfTransactionId).isEqualTo(original.id)
    }

    @Test
    fun `import refuses a sign, flag or direction that contradicts the refund column`() {
        val (user, household) = seed()

        // A refund has to be negative...
        assertThatThrownBy {
            importer.execute(
                household.id,
                (header() + "2025-03-12;expense;groceries.groceries;30,00;Positive refund;;;true;\r\n").byteInputStream(),
                user,
            )
        }.isInstanceOf(AppException::class.java).hasMessageContaining("IMPORT_VALIDATION_FAILED")

        // ...an ordinary row positive...
        val negativePlain = importer.preview(
            household.id,
            (header() + "2025-03-12;expense;groceries.groceries;-30,00;Negative plain;;;false;\r\n").byteInputStream(),
        )
        assertThat(negativePlain.errors.map { it.code }).contains("IMPORT_AMOUNT_INVALID")

        // ...the flag itself a boolean, a link only on a refund, and a refund an expense.
        val garbage = importer.preview(
            household.id,
            (header() + "2025-03-12;expense;groceries.groceries;-30,00;Bad flag;;;yes;\r\n").byteInputStream(),
        )
        assertThat(garbage.errors.map { it.code }).contains("IMPORT_REFUND_FLAG_INVALID")

        val strayLink = importer.preview(
            household.id,
            (header() + "2025-03-12;expense;groceries.groceries;30,00;Stray;;;false;2025-03-05|expense|x|1,00|y\r\n").byteInputStream(),
        )
        assertThat(strayLink.errors.map { it.code }).contains("IMPORT_REFUND_LINK_INVALID")

        val incomeRefund = importer.preview(
            household.id,
            (header() + "2025-03-12;income;income.salary;-30,00;Income refund;;;true;\r\n").byteInputStream(),
        )
        assertThat(incomeRefund.errors.map { it.code }).contains("IMPORT_REFUND_DIRECTION_INVALID")

        assertThat(service.search(criteria(household.id)).items).isEmpty()
    }

    @Test
    fun `a header mixing the old and new shapes is refused`() {
        val (_, household) = seed()
        val halfNew = "date;direction;category_code;amount;description;created_at;updated_at;is_refund\r\n"
        val preview = importer.preview(household.id, halfNew.byteInputStream())
        assertThat(preview.errors.single().code).isEqualTo("IMPORT_HEADER_INVALID")
    }

    private fun header() =
        "date;direction;category_code;amount;description;created_at;updated_at;is_refund;refund_of_key\r\n"

    private fun criteria(householdId: UUID) = TransactionSearchCriteria(
        householdId = householdId,
        from = null,
        to = null,
        direction = null,
        categoryCode = null,
        categoryGroup = null,
        page = 0,
        size = 100,
        sort = "date_asc",
    )

    private fun expense(day: Int, amount: String, description: String?) = TransactionRequest(
        occurrenceDate = LocalDate.of(2025, 3, day),
        direction = Direction.expense,
        categoryCode = "groceries.groceries",
        amount = BigDecimal(amount),
        description = description,
    )

    private fun refund(day: Int, amount: String, originalId: UUID?, description: String?) = TransactionRequest(
        occurrenceDate = LocalDate.of(2025, 3, day),
        direction = Direction.expense,
        categoryCode = "groceries.groceries",
        amount = BigDecimal(amount),
        description = description,
        isRefund = true,
        refundOfTransactionId = originalId,
    )

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "csv${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
