package com.sephilabs.sharedledger.lending

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
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
import java.util.UUID

/** Materializes expected lending payments from active schedules. NOT a @Scheduled bean itself —
 *  RecurringMaterializer.runDaily() invokes runForAll(today) so there is one daily cron entrypoint.
 *  Idempotency comes from the unique partial index (schedule_id, payment_date). */
@Component
class LendingScheduleMaterializer(
    private val lendings: LendingRepository,
    private val schedules: LendingScheduleRepository,
    private val payments: LendingPaymentRepository,
    private val txManager: PlatformTransactionManager,
    private val notifications: NotificationPublisher,
) {
    private val log = LoggerFactory.getLogger(LendingScheduleMaterializer::class.java)

    fun runForAll(today: LocalDate): Int {
        val active = schedules.findAllByActiveTrue()
        log.info("Running lending schedule materializer for {} active schedules", active.size)
        var totalCreated = 0
        for (schedule in active) {
            totalCreated += materializeOne(schedule, today)
        }
        return totalCreated
    }

    fun fireNow(householdId: UUID, lendingId: UUID, by: User, today: LocalDate): Int {
        val lending = lendings.findById(lendingId).orElseThrow { AppException.notFound("LENDING_NOT_FOUND") }
        if (lending.householdId != householdId) throw AppException.notFound("LENDING_NOT_FOUND")
        if (lending.status != LendingStatus.active) return 0
        val schedule = schedules.findByLendingId(lending.id) ?: throw AppException.notFound("LENDING_SCHEDULE_NOT_FOUND")
        return materializeOne(schedule, today, firedBy = by, forceFireToday = true)
    }

    private fun materializeOne(
        schedule: LendingSchedule,
        today: LocalDate,
        firedBy: User? = null,
        forceFireToday: Boolean = false,
    ): Int {
        val scheduleId = schedule.id.toString()
        MDC.put("lendingScheduleId", scheduleId)
        try {
            val lending = lendings.findById(schedule.lendingId).orElse(null) ?: return 0
            if (lending.deletedAt != null || lending.status != LendingStatus.active) return 0

            // Null watermark falls back to the entity's last edit, not the lending's startDate,
            // so a backdated startDate never triggers retroactive back-fill.
            val from = schedule.lastMaterializedThrough?.plusDays(1)
                ?: schedule.updatedAt.atZone(ZoneId.systemDefault()).toLocalDate()

            val cadenceDates: List<LocalDate> = if (from.isAfter(today)) emptyList()
                else LendingScheduleDateMath.occurrencesInRange(schedule, lending.startDate, from, today)
            val dates: List<LocalDate> = if (forceFireToday) (cadenceDates + today).toSortedSet().toList()
                else cadenceDates
            if (dates.isEmpty()) {
                runInTx {
                    val refreshed = schedules.findById(schedule.id).orElse(schedule)
                    refreshed.lastMaterializedThrough = today
                    schedules.save(refreshed)
                }
                return 0
            }

            val userId = firedBy?.id ?: lending.updatedByUserId
            var created = 0
            for (date in dates) {
                try {
                    runInTx {
                        if (payments.existsByScheduleIdAndPaymentDate(schedule.id, date)) return@runInTx
                        val payment = LendingPayment(
                            lendingId = lending.id,
                            paymentDate = date,
                            amount = schedule.expectedAmount,
                            description = null,
                            scheduleId = schedule.id,
                            createdByUserId = userId,
                            updatedByUserId = userId,
                        )
                        payments.save(payment)
                    }
                    created++
                } catch (_: DataIntegrityViolationException) {
                    log.debug("Skipped duplicate lending payment on {} for schedule {}", date, scheduleId)
                }
            }
            runInTx {
                val refreshed = schedules.findById(schedule.id).orElse(schedule)
                refreshed.lastMaterializedThrough = today
                schedules.save(refreshed)
            }
            val actor = firedBy?.let { NotifyActor.Human(it.email) } ?: NotifyActor.Schedule(lending.householdId)
            notifications.recurringLendingPayments(
                lending.householdId, lending.borrowerName, schedule.expectedAmount, created, actor,
            )
            return created
        } catch (ex: Exception) {
            log.error("Lending schedule materialization failed for schedule {}", scheduleId, ex)
            return 0
        } finally {
            MDC.remove("lendingScheduleId")
        }
    }

    private fun runInTx(block: () -> Unit) {
        TransactionTemplate(txManager).execute { block() }
    }
}
