package com.sephilabs.sharedledger.notification

import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.identity.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.Locale
import java.util.UUID

/**
 * Consumes domain change/materialization events and dispatches Telegram messages.
 *
 * Runs on a dedicated executor (async) so Telegram I/O never blocks the request or the nightly
 * scheduler, and AFTER_COMMIT so a rolled-back write never notifies. [MaterializationEvent] uses
 * `fallbackExecution=true`: the scheduler publishes after its per-row commits with no surrounding
 * transaction (run immediately), while a manual "fire now" runs inside one transaction (waits for
 * commit). Delivery failures are logged for the Grafana pipeline, never surfaced or retried.
 */
@Component
class TelegramNotificationListener(
    private val settingsRepo: TelegramSettingsRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val users: UserRepository,
    private val crypto: TelegramCrypto,
    private val formatter: TelegramMessageFormatter,
    private val client: TelegramClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("telegramExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onEntityChange(event: EntityChangeEvent) {
        val ctx = resolve(event.householdId, event.entity, event.actor) ?: return
        val text = formatter.formatEntityChange(
            event, event.householdId, ctx.locale, ctx.currency, ctx.authorEmail, ctx.schedule,
        )
        dispatch(ctx, event.entity, event.action.name, text)
    }

    @Async("telegramExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onMaterialization(event: MaterializationEvent) {
        val ctx = resolve(event.householdId, event.entity, event.actor) ?: return
        val text = formatter.formatMaterialization(
            event, event.householdId, ctx.locale, ctx.currency, ctx.authorEmail, ctx.schedule,
        )
        dispatch(ctx, event.entity, "MATERIALIZE", text)
    }

    private data class DispatchContext(
        val settings: TelegramSettings,
        val locale: Locale,
        val currency: String,
        val authorEmail: String,
        val schedule: Boolean,
    )

    private fun resolve(householdId: UUID, entity: NotifyEntity, actor: NotifyActor): DispatchContext? {
        val settings = settingsRepo.findByHouseholdId(householdId) ?: return null
        if (!settings.isEnabledFor(entity)) return null
        if (!settings.isDeliverable()) {
            log.warn("telegram_notify_skipped household={} entity={} reason=not_configured", householdId, entity)
            return null
        }
        val household = households.findById(householdId).orElse(null) ?: return null
        val locale = Locale.forLanguageTag(household.defaultLocale.ifBlank { "en" })
        val schedule = actor is NotifyActor.Schedule
        val authorEmail = when (actor) {
            is NotifyActor.Human -> actor.email
            is NotifyActor.Schedule -> ownerEmail(householdId) ?: ""
        }
        return DispatchContext(settings, locale, household.currency, authorEmail, schedule)
    }

    private fun ownerEmail(householdId: UUID): String? {
        val owner = members.findAllByIdHouseholdId(householdId).firstOrNull { it.role == HouseholdRole.owner }
            ?: return null
        return users.findById(owner.id.userId).map { it.email }.orElse(null)
    }

    private fun dispatch(ctx: DispatchContext, entity: NotifyEntity, action: String, text: String) {
        val token = try {
            crypto.decrypt(ctx.settings.botTokenEnc!!)
        } catch (ex: Exception) {
            log.error("telegram_notify_failed household={} entity={} reason=token_decrypt", ctx.settings.householdId, entity, ex)
            return
        }
        val result = client.sendMessage(token, ctx.settings.chatId!!, text)
        if (result.ok) {
            log.info("telegram_notify household={} entity={} action={} ok=true", ctx.settings.householdId, entity, action)
        } else {
            log.warn(
                "telegram_notify household={} entity={} action={} ok=false description={}",
                ctx.settings.householdId, entity, action, result.description,
            )
        }
    }
}
