package com.sephilabs.sharedledger.networth.amortization

import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.networth.liability.Liability
import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * Pure schedule computation for amortizable liabilities: current balance, projected schedule, and
 * prepayment simulation. No mutations and no notifications, so it is safe to call from
 * [com.sephilabs.sharedledger.networth.NamedValueResolver] (snapshots) as well as the controller.
 */
@Service
class AmortizationScheduleService(
    private val liabilities: LiabilityRepository,
    private val parts: AmortizationPartRepository,
    private val revisions: AmortizationRateRevisionRepository,
    private val prepayments: AmortizationPrepaymentRepository,
    private val entries: AmortizationEntryRepository,
) {

    /** Total outstanding balance across all parts as of [date]. Null if the liability isn't amortizable. */
    @Transactional(readOnly = true)
    fun balanceAt(liabilityId: UUID, date: LocalDate): BigDecimal? {
        val liability = liabilities.findById(liabilityId).orElse(null) ?: return null
        if (!liability.amortizable) return null
        val partList = parts.findAllByLiabilityIdOrderByStartDateAscCreatedAtAsc(liabilityId)
        if (partList.isEmpty()) return Money.normalize(BigDecimal.ZERO)
        val total = partList.fold(BigDecimal.ZERO) { acc, part ->
            acc + AmortizationCalculator.balanceAt(termsFor(liability, part), revisionInputs(part.id), prepaymentInputs(part.id), date, anchorFor(part))
        }
        return Money.normalize(total)
    }

    @Transactional(readOnly = true)
    fun schedule(householdId: UUID, liabilityId: UUID, today: LocalDate = LocalDate.now()): LiabilityScheduleDto {
        val liability = loadOwn(householdId, liabilityId)
        val partList = parts.findAllByLiabilityIdOrderByStartDateAscCreatedAtAsc(liabilityId)
        val partDtos = partList.map { part ->
            val anchor = anchorFor(part)
            val projection = AmortizationCalculator.project(termsFor(liability, part), revisionInputs(part.id), prepaymentInputs(part.id), anchor = anchor)
            // Evaluate the current balance no earlier than the part's own balance date, so a funded
            // active part always reads its schedule balance (the outstanding principal before the
            // first charge) — never 0, even when the balance date is today or a day ahead (TZ skew).
            val asOf = maxOf(today, part.startDate)
            val currentBalance = AmortizationCalculator.balanceAt(termsFor(liability, part), revisionInputs(part.id), prepaymentInputs(part.id), asOf, anchor)
            val upcoming = projection.rows.firstOrNull { !it.date.isBefore(today) } ?: projection.rows.lastOrNull()
            PartScheduleDto(
                partId = part.id,
                label = part.label,
                method = part.method,
                startMode = part.startMode,
                originalPrincipal = part.originalPrincipal,
                annualRate = part.annualRate,
                startDate = part.startDate,
                termMonths = part.termMonths,
                instalmentInput = part.instalment,
                anchorDate = part.anchorDate,
                anchorBalance = part.anchorBalance,
                currentBalance = currentBalance,
                instalment = upcoming?.instalment ?: Money.normalize(BigDecimal.ZERO),
                payoffDate = projection.payoffDate,
                totalInterestRemaining = interestFrom(projection, today),
                rows = projection.rows.map { ScheduleRowDto(it.date, it.interest, it.principal, it.balance, it.instalment) },
                charged = entries.findAllByPartIdOrderByChargeDateAsc(part.id)
                    .map { ChargedEntryDto(it.chargeDate, it.interest, it.principal, it.resultingBalance) },
                revisions = revisions.findAllByPartIdOrderByEffectiveDateAsc(part.id)
                    .map { RevisionDto(it.id, it.effectiveDate, it.annualRate) },
                prepayments = prepayments.findAllByPartIdOrderByPrepaymentDateAsc(part.id)
                    .map { PrepaymentDto(it.id, it.partId, it.prepaymentDate, it.amount, it.mode) },
            )
        }
        val allRows = partDtos.flatMap { it.rows }
        val outstanding = Money.normalize(partDtos.fold(BigDecimal.ZERO) { a, p -> a + p.currentBalance })
        val totalPrincipal = Money.normalize(partDtos.fold(BigDecimal.ZERO) { a, p -> a + p.originalPrincipal })
        val interestPaid = Money.normalize(allRows.filter { !it.date.isAfter(today) }.fold(BigDecimal.ZERO) { a, r -> a + r.interest })
        val interestRemaining = Money.normalize(allRows.filter { it.date.isAfter(today) }.fold(BigDecimal.ZERO) { a, r -> a + r.interest })
        val principalPaid = Money.normalize((totalPrincipal - outstanding).coerceAtLeast(BigDecimal.ZERO))
        val progress = if (totalPrincipal.signum() > 0) {
            principalPaid.divide(totalPrincipal, 4, RoundingMode.HALF_EVEN)
        } else BigDecimal.ZERO
        return LiabilityScheduleDto(
            liabilityId = liabilityId,
            chargeDay = liability.chargeDay,
            currentBalance = outstanding,
            monthlyInstalment = Money.normalize(partDtos.fold(BigDecimal.ZERO) { a, p -> a + p.instalment }),
            totalPrincipal = totalPrincipal,
            interestPaid = interestPaid,
            principalPaid = principalPaid,
            interestRemaining = interestRemaining,
            totalInterest = Money.normalize(interestPaid + interestRemaining),
            progress = progress,
            parts = partDtos,
        )
    }

    /** Interest saved by a hypothetical prepayment vs not making it, plus the new term/instalment. */
    @Transactional(readOnly = true)
    fun simulate(householdId: UUID, liabilityId: UUID, partId: UUID, req: SimulationRequest): SimulationResultDto {
        val liability = loadOwn(householdId, liabilityId)
        val part = ownPart(liabilityId, partId)
        val terms = termsFor(liability, part)
        val revs = revisionInputs(part.id)
        val existing = prepaymentInputs(part.id)
        val hypothetical = existing + PrepaymentInput(req.prepaymentDate, req.amount, req.mode)

        val anchor = anchorFor(part)
        val baseline = AmortizationCalculator.project(terms, revs, existing, anchor = anchor)
        val withPrepay = AmortizationCalculator.project(terms, revs, hypothetical, anchor = anchor)
        val after = req.prepaymentDate
        return SimulationResultDto(
            interestSaved = Money.normalize(baseline.totalInterest.subtract(withPrepay.totalInterest)),
            baselinePayoffDate = baseline.payoffDate,
            newPayoffDate = withPrepay.payoffDate,
            baselineInstalment = instalmentOnOrAfter(baseline, after),
            newInstalment = instalmentOnOrAfter(withPrepay, after),
            baselineTotalInterest = baseline.totalInterest,
            newTotalInterest = withPrepay.totalInterest,
        )
    }

    fun termsFor(liability: Liability, part: AmortizationPart): PartTerms = PartTerms(
        principal = part.originalPrincipal,
        annualRate = part.annualRate,
        method = part.method,
        termMonths = part.termMonths,
        instalment = part.instalment,
        startDate = part.startDate,
        chargeDay = liability.chargeDay ?: part.startDate.dayOfMonth,
    )

    fun anchorFor(part: AmortizationPart): AnchorInput? {
        val date = part.anchorDate ?: return null
        val balance = part.anchorBalance ?: return null
        return AnchorInput(date, balance)
    }

    fun revisionInputs(partId: UUID): List<RevisionInput> =
        revisions.findAllByPartIdOrderByEffectiveDateAsc(partId).map { RevisionInput(it.effectiveDate, it.annualRate) }

    fun prepaymentInputs(partId: UUID): List<PrepaymentInput> =
        prepayments.findAllByPartIdOrderByPrepaymentDateAsc(partId).map { PrepaymentInput(it.prepaymentDate, it.amount, it.mode) }

    fun ownPart(liabilityId: UUID, partId: UUID): AmortizationPart {
        val part = parts.findById(partId).orElseThrow { AppException.notFound("AMORTIZATION_PART_NOT_FOUND") }
        if (part.liabilityId != liabilityId) throw AppException.notFound("AMORTIZATION_PART_NOT_FOUND")
        return part
    }

    fun loadOwn(householdId: UUID, liabilityId: UUID): Liability {
        val liability = liabilities.findById(liabilityId).orElseThrow { AppException.notFound("LIABILITY_NOT_FOUND") }
        if (liability.householdId != householdId) throw AppException.notFound("LIABILITY_NOT_FOUND")
        return liability
    }

    private fun interestFrom(projection: Projection, from: LocalDate): BigDecimal =
        Money.normalize(projection.rows.filter { !it.date.isBefore(from) }.fold(BigDecimal.ZERO) { a, r -> a + r.interest })

    private fun instalmentOnOrAfter(projection: Projection, date: LocalDate): BigDecimal =
        projection.rows.firstOrNull { !it.date.isBefore(date) }?.instalment
            ?: projection.rows.lastOrNull()?.instalment
            ?: Money.normalize(BigDecimal.ZERO)
}
