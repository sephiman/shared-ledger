package com.sephilabs.sharedledger.networth.amortization

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AmortizationPartRepository : JpaRepository<AmortizationPart, UUID> {
    fun findAllByLiabilityIdOrderByStartDateAscCreatedAtAsc(liabilityId: UUID): List<AmortizationPart>
}

interface AmortizationRateRevisionRepository : JpaRepository<AmortizationRateRevision, UUID> {
    fun findAllByPartIdOrderByEffectiveDateAsc(partId: UUID): List<AmortizationRateRevision>
}

interface AmortizationPrepaymentRepository : JpaRepository<AmortizationPrepayment, UUID> {
    fun findAllByPartIdOrderByPrepaymentDateAsc(partId: UUID): List<AmortizationPrepayment>
}

interface AmortizationEntryRepository : JpaRepository<AmortizationEntry, UUID> {
    fun findAllByPartIdOrderByChargeDateAsc(partId: UUID): List<AmortizationEntry>
    fun existsByPartIdAndChargeDate(partId: UUID, chargeDate: java.time.LocalDate): Boolean
    fun findFirstByPartIdOrderByChargeDateDesc(partId: UUID): AmortizationEntry?
}
