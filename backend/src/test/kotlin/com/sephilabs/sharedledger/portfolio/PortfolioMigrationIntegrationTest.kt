package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class PortfolioMigrationIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val users: UserRepository,
    private val households: HouseholdRepository,
) : IntegrationTestBase() {

    @Test
    fun `index_funds asset class is renamed to fund`() {
        val codes = jdbc.queryForList("SELECT code FROM asset_classes ORDER BY sort_order", String::class.java)
        assertThat(codes).containsExactly("cash", "fund", "etfs", "stocks", "crypto", "pension")
    }

    @Test
    fun `fire_settings default qualifying classes use fund`() {
        val default = jdbc.queryForObject(
            """
            SELECT column_default FROM information_schema.columns
            WHERE table_name = 'fire_settings' AND column_name = 'qualifying_asset_classes'
            """,
            String::class.java,
        )
        assertThat(default).contains("fund").doesNotContain("index_funds")
    }

    @Test
    fun `snapshot asset values default value_source to overridden`() {
        val (user, household) = seed()
        val snapshotId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO snapshots (id, household_id, snapshot_date, created_by_user_id, updated_by_user_id)
            VALUES (?, ?, DATE '2025-01-31', ?, ?)
            """,
            snapshotId, household.id, user.id, user.id,
        )
        // No value_source supplied — mirrors every row that existed before the migration.
        jdbc.update(
            "INSERT INTO snapshot_asset_values (snapshot_id, asset_class_code, value) VALUES (?, 'crypto', 100.00)",
            snapshotId,
        )
        val source = jdbc.queryForObject(
            "SELECT value_source FROM snapshot_asset_values WHERE snapshot_id = ? AND asset_class_code = 'crypto'",
            String::class.java,
            snapshotId,
        )
        assertThat(source).isEqualTo("overridden")
    }

    private fun seed(): Pair<User, Household> {
        val user = users.save(User(email = "pm${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
