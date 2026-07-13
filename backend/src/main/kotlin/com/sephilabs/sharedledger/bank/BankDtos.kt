package com.sephilabs.sharedledger.bank

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.networth.movement.MovementType
import com.sephilabs.sharedledger.transaction.Direction
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// --- Config / visibility ---------------------------------------------------------------------

/** Drives the three-level UI visibility: feature toggle (env) + how many banks are linked. */
data class BankConfigDto(
    val featureEnabled: Boolean,
    val connectionCount: Long,
    // Upcoming background-sync fire times (absolute instants); the UI renders them in the viewer's zone.
    val nextSyncTimes: List<Instant> = emptyList(),
)

data class AspspDto(val name: String, val country: String, val logoUrl: String?)

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

/**
 * Confirm a pending item as a net-worth movement (capital reallocation) instead of a transaction.
 * The target rules mirror [com.sephilabs.sharedledger.networth.movement.MovementService.validateTarget]:
 * contribution/withdrawal require [assetClassCode], debt_payment requires [liabilityId]. The item's
 * date and amount are reused; no category or learned rule is involved.
 */
data class ConfirmAsMovementRequest(
    @field:NotNull(message = "validation.required")
    val type: MovementType,
    val assetClassCode: String? = null,
    val liabilityId: UUID? = null,
    @field:Size(max = 500)
    val note: String? = null,
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

/** One item in a batch confirm: its category (falls back to the movement's suggestion if null). */
data class ConfirmBatchItem(
    @field:NotNull(message = "validation.required")
    val id: UUID,
    val categoryCode: String? = null,
    val direction: Direction? = null,
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

data class PendingCountDto(val count: Long)

/** Result of running categorisation rules over the uncategorized pending inbox. */
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

/** Result of a bulk rule delete. */
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
