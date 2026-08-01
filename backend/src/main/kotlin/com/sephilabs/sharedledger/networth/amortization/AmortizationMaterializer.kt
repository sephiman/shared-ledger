package com.sephilabs.sharedledger.networth.amortization

import com.sephilabs.sharedledger.networth.liability.LiabilityRepository
import com.sephilabs.sharedledger.notification.NotifyActor
import com.sephilabs.sharedledger.notification.NotificationPublisher
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.ZoneId

/** Advances every amortizable liability on its charge day, persisting reached instalments as generated
 *  history and emitting the recurring-loan notification. Autonomous from transactions. NOT a @Scheduled
 *  bean — RecurringMaterializer.runForAll(today) invokes it, so there is one daily cron entrypoint. Each
 *  row commits in its own transaction, so a mid-run failure never loses earlier rows. */
@Component
class AmortizationMaterializer(
    private val liabilities: LiabilityRepository,
    private val parts: AmortizationPartRepository,
    private val entries: AmortizationEntryRepository,
    private val scheduleService: AmortizationScheduleService,
    private val txManager: PlatformTransactionManager,
    private val notifications: NotificationPublisher,
) {
    private val log = LoggerFactory.getLogger(AmortizationMaterializer::class.java)

    fun runForAll(today: LocalDate): Int {
        val amortizable = liabilities.findAllByAmortizableTrueAndActiveTrue()
        log.info("Running amortization materializer for {} amortizable liabilities", amortizable.size)
        var total = 0
        for (liability in amortizable) {
            total += materializeLiability(liability.id, liability.householdId, liability.name, today)
        }
        return total
    }

    private fun materializeLiability(liabilityId: java.util.UUID, householdId: java.util.UUID, name: String, today: LocalDate): Int {
        MDC.put("amortizableLiabilityId", liabilityId.toString())
        try {
            var created = 0
            var lastInstalment = java.math.BigDecimal.ZERO
            for (part in parts.findAllByLiabilityIdOrderByStartDateAscCreatedAtAsc(liabilityId)) {
                val liability = liabilities.findById(liabilityId).orElse(null) ?: continue
                val terms = scheduleService.termsFor(liability, part)
                val projection = AmortizationCalculator.project(
                    terms, scheduleService.revisionInputs(part.id), scheduleService.prepaymentInputs(part.id), horizon = today,
                )
                // Watermark falls back to the part's creation date (not startDate) so a backdated
                // startDate never triggers retroactive back-fill.
                val from = entries.findFirstByPartIdOrderByChargeDateDesc(part.id)?.chargeDate?.plusDays(1)
                    ?: part.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()
                val due = projection.rows.filter { !it.date.isBefore(from) && !it.date.isAfter(today) }
                for (row in due) {
                    try {
                        runInTx {
                            if (entries.existsByPartIdAndChargeDate(part.id, row.date)) return@runInTx
                            entries.save(
                                AmortizationEntry(
                                    partId = part.id,
                                    chargeDate = row.date,
                                    interest = row.interest,
                                    principal = row.principal,
                                    resultingBalance = row.balance,
                                ),
                            )
                        }
                        created++
                        lastInstalment = row.instalment
                    } catch (_: DataIntegrityViolationException) {
                        log.debug("Skipped duplicate amortization entry {} for part {}", row.date, part.id)
                    }
                }
            }
            if (created > 0) {
                notifications.amortizationInstalments(householdId, name, lastInstalment, created, NotifyActor.Schedule(householdId))
            }
            return created
        } catch (ex: Exception) {
            log.error("Amortization materialization failed for liability {}", liabilityId, ex)
            return 0
        } finally {
            MDC.remove("amortizableLiabilityId")
        }
    }

    private fun runInTx(block: () -> Unit) {
        TransactionTemplate(txManager).execute { block() }
    }
}
