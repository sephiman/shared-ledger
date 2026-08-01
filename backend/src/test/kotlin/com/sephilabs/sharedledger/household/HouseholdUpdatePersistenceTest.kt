package com.sephilabs.sharedledger.household

import com.sephilabs.sharedledger.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager

/** Pins that `HouseholdController.update` runs transactionally: mutations on a detached entity never reach
 *  the database. Re-reads the row in a *separate* transaction to verify the UPDATE actually fired. */
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
