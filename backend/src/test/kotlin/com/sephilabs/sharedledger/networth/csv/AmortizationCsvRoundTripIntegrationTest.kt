package com.sephilabs.sharedledger.networth.csv

import com.sephilabs.sharedledger.IntegrationTestBase
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.networth.amortization.*
import com.sephilabs.sharedledger.networth.liability.Liability
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Closes the CSV loop for amortizable liabilities: the liabilities export + the schedules export
 * together reconstruct the loan (definition, parts, revisions, prepayments, generated instalments)
 * in a fresh household, and re-importing is idempotent.
 */
class AmortizationCsvRoundTripIntegrationTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val liabilities: LiabilityRepository,
    private val parts: AmortizationPartRepository,
    private val revisions: AmortizationRateRevisionRepository,
    private val prepayments: AmortizationPrepaymentRepository,
    private val entries: AmortizationEntryRepository,
    private val exportService: NamedCsvExportService,
    private val liabilityImport: LiabilityImportService,
    private val amortizationImport: AmortizationImportService,
) : IntegrationTestBase() {

    @Test
    fun `export liabilities + schedules rebuilds the amortizable loan on import, idempotently`() {
        val user = users.save(User(email = "rt${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val source = households.save(Household(name = "Src", currency = "EUR", defaultLocale = "en"))
        val target = households.save(Household(name = "Dst", currency = "EUR", defaultLocale = "en"))

        // Source: one amortizable liability with a full schedule definition.
        val mortgage = liabilities.save(Liability(householdId = source.id, name = "Mortgage", amortizable = true, chargeDay = 1, createdByUserId = user.id, updatedByUserId = user.id))
        val part = parts.save(AmortizationPart(
            liabilityId = mortgage.id, method = AmortizationMethod.french, startMode = StartMode.origin,
            originalPrincipal = BigDecimal("100000.00"), annualRate = BigDecimal("3.0000"), termMonths = 300,
            startDate = LocalDate.of(2021, 3, 15), anchorDate = LocalDate.of(2023, 1, 1), anchorBalance = BigDecimal("90000.00"),
            createdByUserId = user.id, updatedByUserId = user.id,
        ))
        revisions.save(AmortizationRateRevision(partId = part.id, effectiveDate = LocalDate.of(2022, 1, 1), annualRate = BigDecimal("2.5000"), createdByUserId = user.id, updatedByUserId = user.id))
        prepayments.save(AmortizationPrepayment(partId = part.id, prepaymentDate = LocalDate.of(2021, 6, 1), amount = BigDecimal("5000.00"), mode = PrepaymentMode.reduce_term, createdByUserId = user.id, updatedByUserId = user.id))
        entries.save(AmortizationEntry(partId = part.id, chargeDate = LocalDate.of(2021, 4, 1), interest = BigDecimal("200.00"), principal = BigDecimal("300.00"), resultingBalance = BigDecimal("99700.00")))

        val liabilitiesCsv = exportService.exportLiabilities(source.id)
        val schedulesCsv = exportService.exportAmortization(source.id)

        // Import into the fresh household: liabilities first, then schedules.
        liabilityImport.execute(target.id, liabilitiesCsv.byteInputStream(), user)
        amortizationImport.execute(target.id, schedulesCsv.byteInputStream(), user)

        val rebuiltLiability = liabilities.findAllByHouseholdIdOrderByNameAsc(target.id).single()
        assertThat(rebuiltLiability.amortizable).isTrue()
        assertThat(rebuiltLiability.chargeDay).isEqualTo(1)

        val rebuiltPart = parts.findAllByLiabilityIdOrderByStartDateAscCreatedAtAsc(rebuiltLiability.id).single()
        assertThat(rebuiltPart.method).isEqualTo(AmortizationMethod.french)
        assertThat(rebuiltPart.startMode).isEqualTo(StartMode.origin)
        assertThat(rebuiltPart.originalPrincipal).isEqualByComparingTo("100000.00")
        assertThat(rebuiltPart.termMonths).isEqualTo(300)
        assertThat(rebuiltPart.startDate).isEqualTo(LocalDate.of(2021, 3, 15))
        assertThat(rebuiltPart.anchorDate).isEqualTo(LocalDate.of(2023, 1, 1))
        assertThat(rebuiltPart.anchorBalance).isEqualByComparingTo("90000.00")
        assertThat(revisions.findAllByPartIdOrderByEffectiveDateAsc(rebuiltPart.id)).singleElement()
            .satisfies({ assertThat(it.annualRate).isEqualByComparingTo("2.5000") })
        assertThat(prepayments.findAllByPartIdOrderByPrepaymentDateAsc(rebuiltPart.id)).singleElement()
            .satisfies({ assertThat(it.amount).isEqualByComparingTo("5000.00") })
        assertThat(entries.findAllByPartIdOrderByChargeDateAsc(rebuiltPart.id)).singleElement()
            .satisfies({ assertThat(it.resultingBalance).isEqualByComparingTo("99700.00") })

        // Re-importing the same exports is idempotent — no duplicated rows.
        liabilityImport.execute(target.id, liabilitiesCsv.byteInputStream(), user)
        amortizationImport.execute(target.id, schedulesCsv.byteInputStream(), user)
        assertThat(liabilities.findAllByHouseholdIdOrderByNameAsc(target.id)).hasSize(1)
        assertThat(parts.findAllByLiabilityIdOrderByStartDateAscCreatedAtAsc(rebuiltLiability.id)).hasSize(1)
        assertThat(revisions.findAllByPartIdOrderByEffectiveDateAsc(rebuiltPart.id)).hasSize(1)
        assertThat(prepayments.findAllByPartIdOrderByPrepaymentDateAsc(rebuiltPart.id)).hasSize(1)
        assertThat(entries.findAllByPartIdOrderByChargeDateAsc(rebuiltPart.id)).hasSize(1)
    }
}
