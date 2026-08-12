package com.sephilabs.sharedledger.bank

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.transaction.Direction
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// --- Config / visibility ---------------------------------------------------------------------

/** Drives UI visibility. Everything waits for [credentialsConfigured] except the connections list, which
 *  stays visible while connections exist so the migration gap is explained rather than hidden. */
data class BankConfigDto(
    val credentialsConfigured: Boolean,
    val connectionCount: Long,
    // Upcoming background-sync fire times (absolute instants); the UI renders them in the viewer's zone.
    val nextSyncTimes: List<Instant> = emptyList(),
)

data class AspspDto(val name: String, val country: String, val logoUrl: String?)

// --- Household credentials (owner-only) -------------------------------------------------------

/** The private key is write-only — accepted on save, never returned. [redirectUrl] is what to register
 *  at Enable Banking. */
data class BankCredentialsDto(
    val appId: String?,
    val privateKeyConfigured: Boolean,
    val redirectUrl: String,
    val connectionCount: Long,
    // Connections authorized under a different (or unknown) application; they need re-linking.
    val mismatchedConnectionCount: Long,
)

data class BankCredentialsUpdateRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 128, message = "validation.invalid")
    val appId: String,
    // Blank/null keeps the stored key; any value replaces it (validated, then re-encrypted).
    val privateKey: String? = null,
    // Acknowledges the "N connections will need re-linking" warning; without it that save is refused.
    // Nullable so an omitted value doesn't break Jackson deserialization of this primitive
    // (see CategorizationRuleRequest.priority).
    val confirm: Boolean? = null,
)

data class BankCredentialsTestResult(val ok: Boolean, val message: String?)

// --- Connections -----------------------------------------------------------------------------

data class BankAccountDto(
    val id: UUID,
    val ibanMasked: String?,
    val name: String?,
    val currency: String?,
)

data class BankConnectionDto(
    val id: UUID,
    val provider: String,
    val aspspName: String,
    val aspspCountry: String,
    val label: String?,
    val status: ConnectionStatus,
    val consentExpiresAt: Instant?,
    val lastSyncedAt: Instant?,
    val ingestionEnabled: Boolean,
    val syncFrequency: SyncFrequency,
    val accounts: List<BankAccountDto>,
    val lastSyncStatus: SyncRunStatus?,
    val lastSyncError: String?,
    // Whether the caller may sync, re-link, edit or delete this connection: owners always, plus the
    // member who linked it. Every member sees every connection, but only manages their own.
    val canManage: Boolean = false,
)

data class StartLinkRequest(
    @field:NotBlank(message = "validation.required")
    val aspspName: String,
    @field:NotBlank(message = "validation.required")
    val country: String,
    @field:Size(max = 120)
    val label: String? = null,
    // Set when re-linking an existing (expired) connection instead of creating a new one.
    val relinkConnectionId: UUID? = null,
)

data class StartLinkResponse(val authUrl: String)

data class CompleteLinkRequest(
    @field:NotBlank(message = "validation.required")
    val code: String,
    @field:NotBlank(message = "validation.required")
    val state: String,
)

data class UpdateConnectionRequest(
    @field:Size(max = 120)
    val label: String? = null,
    val ingestionEnabled: Boolean? = null,
    val syncFrequency: SyncFrequency? = null,
)

// --- Pending movements (review inbox) --------------------------------------------------------

data class PendingMovementDto(
    val id: UUID,
    val connectionId: UUID,
    val connectionLabel: String?,
    val aspspName: String,
    val accountId: UUID,
    val accountName: String?,
    val bookingDate: LocalDate,
    val valueDate: LocalDate?,
    val direction: Direction,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val originalAmount: BigDecimal?,
    val originalCurrency: String?,
    val counterparty: String?,
    val description: String?,
    val reference: String?,
    val status: MovementStatus,
    val suggestedCategoryCode: String?,
    val createdTransactionId: UUID?,
    // Every transaction this item produced: one for a confirm, Replace or merge (where the merged items
    // share it), N for a split, empty while pending.
    val createdTransactionIds: List<UUID> = emptyList(),
    // Set instead of createdTransactionId when the item was confirmed as a net-worth movement.
    val createdMovementId: UUID?,
    // Warning only: a manual transaction looks like this movement (same date+amount, close desc).
    val possibleDuplicate: Boolean,
)

data class ConfirmMovementRequest(
    // Overrides the suggested category; required if there is no suggestion.
    val categoryCode: String? = null,
    val direction: Direction? = null,
    @field:Size(max = 500)
    val note: String? = null,
    // Remember counterparty -> category as a learned rule.
    val saveRule: Boolean = true,
)

/** Confirm a pending item as a net-worth movement instead of a transaction. Target rules mirror
 *  [com.sephilabs.sharedledger.networth.movement.MovementService.validateTarget]. The item's date and
 *  amount are reused; no category or learned rule is involved. */
data class ConfirmAsMovementRequest(
    @field:NotNull(message = "validation.required")
    val type: MovementType,
    val assetClassCode: String? = null,
    val liabilityId: UUID? = null,
    @field:Size(max = 500)
    val note: String? = null,
)

/** Split one movement into several transactions summing *exactly* to its total. One [direction] for every
 *  part: a single bank movement cannot be part income and part expense. */
data class SplitMovementRequest(
    @field:Valid
    @field:Size(min = 2, message = "validation.invalid")
    val parts: List<SplitPartRequest> = emptyList(),
    // One value for every part; null keeps the item's own direction.
    val direction: Direction? = null,
)

/** [description] falls back to the bank-derived one when blank, like a confirm's note. */
data class SplitPartRequest(
    @field:NotNull(message = "validation.required")
    @field:DecimalMin(value = "0.01", message = "validation.amount.positive")
    @field:Digits(integer = 13, fraction = 2, message = "validation.invalid")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    @field:NotBlank(message = "validation.required")
    val categoryCode: String,
    @field:Size(max = 500)
    val description: String? = null,
)

/** One item of a merge. Null [direction] keeps the movement's stored one; the inbox sends the row's
 *  (possibly flipped) draft, since it is what decides this item's sign in the net. */
data class MergeItemRequest(
    @field:NotNull(message = "validation.required")
    val id: UUID,
    val direction: Direction? = null,
)

/** Merge N items that are really one purchase (a bill split across cards, a tip charged separately, a
 *  charge and its partial refund) into a single transaction carrying their signed net — incomes add,
 *  expenses subtract — whose direction is the sign of that net. A selection netting to zero can produce no
 *  transaction; that is [CancelOutRequest]'s job. Null [date] takes the earliest booking date; blank
 *  [description] joins the bank-derived ones. */
data class MergeMovementsRequest(
    @field:Valid
    @field:Size(min = 2, message = "validation.invalid")
    val items: List<MergeItemRequest> = emptyList(),
    @field:NotBlank(message = "validation.required")
    val categoryCode: String = "",
    val date: LocalDate? = null,
    @field:Size(max = 500)
    val description: String? = null,
)

data class MergeResultDto(val transactionId: UUID, val mergedCount: Int)

/** The zero-net outcome of a merge: the items cancel each other out, so all of them are rejected and
 *  nothing is created. Carries the same items (with their directions) so the server can verify they
 *  really do cancel out rather than take the caller's word for it. */
data class CancelOutRequest(
    @field:Valid
    @field:Size(min = 2, message = "validation.invalid")
    val items: List<MergeItemRequest> = emptyList(),
)

/** A manual transaction a pending item could *replace* instead of duplicating. [bankLinked] ones already
 *  resolve another movement, so they can't be replaced — returned anyway so the dialog can say why. */
data class DuplicateCandidateDto(
    val transactionId: UUID,
    val occurrenceDate: LocalDate,
    val direction: Direction,
    val categoryCode: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    val description: String?,
    val recurringTemplateId: UUID?,
    val bankLinked: Boolean,
)

/** Null [categoryCode]/[direction] keep the transaction's own values; null [description] falls back to the
 *  bank-derived one. */
data class ReplaceTransactionRequest(
    @field:NotNull(message = "validation.required")
    val transactionId: UUID,
    val categoryCode: String? = null,
    val direction: Direction? = null,
    @field:Size(max = 500)
    val description: String? = null,
)

data class EditMovementRequest(
    val suggestedCategoryCode: String? = null,
    val direction: Direction? = null,
    @field:Size(max = 500)
    val description: String? = null,
)

data class BatchIdsRequest(
    @field:NotNull(message = "validation.required")
    val ids: List<UUID> = emptyList(),
)

/** One item in a batch confirm: its category (falls back to the movement's suggestion if null) and the
 *  row's description input as [note]. */
data class ConfirmBatchItem(
    @field:NotNull(message = "validation.required")
    val id: UUID,
    val categoryCode: String? = null,
    val direction: Direction? = null,
    @field:Size(max = 500)
    val note: String? = null,
)

data class ConfirmBatchRequest(
    @field:NotNull(message = "validation.required")
    val items: List<ConfirmBatchItem> = emptyList(),
)

data class BatchResultDto(
    val confirmed: Int = 0,
    val rejected: Int = 0,
    val restored: Int = 0,
    val skipped: List<UUID> = emptyList(),
)

data class PendingCountDto(val count: Long, val byConnection: List<PendingConnectionCountDto> = emptyList())

/** Pending inbox size of one bank connection; [label] falls back to the bank name when unlabelled. */
data class PendingConnectionCountDto(val connectionId: UUID, val label: String, val count: Long)

data class ApplyRulesResultDto(val categorized: Int = 0)

// --- Categorisation rules --------------------------------------------------------------------

data class CategorizationRuleDto(
    val id: UUID,
    val matchField: RuleField,
    val matchOp: RuleOp,
    val matchValue: String,
    val categoryCode: String,
    val direction: Direction,
    val priority: Int,
    val source: RuleSource,
    val createdAt: Instant,
)

data class DeletedCountDto(val deleted: Int)

data class CategorizationRuleRequest(
    @field:NotNull(message = "validation.required")
    val matchField: RuleField,
    @field:NotNull(message = "validation.required")
    val matchOp: RuleOp,
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 255)
    val matchValue: String,
    @field:NotBlank(message = "validation.required")
    val categoryCode: String,
    @field:NotNull(message = "validation.required")
    val direction: Direction,
    // Nullable so an omitted value doesn't break Jackson deserialization of this primitive;
    // the service defaults it. Lower wins.
    val priority: Int? = null,
)
