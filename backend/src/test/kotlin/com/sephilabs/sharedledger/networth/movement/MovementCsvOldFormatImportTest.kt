package com.sephilabs.sharedledger.networth.movement

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.liability.Liability
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/** The Spanish label of a movement type changed ("Retiro" → "Retirada"); files store the type code, so a file
 *  exported before the change must import exactly as it did. */
class MovementCsvOldFormatImportTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val liabilities: LiabilityRepository,
    private val importService: MovementImportService,
    private val movements: MovementService,
    private val repository: MovementRepository,
) : IntegrationTestBase() {

    @Test
    fun `a movements file exported before the Spanish relabel imports unchanged and re-exports type codes`() {
        val user = users.save(User(email = "mvold${System.nanoTime()}@example.com", passwordHash = "x", locale = "es"))
        val household = households.save(Household(name = "Casa", currency = "EUR", defaultLocale = "es"))
        liabilities.save(Liability(householdId = household.id, name = "Hipoteca", createdByUserId = user.id, updatedByUserId = user.id))

        val preview = importService.preview(household.id, OLD_EXPORT.byteInputStream())
        assertThat(preview.errors).isEmpty()

        val result = importService.execute(household.id, OLD_EXPORT.byteInputStream(), user)

        assertThat(result.inserted).isEqualTo(3)
        val imported = repository.findInRange(household.id, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))
            .associateBy { it.type }
        assertThat(imported.keys).containsExactlyInAnyOrder(MovementType.contribution, MovementType.withdrawal, MovementType.debt_payment)
        assertThat(imported.getValue(MovementType.withdrawal).assetClassCode).isEqualTo("crypto")
        assertThat(imported.getValue(MovementType.withdrawal).amount).isEqualByComparingTo(BigDecimal("1200.00"))
        assertThat(imported.getValue(MovementType.withdrawal).description).isEqualTo("Venta BTC")

        val reExport = movements.exportCsv(household.id)
        assertThat(reExport).contains(";withdrawal;crypto;").doesNotContain("Retirada").doesNotContain("Retiro")

        // Importing the same old file again is idempotent, as before.
        assertThat(importService.execute(household.id, OLD_EXPORT.byteInputStream(), user).inserted).isEqualTo(0)
    }

    private companion object {
        /** Verbatim shape of `MovementService.exportCsv` before the relabel: European CSV, type codes, CRLF. */
        val OLD_EXPORT = listOf(
            "date;type;asset_class_code;liability_name;amount;description;created_at",
            "2025-03-01;contribution;etfs;;300,00;VWCE;2025-03-01T08:00:00Z",
            "2025-03-10;withdrawal;crypto;;1200,00;Venta BTC;2025-03-10T09:30:00Z",
            "2025-03-15;debt_payment;;Hipoteca;450,00;;2025-03-15T10:00:00Z",
        ).joinToString("\r\n", postfix = "\r\n")
    }
}
