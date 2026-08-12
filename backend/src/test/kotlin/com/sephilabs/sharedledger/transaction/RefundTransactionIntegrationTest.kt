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

class RefundTransactionIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val service: TransactionService,
) : IntegrationTestBase() {

    @Test
    fun `a refund is a negative expense linked to the purchase it nets`() {
        val (user, household) = seed()
        val original = service.create(household.id, expense("80.00", "Jacket"), user)

        val refund = service.create(household.id, refund("-30.00", original.id, "Jacket returned"), user)

        assertThat(refund.isRefund).isTrue()
        assertThat(refund.direction).isEqualTo(Direction.expense)
        assertThat(refund.amount).isEqualByComparingTo("-30.00")
        assertThat(refund.refundOfTransactionId).isEqualTo(original.id)

        // The list resolves both sides: the refund names its original, the original says how much came back.
        val page = service.search(criteria(household.id))
        val refundRow = page.items.first { it.id == refund.id }
        assertThat(refundRow.refundOf?.id).isEqualTo(original.id)
        assertThat(refundRow.refundOf?.description).isEqualTo("Jacket")
        val originalRow = page.items.first { it.id == original.id }
        assertThat(originalRow.refundedTotal).isEqualByComparingTo("-30.00")
        assertThat(originalRow.refundCount).isEqualTo(1)
        assertThat(refundRow.refundedTotal).isNull()
    }

    @Test
    fun `a refund does not need an original`() {
        val (user, household) = seed()
        val refund = service.create(household.id, refund("-12.00", null, "Cash back"), user)

        assertThat(refund.refundOfTransactionId).isNull()
        assertThat(service.search(criteria(household.id)).items.single().refundOf).isNull()
    }

    @Test
    fun `ordinary transactions are positive, refunds negative, and neither is ever zero`() {
        val (user, household) = seed()

        assertThatThrownBy { service.create(household.id, expense("-5.00"), user) }
            .isInstanceOf(AppException::class.java).hasMessageContaining("TX_AMOUNT_INVALID")
        assertThatThrownBy { service.create(household.id, expense("0.00"), user) }
            .isInstanceOf(AppException::class.java).hasMessageContaining("TX_AMOUNT_INVALID")
        assertThatThrownBy { service.create(household.id, refund("5.00", null), user) }
            .isInstanceOf(AppException::class.java).hasMessageContaining("TX_REFUND_AMOUNT_INVALID")
        assertThatThrownBy { service.create(household.id, refund("0.00", null), user) }
            .isInstanceOf(AppException::class.java).hasMessageContaining("TX_REFUND_AMOUNT_INVALID")

        assertThat(service.search(criteria(household.id)).items).isEmpty()
    }

    @Test
    fun `refunding income is out of scope, and a link needs the flag`() {
        val (user, household) = seed()

        // Money going back out is an ordinary expense, so a refund is always an expense itself.
        assertThatThrownBy {
            service.create(
                household.id,
                TransactionRequest(
                    occurrenceDate = LocalDate.of(2025, 3, 4),
                    direction = Direction.income,
                    categoryCode = "income.salary",
                    amount = BigDecimal("-10.00"),
                    isRefund = true,
                ),
                user,
            )
        }.isInstanceOf(AppException::class.java).hasMessageContaining("TX_REFUND_MUST_BE_EXPENSE")

        val original = service.create(household.id, expense("40.00"), user)
        assertThatThrownBy {
            service.create(household.id, expense("10.00").copy(refundOfTransactionId = original.id), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("TX_REFUND_OF_WITHOUT_REFUND")
    }

    @Test
    fun `a refund can only point at a live expense of this household that is not itself a refund`() {
        val (user, household) = seed()
        val (otherUser, otherHousehold) = seed()
        val original = service.create(household.id, expense("50.00"), user)
        val firstRefund = service.create(household.id, refund("-20.00", original.id), user)
        val income = service.create(
            household.id,
            TransactionRequest(
                occurrenceDate = LocalDate.of(2025, 3, 1),
                direction = Direction.income,
                categoryCode = "income.salary",
                amount = BigDecimal("100.00"),
            ),
            user,
        )
        val elsewhere = service.create(otherHousehold.id, expense("10.00"), otherUser)

        assertThatThrownBy { service.create(household.id, refund("-5.00", UUID.randomUUID()), user) }
            .isInstanceOf(AppException::class.java).hasMessageContaining("TX_REFUND_OF_NOT_FOUND")
        assertThatThrownBy { service.create(household.id, refund("-5.00", elsewhere.id), user) }
            .isInstanceOf(AppException::class.java).hasMessageContaining("TX_REFUND_OF_NOT_FOUND")
        assertThatThrownBy { service.create(household.id, refund("-5.00", firstRefund.id), user) }
            .isInstanceOf(AppException::class.java).hasMessageContaining("TX_REFUND_OF_IS_REFUND")
        assertThatThrownBy { service.create(household.id, refund("-5.00", income.id), user) }
            .isInstanceOf(AppException::class.java).hasMessageContaining("TX_REFUND_OF_NOT_EXPENSE")
    }

    @Test
    fun `editing a row into a refund keeps its identity, and refunding an already-refunded purchase is refused`() {
        val (user, household) = seed()
        val original = service.create(household.id, expense("100.00", "Boots"), user)
        // The historical way of recording money back: an income row under reimbursements.
        val legacy = service.create(
            household.id,
            TransactionRequest(
                occurrenceDate = LocalDate.of(2025, 3, 20),
                direction = Direction.income,
                categoryCode = "income.reimbursements",
                amount = BigDecimal("30.00"),
                description = "Boots returned",
            ),
            user,
        )

        val converted = service.update(
            household.id,
            legacy.id,
            refund("-30.00", original.id, "Boots returned").copy(occurrenceDate = LocalDate.of(2025, 3, 20)),
            user,
        )

        // Same row, flipped: an edit, so the id and the audit trail survive.
        assertThat(converted.id).isEqualTo(legacy.id)
        assertThat(converted.createdByUserId).isEqualTo(legacy.createdByUserId)
        assertThat(converted.direction).isEqualTo(Direction.expense)
        assertThat(converted.isRefund).isTrue()
        assertThat(converted.amount).isEqualByComparingTo("-30.00")

        // And back again: toggling the flag off returns it to an ordinary transaction.
        val restored = service.update(
            household.id,
            legacy.id,
            TransactionRequest(
                occurrenceDate = LocalDate.of(2025, 3, 20),
                direction = Direction.income,
                categoryCode = "income.reimbursements",
                amount = BigDecimal("30.00"),
                description = "Boots returned",
            ),
            user,
        )
        assertThat(restored.id).isEqualTo(legacy.id)
        assertThat(restored.isRefund).isFalse()
        assertThat(restored.refundOfTransactionId).isNull()
        assertThat(restored.amount).isEqualByComparingTo("30.00")

        // A purchase that already has refunds can't become one itself — that would be a refund of a refund.
        service.create(household.id, refund("-10.00", original.id), user)
        assertThatThrownBy {
            service.update(household.id, original.id, refund("-100.00", null, "Boots"), user)
        }.isInstanceOf(AppException::class.java).hasMessageContaining("TX_REFUND_TARGET_HAS_REFUNDS")
    }

    @Test
    fun `a refund whose original was deleted keeps its own amount and loses only the link summary`() {
        val (user, household) = seed()
        val original = service.create(household.id, expense("60.00"), user)
        val refund = service.create(household.id, refund("-25.00", original.id), user)

        service.delete(household.id, original.id, user)

        val row = service.search(criteria(household.id)).items.single { it.id == refund.id }
        assertThat(row.isRefund).isTrue()
        assertThat(row.refundOfTransactionId).isEqualTo(original.id)
        assertThat(row.refundOf).isNull()
    }

    @Test
    fun `the refunds-only filter and the description search narrow the list`() {
        val (user, household) = seed()
        service.create(household.id, expense("40.00", "Mercadona"), user)
        val refund = service.create(household.id, refund("-15.00", null, "Mercadona return"), user)

        assertThat(service.search(criteria(household.id, isRefund = true)).items.map { it.id })
            .containsExactly(refund.id)
        assertThat(service.search(criteria(household.id, isRefund = false)).items).hasSize(1)
        assertThat(service.search(criteria(household.id, q = "return")).items.map { it.id })
            .containsExactly(refund.id)
        assertThat(service.search(criteria(household.id, q = "mercadona")).items).hasSize(2)
    }

    private fun criteria(householdId: UUID, isRefund: Boolean? = null, q: String? = null) =
        TransactionSearchCriteria(
            householdId = householdId,
            from = null,
            to = null,
            direction = null,
            categoryCode = null,
            categoryGroup = null,
            page = 0,
            size = 50,
            sort = "date_desc",
            isRefund = isRefund,
            q = q,
        )

    private fun expense(amount: String, description: String? = null) = TransactionRequest(
        occurrenceDate = LocalDate.of(2025, 3, 10),
        direction = Direction.expense,
        categoryCode = "groceries.groceries",
        amount = BigDecimal(amount),
        description = description,
    )

    private fun refund(amount: String, originalId: UUID?, description: String? = null) = TransactionRequest(
        occurrenceDate = LocalDate.of(2025, 3, 25),
        direction = Direction.expense,
        categoryCode = "groceries.groceries",
        amount = BigDecimal(amount),
        description = description,
        isRefund = true,
        refundOfTransactionId = originalId,
    )

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "rf${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
