package com.sephilabs.sharedledger.fire

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.snapshot.Snapshot
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValue
import com.sephilabs.sharedledger.networth.snapshot.SnapshotAssetValueId
import com.sephilabs.sharedledger.networth.snapshot.SnapshotRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

class FireProjectionTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val snapshots: SnapshotRepository,
    private val fireService: FireService,
) : IntegrationTestBase() {

    @Test
    fun `deterministic series matches expected math with zero stddev`() {
        val (household, user) = setupHouseholdWithSnapshot(BigDecimal("100000.00"))

        fireService.update(
            household.id,
            FireSettingsRequest(
                targetAmount = BigDecimal("500000.00"),
                targetYear = 2035,
                monthlyContribution = BigDecimal("1000.00"),
                returnScenarios = listOf(ReturnScenarioInput(BigDecimal("0.0"), BigDecimal("0.0"))),
                qualifyingAssetClasses = listOf("etfs"),
            ),
            user,
        )

        val result = fireService.project(household.id)
        val series = result.scenarios.first().series
        // 0% return, 12k/year contributions, starting 100k, 10 years => 220k.
        assertThat(series.first().value).isEqualByComparingTo("100000.00")
        assertThat(series.last().value).isEqualByComparingTo("220000.00")
    }

    @Test
    fun `monte carlo percentiles are ordered and collapse when stddev is zero`() {
        val (household, user) = setupHouseholdWithSnapshot(BigDecimal("100000.00"))

        fireService.update(
            household.id,
            FireSettingsRequest(
                targetAmount = BigDecimal("1000000.00"),
                targetYear = 2040,
                monthlyContribution = BigDecimal("500.00"),
                returnScenarios = listOf(
                    ReturnScenarioInput(BigDecimal("5.0"), BigDecimal("0.0")),
                    ReturnScenarioInput(BigDecimal("7.0"), BigDecimal("15.0")),
                ),
                qualifyingAssetClasses = listOf("etfs"),
            ),
            user,
        )

        val result = fireService.project(household.id)
        val deterministic = result.scenarios[0]
        val stochastic = result.scenarios[1]

        deterministic.percentiles.forEachIndexed { i, p ->
            assertThat(p.p10).isEqualByComparingTo(p.p25)
            assertThat(p.p25).isEqualByComparingTo(p.p50)
            assertThat(p.p50).isEqualByComparingTo(p.p75)
            assertThat(p.p75).isEqualByComparingTo(p.p90)
            assertThat(p.p50).isEqualByComparingTo(deterministic.series[i].value)
        }

        stochastic.percentiles.forEach { p ->
            assertThat(p.p10).isLessThanOrEqualTo(p.p25)
            assertThat(p.p25).isLessThanOrEqualTo(p.p50)
            assertThat(p.p50).isLessThanOrEqualTo(p.p75)
            assertThat(p.p75).isLessThanOrEqualTo(p.p90)
        }

        assertThat(deterministic.probabilityOfReachingTarget).isEqualTo(0.0)
        assertThat(stochastic.probabilityOfReachingTarget).isBetween(0.0, 1.0)
    }

    @Test
    fun `high mean and modest stddev gives high probability of hitting reachable target`() {
        val (household, user) = setupHouseholdWithSnapshot(BigDecimal("500000.00"))

        fireService.update(
            household.id,
            FireSettingsRequest(
                targetAmount = BigDecimal("750000.00"),
                targetYear = 2035,
                monthlyContribution = BigDecimal("2000.00"),
                returnScenarios = listOf(ReturnScenarioInput(BigDecimal("8.0"), BigDecimal("5.0"))),
                qualifyingAssetClasses = listOf("etfs"),
            ),
            user,
        )

        val out = fireService.project(household.id).scenarios.single()
        assertThat(out.probabilityOfReachingTarget).isGreaterThan(0.95)
        assertThat(out.medianYearReachingTarget).isNotNull
    }

    private fun setupHouseholdWithSnapshot(etfsValue: BigDecimal): Pair<Household, User> {
        val user = users.save(User(email = "f${System.nanoTime()}@example.com", passwordHash = "x"))
        val h = households.save(Household(name = "H", currency = "EUR"))
        val snapshot = Snapshot(
            householdId = h.id,
            snapshotDate = LocalDate.of(2025, 1, 1),
            createdByUserId = user.id,
            updatedByUserId = user.id,
        )
        snapshot.assetValues.add(SnapshotAssetValue(SnapshotAssetValueId(snapshot.id, "etfs"), etfsValue))
        snapshots.save(snapshot)
        return h to user
    }
}
