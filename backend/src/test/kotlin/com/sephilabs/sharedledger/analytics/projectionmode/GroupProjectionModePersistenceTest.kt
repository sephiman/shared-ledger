package com.sephilabs.sharedledger.analytics.projectionmode

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

class GroupProjectionModePersistenceTest @Autowired constructor(
    private val service: GroupProjectionModeService,
    private val households: HouseholdRepository,
    private val jdbc: JdbcTemplate,
) : IntegrationTestBase() {

    @Test
    fun `every expense group reads as envelope when nothing is stored`() {
        val household = newHousehold()

        val listed = service.list(household.id)

        assertThat(listed.map { it.groupCode }).containsExactlyInAnyOrder(
            "home", "transport", "groceries", "shopping", "outings", "financial", "health", "personal")
        assertThat(listed.map { it.mode }).containsOnly(ProjectionMode.ENVELOPE)
        assertThat(rowCount(household)).isZero()
    }

    @Test
    fun `a stored mode persists and can be switched back`() {
        val household = newHousehold()

        service.set(household.id, "groceries", ProjectionMode.PRORATE)
        assertThat(service.modes(household.id).of("groceries")).isEqualTo(ProjectionMode.PRORATE)
        assertThat(service.modes(household.id).of("home")).isEqualTo(ProjectionMode.ENVELOPE)

        service.set(household.id, "groceries", ProjectionMode.ENVELOPE)
        assertThat(service.modes(household.id).of("groceries")).isEqualTo(ProjectionMode.ENVELOPE)
        assertThat(rowCount(household)).isEqualTo(1)
    }

    @Test
    fun `modes are kept per household`() {
        val mine = newHousehold()
        val theirs = newHousehold()

        service.set(mine.id, "outings", ProjectionMode.PRORATE)

        assertThat(service.modes(theirs.id).of("outings")).isEqualTo(ProjectionMode.ENVELOPE)
    }

    @Test
    fun `an unknown group is rejected and nothing is stored`() {
        val household = newHousehold()

        assertThatThrownBy { service.set(household.id, "income", ProjectionMode.PRORATE) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("INVALID_PARAMETER")
        assertThat(rowCount(household)).isZero()
    }

    @Test
    fun `the database rejects a mode outside the two known values`() {
        val household = newHousehold()

        assertThatThrownBy {
            jdbc.update("INSERT INTO group_projection_modes (household_id, group_code, mode) VALUES (?, 'home', 'LINEAR')",
                household.id)
        }.hasMessageContaining("group_projection_modes_mode_check")
    }

    @Test
    fun `deleting the household deletes its modes`() {
        val household = newHousehold()
        service.set(household.id, "home", ProjectionMode.PRORATE)

        jdbc.update("DELETE FROM households WHERE id = ?", household.id)

        assertThat(rowCount(household)).isZero()
    }

    private fun newHousehold(): Household =
        households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))

    private fun rowCount(household: Household): Int =
        jdbc.queryForObject("SELECT count(*) FROM group_projection_modes WHERE household_id = ?", Int::class.java,
            household.id)!!
}
