package com.sephilabs.sharedledger.bank.aisuggestion

import java.time.Instant
import java.util.UUID

/** [model] and [providerHost] name where data goes, for the setting's help text. */
data class AiSuggestionSettingsDto(
    val enabled: Boolean,
    val model: String,
    val providerHost: String,
)

data class AiSuggestionSettingsRequest(val enabled: Boolean)

/** Token figures stay out on purpose: they are for the operator's logs, not the inbox. */
data class AiSuggestionRunDto(
    val id: UUID,
    val status: AiSuggestionRunStatus,
    val batchesTotal: Int,
    val batchesDone: Int,
    val batchesFailed: Int,
    val itemsSent: Int,
    val suggestionsStored: Int,
    val itemsUnsuggested: Int,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

/** What the inbox polls: how many items a press would send, and the latest run (null before the first). */
data class AiSuggestionStatusDto(
    val candidates: Long,
    val run: AiSuggestionRunDto?,
)

fun AiSuggestionRun.toDto() = AiSuggestionRunDto(
    id = id,
    status = status,
    batchesTotal = batchesTotal,
    batchesDone = batchesDone,
    batchesFailed = batchesFailed,
    itemsSent = itemsSent,
    suggestionsStored = suggestionsStored,
    itemsUnsuggested = itemsUnsuggested,
    startedAt = startedAt,
    finishedAt = finishedAt,
)
