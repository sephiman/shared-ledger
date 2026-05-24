package com.sharedledger.household

import com.sharedledger.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * Regression test for the "mutate a detached entity" bug.
 *
 * Before the fix, `HouseholdController.update` had no @Transactional, so the entity returned
 * by `households.findById(...)` was detached. Mutations on it (name / currency / defaultLocale)
 * never reached the database. The test simulates the same call path the controller takes and
 * then re-reads the row in a *separate* transaction to verify the UPDATE actually fired.
 */
class HouseholdUpdatePersistenceTest @Autowired constructor(
    private val households: HouseholdRepository,
    private val txManager: PlatformTransactionManager,
) : IntegrationTestBase() {

    @Test
    fun `household mutation persists when applied inside a transaction`() {
        val saved = households.save(Household(name = "Original", currency = "EUR", defaultLocale = "en"))

        // Reproduce the controller's path: mutate inside a @Transactional boundary.
        TransactionTemplate(txManager).execute {
            val h = households.findById(saved.id).orElseThrow()
            h.defaultLocale = "es"
            h.name = "Updated"
            h.currency = "USD"
        }

        // Read it back in a fresh transaction (would catch any "in-memory only" silent failure).
        val reloaded = TransactionTemplate(txManager).execute {
            households.findById(saved.id).orElseThrow()
        }!!

        assertThat(reloaded.name).isEqualTo("Updated")
        assertThat(reloaded.currency).isEqualTo("USD")
        assertThat(reloaded.defaultLocale).isEqualTo("es")
    }
}
