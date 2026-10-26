package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.bank.BankConnection
import com.sephilabs.sharedledger.bank.BankConnectionAccount
import com.sephilabs.sharedledger.bank.BankConnectionAccountRepository
import com.sephilabs.sharedledger.bank.BankConnectionRepository
import com.sephilabs.sharedledger.bank.MovementStatus
import com.sephilabs.sharedledger.bank.PendingMovement
import com.sephilabs.sharedledger.bank.PendingMovementRepository
import com.sephilabs.sharedledger.bank.SuggestionSource
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.transaction.Direction
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Seeds a household with one bank account and pending items directly, without the bank connector. */
@Component
class AiSuggestionFixtures(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val connections: BankConnectionRepository,
    private val accounts: BankConnectionAccountRepository,
    private val pending: PendingMovementRepository,
    private val aiSettings: AiSuggestionSettingsRepository,
) {

    data class Seeded(val user: User, val household: Household, val connectionId: UUID, val accountId: UUID)

    fun household(aiEnabled: Boolean = true): Seeded {
        val user = users.save(User(email = "ai${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "AI", currency = "EUR", defaultLocale = "en"))
        members.save(HouseholdMember(HouseholdMemberId(household.id, user.id), HouseholdRole.owner))
        val connection = connections.save(BankConnection(householdId = household.id, aspspName = "ING", aspspCountry = "NL"))
        val account = accounts.save(
            BankConnectionAccount(connectionId = connection.id, accountUid = "acc-${System.nanoTime()}", ibanMasked = "••••4567"),
        )
        if (aiEnabled) aiSettings.save(AiSuggestionSettings(householdId = household.id, enabled = true))
        return Seeded(user, household, connection.id, account.id)
    }

    fun item(
        seeded: Seeded,
        counterparty: String?,
        direction: Direction = Direction.expense,
        description: String? = "Card payment",
        amount: String = "12.34",
        status: MovementStatus = MovementStatus.pending,
        suggested: String? = null,
        source: SuggestionSource? = null,
        confidence: String? = null,
        bookingDate: LocalDate = LocalDate.of(2026, 9, 14),
    ): PendingMovement = pending.save(
        PendingMovement(
            householdId = seeded.household.id,
            connectionId = seeded.connectionId,
            accountId = seeded.accountId,
            bankMovementId = "bm-${UUID.randomUUID()}",
            bookingDate = bookingDate,
            direction = direction,
            amount = BigDecimal(amount),
            counterparty = counterparty,
            description = description,
            status = status,
            suggestedCategoryCode = suggested,
            suggestionSource = source,
            suggestionConfidence = confidence?.let(::BigDecimal),
        ),
    )

    fun reload(item: PendingMovement): PendingMovement = pending.findById(item.id).orElseThrow()
}
