package com.sephilabs.sharedledger.notification

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The notifiable entity kinds, each mapping to one per-household toggle. */
enum class NotifyEntity { TRANSACTION, SNAPSHOT, MOVEMENT, LOAN_PAYMENT, HOLDING, RECURRING_TXN, RECURRING_LOAN }

/** The action performed on a notifiable entity. */
enum class NotifyAction { CREATE, UPDATE, DELETE }

/** Who triggered the change; drives the author line in the message. */
sealed interface NotifyActor {
    /** A household member acted directly. */
    data class Human(val email: String) : NotifyActor

    /** The recurring scheduler acted; the household owner's email is resolved in the listener. */
    data class Schedule(val householdId: UUID) : NotifyActor
}

/**
 * A single mobile-card line: a label (i18n key) and a typed value the formatter renders in the
 * household locale. Captured at publish time from the live entity so the async listener never
 * touches a JPA entity (no lazy-load / detached access, safe for soft+hard deletes).
 */
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

/**
 * Published by the mutation services for user-driven create/update/delete. Consumed after the
 * producing transaction commits (so a rolled-back write never notifies).
 *
 * NOTE: only the *Service.create/update/delete methods publish these. CSV import services write
 * directly to repositories and intentionally do not publish — that is how imports stay silent.
 */
data class EntityChangeEvent(
    val householdId: UUID,
    val entity: NotifyEntity,
    val action: NotifyAction,
    val actor: NotifyActor,
    val fields: List<CardField>,
)

/**
 * Published once per template/schedule after the scheduler (or a manual "fire now") materializes
 * N>0 occurrences. Aggregated into a single summary message to avoid floods on catch-up runs.
 */
data class MaterializationEvent(
    val householdId: UUID,
    val entity: NotifyEntity, // RECURRING_TXN or RECURRING_LOAN
    val count: Int,
    val actor: NotifyActor,
    val fields: List<CardField>,
)
