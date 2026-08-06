package com.sephilabs.sharedledger.notification

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The notifiable entity kinds, each mapping to one per-household toggle. */
enum class NotifyEntity {
    TRANSACTION, SNAPSHOT, MOVEMENT, LENDING_PAYMENT, HOLDING, RECURRING_TXN, RECURRING_LENDING,
    // Bank ingestion: BANK_MOVEMENT covers batch-confirm summaries, BANK_MOVEMENT_SPLIT the one-movement-
    // into-N-transactions summary (its own header, same user toggle), BANK_CONNECTION consent re-link
    // reminders.
    BANK_MOVEMENT, BANK_MOVEMENT_SPLIT, BANK_CONNECTION,
}

enum class NotifyAction { CREATE, UPDATE, DELETE }

/** Who triggered the change; drives the author line in the message. */
sealed interface NotifyActor {
    /** A household member acted directly. */
    data class Human(val email: String) : NotifyActor

    /** The recurring scheduler acted; the household owner's email is resolved in the listener. */
    data class Schedule(val householdId: UUID) : NotifyActor
}

/** One mobile-card line: an i18n label key and a typed value rendered in the household locale. Captured at
 *  publish time from the live entity, so the async listener never touches a JPA entity (no lazy-load or
 *  detached access; safe for soft and hard deletes). */
data class CardField(val labelKey: String, val value: FieldValue)

sealed interface FieldValue {
    /** Money rendered with the household currency + locale. */
    data class Money(val amount: BigDecimal) : FieldValue

    /** A date rendered localized (medium style). */
    data class Day(val date: LocalDate) : FieldValue

    /** Free text (escaped for Markdown); null/blank renders as an em dash. */
    data class Text(val text: String?) : FieldValue

    /** A category code — the formatter resolves its label and prepends the group emoji. */
    data class Category(val code: String) : FieldValue

    /** A value whose label is itself an i18n key (e.g. direction, movement type, cadence, asset class). */
    data class Keyed(val i18nKey: String) : FieldValue
}

/** Published by the mutation services for user-driven create/update/delete, consumed after the producing
 *  transaction commits. Only *Service.create/update/delete publish these — CSV imports write straight to
 *  repositories, which is how imports stay silent. */
data class EntityChangeEvent(
    val householdId: UUID,
    val entity: NotifyEntity,
    val action: NotifyAction,
    val actor: NotifyActor,
    val fields: List<CardField>,
)

/** Published once per template/schedule after the scheduler (or a manual "fire now") materializes N>0
 *  occurrences. Aggregated into one summary to avoid floods on catch-up runs. */
data class MaterializationEvent(
    val householdId: UUID,
    val entity: NotifyEntity, // RECURRING_TXN or RECURRING_LENDING
    val count: Int,
    val actor: NotifyActor,
    val fields: List<CardField>,
)
