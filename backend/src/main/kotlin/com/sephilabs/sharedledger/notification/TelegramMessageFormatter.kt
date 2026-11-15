package com.sephilabs.sharedledger.notification

import com.sephilabs.sharedledger.catalog.CategoryService
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.i18n.Messages
import com.sephilabs.sharedledger.portfolio.alert.AlertDirection
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import java.util.UUID

/** Renders notifications as Telegram light Markdown, reusing the mobile-card field set and the frontend
 *  i18n keys (mirrored into messages_*.properties). Text in the household [locale], money in its [currency]. */
@Component
class TelegramMessageFormatter(
    private val messages: Messages,
    private val categories: CategoryService,
    private val props: AppProperties,
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

    /** One header, one line per connection, then where to re-link. No author line: nobody acted. */
    fun formatConsentExpiry(notices: List<ConsentNotice>, locale: Locale): String {
        val header = if (notices.size == 1) {
            messages.resolve("notif.consent.header.single", locale = locale)
        } else {
            messages.resolve("notif.consent.header.summary", arrayOf(notices.size), locale = locale)
        }
        val sb = StringBuilder()
        sb.append("*⏰ ").append(sanitize(header)).append("*\n")
        for (notice in notices) {
            sb.append("• ").append(consentLine(notice, locale)).append("\n")
        }
        sb.append(sanitize(messages.resolve("notif.consent.footer", locale = locale)))
        return sb.toString()
    }

    private fun consentLine(notice: ConsentNotice, locale: Locale): String {
        val label = notice.label?.takeIf { it.isNotBlank() } ?: notice.bankName
        // The label defaults to the bank name; only name the bank when the label doesn't already.
        val name = if (label == notice.bankName) "*${sanitize(label)}*" else "*${sanitize(label)}* (${sanitize(notice.bankName)})"
        val date = notice.expiresOn?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) ?: "—"
        val key = when (notice.state) {
            ConsentState.EXPIRES_TODAY -> "notif.consent.expires_today"
            ConsentState.EXPIRES_TOMORROW -> "notif.consent.expires_tomorrow"
            ConsentState.EXPIRED -> "notif.consent.expired"
        }
        return "$name: ${sanitize(messages.resolve(key, arrayOf(date), locale = locale))}"
    }

    fun testMessage(locale: Locale): String = messages.resolve("notif.test", locale = locale)

    fun formatPriceAlert(
        symbol: String,
        label: String?,
        direction: AlertDirection,
        threshold: BigDecimal,
        priceCurrency: String,
        firedPrice: BigDecimal,
        priceObservedAt: Instant,
        quantity: BigDecimal,
        currentValueBase: BigDecimal?,
        unrealizedPnlBase: BigDecimal?,
        unrealizedPnlPct: BigDecimal?,
        baseCurrency: String,
        note: String?,
        locale: Locale,
    ): String {
        val assetTitle = label?.takeIf { it.isNotBlank() && it != symbol }
            ?.let { "${sanitize(symbol)} (${sanitize(it)})" }
            ?: sanitize(symbol)
        val header = messages.resolve("notif.price_alert.header", arrayOf(assetTitle), locale = locale)
        val priceFormatted = formatPrice(firedPrice, priceCurrency, locale)
        val thresholdFormatted = formatPrice(threshold, priceCurrency, locale)
        val whenFormatted = formatTimestamp(priceObservedAt, props.displayZoneId(), locale)
        val conditionKey = if (direction == AlertDirection.ABOVE) {
            "notif.price_alert.condition.above"
        } else {
            "notif.price_alert.condition.below"
        }
        val conditionText = messages.resolve(conditionKey, arrayOf(thresholdFormatted), locale = locale)

        val qtyText = "${formatQuantity(quantity, locale)} ${sanitize(symbol)}"
        val valueText = currentValueBase?.let { formatMoney(it, baseCurrency, locale) } ?: "—"
        val pnlText = if (unrealizedPnlBase != null) {
            val signedAmt = formatSignedMoney(unrealizedPnlBase, baseCurrency, locale)
            val pctPart = unrealizedPnlPct?.let { " (${formatSignedPercent(it, locale)})" } ?: ""
            "$signedAmt$pctPart"
        } else {
            "—"
        }

        val sb = StringBuilder()
        sb.append("*🔔 ").append(sanitize(header)).append("*\n")
        sb.append("*").append(sanitize(messages.resolve("notif.price_alert.price_reached", locale = locale))).append("*: ")
            .append(sanitize(priceFormatted)).append(" (").append(sanitize(whenFormatted)).append(")\n")
        sb.append("*").append(sanitize(messages.resolve("notif.price_alert.condition", locale = locale))).append("*: ")
            .append(sanitize(conditionText)).append("\n")
        sb.append("*").append(sanitize(messages.resolve("notif.price_alert.position", locale = locale))).append("*: ")
            .append(qtyText).append("\n")
        sb.append("*").append(sanitize(messages.resolve("notif.price_alert.value", locale = locale))).append("*: ")
            .append(sanitize(valueText)).append("\n")
        sb.append("*").append(sanitize(messages.resolve("notif.price_alert.unrealized_pnl", locale = locale))).append("*: ")
            .append(sanitize(pnlText)).append("\n")
        if (!note.isNullOrBlank()) {
            sb.append("*").append(sanitize(messages.resolve("notif.price_alert.note", locale = locale))).append("*: ")
                .append(sanitize(note)).append("\n")
        }
        val baseUrl = props.publicUrl.trim().trimEnd('/')
        if (baseUrl.isNotEmpty()) {
            sb.append(baseUrl).append("/networth?tab=holdings")
        }
        return sb.toString().trimEnd()
    }

    private fun formatPrice(amount: BigDecimal, currency: String, locale: Locale): String {
        val clean = amount.stripTrailingZeros()
        val scale = maxOf(0, clean.scale())
        val decimals = if (clean.abs() >= BigDecimal.ONE) 2 else minOf(12, maxOf(2, scale))
        val nf = NumberFormat.getCurrencyInstance(locale)
        runCatching { nf.currency = Currency.getInstance(currency) }
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = decimals
        return nf.format(amount)
    }

    private fun formatMoney(amount: BigDecimal, currency: String, locale: Locale): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        runCatching { nf.currency = Currency.getInstance(currency) }
        nf.minimumFractionDigits = 2
        nf.maximumFractionDigits = 2
        return nf.format(amount)
    }

    private fun formatSignedMoney(amount: BigDecimal, currency: String, locale: Locale): String {
        val formatted = formatMoney(amount, currency, locale)
        return if (amount.signum() > 0) "+$formatted" else formatted
    }

    private fun formatSignedPercent(fraction: BigDecimal, locale: Locale): String {
        val pct = fraction.multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_EVEN)
        val nf = NumberFormat.getNumberInstance(locale)
        nf.minimumFractionDigits = 1
        nf.maximumFractionDigits = 1
        val num = nf.format(pct)
        return if (pct.signum() > 0) "+$num%" else "$num%"
    }

    private fun formatQuantity(qty: BigDecimal, locale: Locale): String {
        val clean = qty.stripTrailingZeros()
        val nf = NumberFormat.getNumberInstance(locale)
        nf.minimumFractionDigits = 0
        nf.maximumFractionDigits = 12
        return nf.format(clean)
    }

    internal fun formatTimestamp(instant: Instant, zone: java.time.ZoneId, locale: Locale): String {
        val pattern = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", locale)
        val formatted = pattern.format(ZonedDateTime.ofInstant(instant, zone))
        // Normalize CLDR Spanish abbreviated months ("jul." -> "jul", "sept." -> "sep") to match date-fns.
        return formatted
            .replace(Regex("(\\p{L})\\. "), "$1 ")
            .replace(" sept ", " sep ")
    }

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
        NotifyEntity.LENDING_PAYMENT -> "lending_payment"
        NotifyEntity.HOLDING -> "holding"
        NotifyEntity.RECURRING_TXN -> "recurring_txn"
        NotifyEntity.RECURRING_LENDING -> "recurring_lending"
        NotifyEntity.BANK_MOVEMENT -> "bank_movement"
        NotifyEntity.BANK_MOVEMENT_SPLIT -> "bank_movement_split"
        NotifyEntity.BANK_MOVEMENT_MERGE -> "bank_movement_merge"
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
            NotifyEntity.LENDING_PAYMENT -> "💸"
            NotifyEntity.HOLDING -> "📈"
            NotifyEntity.RECURRING_LENDING -> "📅"
            NotifyEntity.BANK_MOVEMENT -> "🏦"
            NotifyEntity.BANK_MOVEMENT_SPLIT -> "✂️"
            NotifyEntity.BANK_MOVEMENT_MERGE -> "🔗"
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
