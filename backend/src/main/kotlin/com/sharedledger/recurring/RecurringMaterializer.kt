package com.sharedledger.recurring

import com.sharedledger.loan.LoanScheduleMaterializer
import com.sharedledger.observability.AppMetrics
import com.sharedledger.transaction.Transaction
import com.sharedledger.transaction.TransactionRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.util.UUID

@Component
class RecurringMaterializer(
    private val templates: RecurringTemplateRepository,
    private val transactions: TransactionRepository,
    private val metrics: AppMetrics,
    private val txManager: PlatformTransactionManager,
    private val loanScheduleMaterializer: LoanScheduleMaterializer,
) {
    private val log = LoggerFactory.getLogger(RecurringMaterializer::class.java)

    @Scheduled(cron = "\${app.scheduler.recurring-cron}", zone = "\${app.scheduler.timezone}")
    fun runDaily() {
        runForAll(LocalDate.now())
    }

    fun runForAll(today: LocalDate) {
        val active = templates.findAllByActiveTrue()
        log.info("Running recurring materializer for {} active templates", active.size)
        for (template in active) {
            materializeOne(template, today)
        }
        loanScheduleMaterializer.runForAll(today)
    }

    fun runForHousehold(householdId: UUID, today: LocalDate): Int {
        var created = 0
        templates.findAllByHouseholdIdAndActiveTrue(householdId).forEach {
            created += materializeOne(it, today)
        }
        return created
    }

    private fun materializeOne(template: RecurringTemplate, today: LocalDate): Int {
        val template_id = template.id.toString()
        MDC.put("templateId", template_id)
        try {
            val cap = template.endDate?.let { if (it.isBefore(today)) it else today } ?: today
            val from = template.lastMaterializedThrough?.plusDays(1) ?: template.startDate
            if (from.isAfter(cap)) return 0

            val dates = RecurringDateMath.occurrencesInRange(template, from, cap)
            if (dates.isEmpty()) {
                runInTx { templates.save(template.apply { lastMaterializedThrough = cap }) }
                return 0
            }

            var created = 0
            for (date in dates) {
                try {
                    runInTx {
                        val tx = Transaction(
                            householdId = template.householdId,
                            occurrenceDate = date,
                            direction = template.direction,
                            categoryCode = template.categoryCode,
                            amount = template.amount,
                            description = template.description,
                            recurringTemplateId = template.id,
                            createdByUserId = template.createdByUserId,
                            updatedByUserId = template.updatedByUserId,
                        )
                        transactions.save(tx)
                    }
                    metrics.recurringMaterialized(template_id)
                    created++
                } catch (ignored: DataIntegrityViolationException) {
                    // Idempotency: row already exists from a prior run.
                    log.debug("Skipped duplicate occurrence on {} for template {}", date, template_id)
                }
            }
            runInTx {
                val refreshed = templates.findById(template.id).orElse(template)
                refreshed.lastMaterializedThrough = cap
                templates.save(refreshed)
            }
            return created
        } catch (ex: Exception) {
            metrics.recurringFailure(template_id)
            log.error("Recurring materialization failed for template {}", template_id, ex)
            return 0
        } finally {
            MDC.remove("templateId")
        }
    }

    private fun runInTx(block: () -> Unit) {
        TransactionTemplate(txManager).execute { block() }
    }
}
