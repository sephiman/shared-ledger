package com.sephilabs.sharedledger.networth.snapshot

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class AssetValueInput(
    val assetClassCode: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val value: BigDecimal,
)

data class LiabilityBalanceInput(
    val liabilityId: UUID,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val balance: BigDecimal,
)

data class SnapshotRequest(
    @field:NotNull val snapshotDate: LocalDate,
    val note: String? = null,
    val assets: List<AssetValueInput>,
    val liabilities: List<LiabilityBalanceInput>,
    val confirmLargeChanges: Boolean = false,
)

data class AssetValueDto(
    val assetClassCode: String,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val value: BigDecimal,
)

data class LiabilityBalanceDto(
    val liabilityId: UUID,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val balance: BigDecimal,
)

data class SnapshotDto(
    val id: UUID,
    val snapshotDate: LocalDate,
    val note: String?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalAssets: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalLiabilities: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val netWorth: BigDecimal,
    val assets: List<AssetValueDto>,
    val liabilities: List<LiabilityBalanceDto>,
    val createdAt: Instant,
)

data class PrefillView(
    val previous: SnapshotDto?,
    val activeLiabilities: List<UUID>,
)

data class EvolutionPoint(
    val snapshotDate: LocalDate,
    val byClass: Map<String, BigDecimal>,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalAssets: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val totalLiabilities: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val netWorth: BigDecimal,
)
