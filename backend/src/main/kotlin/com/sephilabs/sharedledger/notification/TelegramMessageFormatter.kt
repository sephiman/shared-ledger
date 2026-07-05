package com.sephilabs.sharedledger.notification

import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.i18n.Messages
import org.springframework.stereotype.Component
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import java.util.UUID

/**
 * Renders notification messages as Telegram light Markdown, reusing the mobile-card field set and
 * the frontend i18n keys (mirrored into messages_*.properties). All text resolves in the
 * household's configured [locale], money in the household [currency].
 */
@Component
class TelegramMessageFormatter(
    private val messages: Messages,
    private val categories: CategoryService,
) {

    fun formatEntityChange(
        event: EntityChangeEvent,
        householdId: UUID,
        locale: Locale,
        currency: String,
        authorEmail: String,
        schedule: Boolean,
    ): String {
        val entitySlug = entitySlug(event.entity)
        val actionSlug = event.action.name.lowercase()
        val header = messages.resolve("notif.header.$entitySlug.$actionSlug", locale = locale)
        return build(header, leadingEmoji(event.entity, event.fields), event.fields, householdId, locale, currency, authorEmail, schedule)
    }

    fun formatMaterialization(
        event: MaterializationEvent,
        householdId: UUID,
        locale: Locale,
        currency: String,
        authorEmail: String,
        schedule: Boolean,
    ): String {
        val entitySlug = entitySlug(event.entity)
        val header = if (event.count == 1) {
            messages.resolve("notif.header.$entitySlug.single", locale = locale)
        } else {
            messages.resolve("notif.header.$entitySlug.summary", arrayOf(event.count), locale = locale)
        }
        return build(header, leadingEmoji(event.entity, event.fields), event.fields, householdId, locale, currency, authorEmail, schedule)
    }

    fun testMessage(locale: Locale): String = messages.resolve("notif.test", locale = locale)

    private fun build(
        header: String,
        emoji: String,
        fields: List<CardField>,
        householdId: UUID,
        locale: Locale,
        currency: String,
        authorEmail: String,
        schedule: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.append("*").append(emoji).append(" ").append(sanitize(header)).append("*\n")
        for (field in fields) {
            val rendered = render(field.value, householdId, locale, currency) ?: continue
            val label = sanitize(messages.resolve(field.labelKey, locale = locale))
            sb.append("*").append(label).append("*: ").append(rendered).append("\n")
        }
        val authorKey = if (schedule) "notif.author.schedule" else "notif.author.by"
        sb.append("*").append(sanitize(messages.resolve(authorKey, arrayOf(authorEmail), locale = locale))).append("*")
        return sb.toString()
    }

    private fun render(value: FieldValue, householdId: UUID, locale: Locale, currency: String): String? = when (value) {
        is FieldValue.Money -> {
            val nf = NumberFormat.getCurrencyInstance(locale)
            runCatching { nf.currency = Currency.getInstance(currency) }
            nf.format(value.amount)
        }
        is FieldValue.Day -> value.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        is FieldValue.Text -> value.text?.takeIf { it.isNotBlank() }?.let { sanitize(it) } ?: "—"
        is FieldValue.Keyed -> sanitize(messages.resolve(value.i18nKey, locale = locale))
        is FieldValue.Category -> {
            val view = categories.find(householdId, value.code)
            val label = when {
                view == null -> value.code
                view.custom -> view.name
                else -> messages.resolve(view.name, locale = locale) // view.name is the "category.<code>" key
            }
            "${CategoryIcons.icon(value.code)} ${sanitize(label)}"
        }
    }

    private fun entitySlug(entity: NotifyEntity): String = when (entity) {
        NotifyEntity.TRANSACTION -> "transaction"
        NotifyEntity.SNAPSHOT -> "snapshot"
        NotifyEntity.MOVEMENT -> "movement"
        NotifyEntity.LOAN_PAYMENT -> "loan_payment"
        NotifyEntity.HOLDING -> "holding"
        NotifyEntity.RECURRING_TXN -> "recurring_txn"
        NotifyEntity.RECURRING_LOAN -> "recurring_loan"
        NotifyEntity.BANK_MOVEMENT -> "bank_movement"
        NotifyEntity.BANK_CONNECTION -> "bank_connection"
    }

    /** Category emoji when the card carries a category; otherwise a fixed per-entity emoji. */
    private fun leadingEmoji(entity: NotifyEntity, fields: List<CardField>): String {
        (fields.firstOrNull { it.value is FieldValue.Category }?.value as? FieldValue.Category)?.let {
            return CategoryIcons.icon(it.code)
        }
        return when (entity) {
            NotifyEntity.SNAPSHOT -> "📸"
            NotifyEntity.MOVEMENT -> "🔄"
            NotifyEntity.LOAN_PAYMENT -> "💸"
            NotifyEntity.HOLDING -> "📈"
            NotifyEntity.RECURRING_LOAN -> "📅"
            NotifyEntity.BANK_MOVEMENT -> "🏦"
            NotifyEntity.BANK_CONNECTION -> "⏰"
            else -> "🔔"
        }
    }

    /** Strip Telegram legacy-Markdown control chars from interpolated text to avoid parse errors. */
    private fun sanitize(text: String): String = text.filterNot { it in MARKDOWN_CONTROL }

    private companion object {
        val MARKDOWN_CONTROL = charArrayOf('*', '_', '`', '[', ']').toSet()
    }
}
