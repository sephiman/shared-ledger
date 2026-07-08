package com.sephilabs.sharedledger.networth.asset

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class AssetRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 120)
    val name: String,
    @field:NotNull
    val type: AssetType = AssetType.other,
    val active: Boolean = true,
)

data class AssetDto(
    val id: UUID,
    val name: String,
    val type: AssetType,
    val active: Boolean,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val latestValue: BigDecimal?,
    val latestValueDate: LocalDate?,
)

data class AssetValueEntryRequest(
    @field:NotNull val valueDate: LocalDate,
    @field:NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val value: BigDecimal,
)

data class AssetValueEntryDto(
    val id: UUID,
    val valueDate: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val value: BigDecimal,
)

@RestController
@RequestMapping("/api/households/{householdId}/assets")
class AssetController(
    private val assets: AssetRepository,
    private val values: AssetValueEntryRepository,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<AssetDto> =
        assets.findAllByHouseholdIdOrderByNameAsc(householdId).map { it.toDto() }

    @PostMapping
    @Transactional
    fun create(@PathVariable householdId: UUID, @Valid @RequestBody body: AssetRequest): ResponseEntity<AssetDto> {
        val by = currentUser.requireUser()
        val asset = Asset(
            householdId = householdId,
            name = body.name.trim(),
            type = body.type,
            active = body.active,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        assets.save(asset)
        return ResponseEntity.status(201).body(asset.toDto())
    }

    @PatchMapping("/{id}")
    @Transactional
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: AssetRequest,
    ): AssetDto {
        val by = currentUser.requireUser()
        val asset = loadOwn(householdId, id)
        asset.name = body.name.trim()
        asset.type = body.type
        asset.active = body.active
        asset.updatedByUserId = by.id
        return asset.toDto()
    }

    @DeleteMapping("/{id}")
    @Transactional
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        val by = currentUser.requireUser()
        val asset = loadOwn(householdId, id)
        asset.deletedAt = Instant.now()
        asset.updatedByUserId = by.id
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}/values")
    fun listValues(@PathVariable householdId: UUID, @PathVariable id: UUID): List<AssetValueEntryDto> {
        loadOwn(householdId, id)
        return values.findAllByAssetIdOrderByValueDateDescCreatedAtDesc(id)
            .map { AssetValueEntryDto(it.id, it.valueDate, it.value) }
    }

    @PostMapping("/{id}/values")
    @Transactional
    fun addValue(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: AssetValueEntryRequest,
    ): ResponseEntity<AssetValueEntryDto> {
        val by = currentUser.requireUser()
        loadOwn(householdId, id)
        val entry = AssetValueEntry(
            assetId = id,
            valueDate = body.valueDate,
            value = Money.normalize(body.value),
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        values.save(entry)
        return ResponseEntity.status(201).body(AssetValueEntryDto(entry.id, entry.valueDate, entry.value))
    }

    @PatchMapping("/{id}/values/{entryId}")
    @Transactional
    fun updateValue(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @PathVariable entryId: UUID,
        @Valid @RequestBody body: AssetValueEntryRequest,
    ): AssetValueEntryDto {
        val by = currentUser.requireUser()
        loadOwn(householdId, id)
        val entry = ownEntry(id, entryId)
        entry.valueDate = body.valueDate
        entry.value = Money.normalize(body.value)
        entry.updatedByUserId = by.id
        return AssetValueEntryDto(entry.id, entry.valueDate, entry.value)
    }

    @DeleteMapping("/{id}/values/{entryId}")
    @Transactional
    fun deleteValue(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @PathVariable entryId: UUID,
    ): ResponseEntity<Void> {
        val by = currentUser.requireUser()
        loadOwn(householdId, id)
        val entry = ownEntry(id, entryId)
        entry.deletedAt = Instant.now()
        entry.updatedByUserId = by.id
        return ResponseEntity.noContent().build()
    }

    private fun ownEntry(assetId: UUID, entryId: UUID): AssetValueEntry {
        val entry = values.findById(entryId).orElseThrow { AppException.notFound("ASSET_VALUE_NOT_FOUND") }
        if (entry.assetId != assetId) throw AppException.notFound("ASSET_VALUE_NOT_FOUND")
        return entry
    }

    private fun loadOwn(householdId: UUID, id: UUID): Asset {
        val asset = assets.findById(id).orElseThrow { AppException.notFound("ASSET_NOT_FOUND") }
        if (asset.householdId != householdId) throw AppException.notFound("ASSET_NOT_FOUND")
        return asset
    }

    private fun Asset.toDto(): AssetDto {
        val latest = values.findFirstByAssetIdOrderByValueDateDescCreatedAtDesc(id)
        return AssetDto(id, name, type, active, latest?.value, latest?.valueDate)
    }
}
