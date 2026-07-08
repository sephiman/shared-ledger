package com.sephilabs.sharedledger.networth.amortization

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.NamedValueResolver
import com.sephilabs.sharedledger.networth.liability.Liability
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/** Amortizable liability end-to-end: schedule balance feeds the resolver, and the monthly job is idempotent. */
class AmortizationIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val liabilities: LiabilityRepository,
    private val parts: AmortizationPartRepository,
    private val entries: AmortizationEntryRepository,
    private val resolver: NamedValueResolver,
    private val schedule: AmortizationScheduleService,
    private val materializer: AmortizationMaterializer,
    private val snapshotService: com.sephilabs.sharedledger.networth.snapshot.SnapshotService,
) : IntegrationTestBase() {

    @Test
    fun `snapshot named-values auto-fill reads the amortizable balance, not zero`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val liability = liabilities.save(
            Liability(householdId = household.id, name = "Hipoteca", amortizable = true, chargeDay = 1, createdByUserId = user.id, updatedByUserId = user.id),
        )
        parts.save(AmortizationPart(
            liabilityId = liability.id, method = AmortizationMethod.french, originalPrincipal = BigDecimal("254793.00"),
            annualRate = BigDecimal("1.7400"), termMonths = 300, startDate = today,
            createdByUserId = user.id, updatedByUserId = user.id,
        ))
        val values = snapshotService.namedValuesAt(household.id, today)
        val filled = values.liabilities[liability.id.toString()]!!
        assertThat(BigDecimal(filled)).isEqualByComparingTo("254793.00")
        assertThat(BigDecimal(filled)).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `resolver reads the schedule balance and the monthly job is idempotent`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val liability = liabilities.save(
            Liability(
                householdId = household.id,
                name = "Mortgage",
                amortizable = true,
                chargeDay = today.dayOfMonth,
                createdByUserId = user.id,
                updatedByUserId = user.id,
            ),
        )
        parts.save(
            AmortizationPart(
                liabilityId = liability.id,
                method = AmortizationMethod.french,
                originalPrincipal = BigDecimal("100000.00"),
                annualRate = BigDecimal("6.0000"),
                termMonths = 360,
                startDate = today.minusMonths(2),
                createdByUserId = user.id,
                updatedByUserId = user.id,
            ),
        )

        // Resolver returns the schedule-computed balance (below the original principal, above zero).
        val balance = resolver.liabilityBalanceAt(liability.id, today)!!
        assertThat(balance).isLessThan(BigDecimal("100000.00"))
        assertThat(balance).isGreaterThan(BigDecimal.ZERO)

        // First run persists the instalment(s) due; a second run adds nothing (idempotent).
        val created = materializer.runForAll(today)
        assertThat(created).isGreaterThanOrEqualTo(1)
        val again = materializer.runForAll(today)
        assertThat(again).isEqualTo(0)
        val partId = parts.findAllByLiabilityIdOrderByStartDateAscCreatedAtAsc(liability.id).single().id
        assertThat(entries.existsByPartIdAndChargeDate(partId, today)).isTrue()
    }

    @Test
    fun `schedule total balance is the sum of parts' outstanding principals on the balance date, never zero`() {
        val (user, household) = seed()
        val today = LocalDate.now()
        val liability = liabilities.save(
            Liability(householdId = household.id, name = "Mortgage", amortizable = true, chargeDay = 1, createdByUserId = user.id, updatedByUserId = user.id),
        )
        // Two parts with balance date = today (schedule projects forward from here).
        parts.save(AmortizationPart(liabilityId = liability.id, method = AmortizationMethod.french, originalPrincipal = BigDecimal("255765.21"), annualRate = BigDecimal("3.0000"), termMonths = 300, startDate = today, createdByUserId = user.id, updatedByUserId = user.id))
        parts.save(AmortizationPart(liabilityId = liability.id, method = AmortizationMethod.french, originalPrincipal = BigDecimal("46791.81"), annualRate = BigDecimal("2.5000"), termMonths = 300, startDate = today, createdByUserId = user.id, updatedByUserId = user.id))

        val dto = schedule.schedule(household.id, liability.id, today)

        // Immediately available, summed from the parts, not 0.
        assertThat(dto.currentBalance).isEqualByComparingTo("302557.02")
        assertThat(dto.parts).allSatisfy { assertThat(it.currentBalance).isGreaterThan(BigDecimal.ZERO) }
        assertThat(dto.monthlyInstalment).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `simulate a prepayment reports interest saved and an earlier payoff`() {
        val (user, household) = seed()
        val today = LocalDate.of(2026, 1, 1)
        val liability = liabilities.save(
            Liability(householdId = household.id, name = "Loan", amortizable = true, chargeDay = 1, createdByUserId = user.id, updatedByUserId = user.id),
        )
        val part = parts.save(
            AmortizationPart(
                liabilityId = liability.id,
                method = AmortizationMethod.french,
                originalPrincipal = BigDecimal("100000.00"),
                annualRate = BigDecimal("6.0000"),
                termMonths = 360,
                startDate = today,
                createdByUserId = user.id,
                updatedByUserId = user.id,
            ),
        )
        val sim = schedule.simulate(
            household.id, liability.id, part.id,
            SimulationRequest(prepaymentDate = today.plusMonths(2), amount = BigDecimal("20000.00"), mode = PrepaymentMode.reduce_term),
        )
        assertThat(sim.interestSaved).isGreaterThan(BigDecimal.ZERO)
        assertThat(sim.newPayoffDate).isNotNull()
        assertThat(sim.baselinePayoffDate).isNotNull()
        assertThat(sim.newPayoffDate!!).isBefore(sim.baselinePayoffDate!!)
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "am${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
