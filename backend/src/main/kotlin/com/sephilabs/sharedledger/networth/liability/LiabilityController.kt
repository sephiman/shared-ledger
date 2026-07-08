package com.sephilabs.sharedledger.networth.liability

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

data class LiabilityRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 120)
    val name: String,
    val active: Boolean = true,
    val amortizable: Boolean = false,
    val chargeDay: Int? = null,
)

data class LiabilityDto(
    val id: UUID,
    val name: String,
    val active: Boolean,
    val amortizable: Boolean = false,
    val chargeDay: Int? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val latestBalance: BigDecimal? = null,
    val latestBalanceDate: LocalDate? = null,
)

data class LiabilityBalanceEntryRequest(
    @field:NotNull val balanceDate: LocalDate,
    @field:NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val balance: BigDecimal,
)

data class LiabilityBalanceEntryDto(
    val id: UUID,
    val balanceDate: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val balance: BigDecimal,
)

@RestController
@RequestMapping("/api/households/{householdId}/liabilities")
class LiabilityController(
    private val liabilities: LiabilityRepository,
    private val balances: LiabilityBalanceEntryRepository,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<LiabilityDto> =
        liabilities.findAllByHouseholdIdOrderByNameAsc(householdId).map { it.toDto() }

    @PostMapping
    @Transactional
    fun create(@PathVariable householdId: UUID, @Valid @RequestBody body: LiabilityRequest): ResponseEntity<LiabilityDto> {
        val by = currentUser.requireUser()
        validateAmortization(body)
        val liability = Liability(
            householdId = householdId,
            name = body.name.trim(),
            active = body.active,
            amortizable = body.amortizable,
            chargeDay = body.chargeDay,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        liabilities.save(liability)
        return ResponseEntity.status(201).body(liability.toDto())
    }

    @PatchMapping("/{id}")
    @Transactional
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: LiabilityRequest,
    ): LiabilityDto {
        val by = currentUser.requireUser()
        validateAmortization(body)
        val liability = loadOwn(householdId, id)
        liability.name = body.name.trim()
        liability.active = body.active
        liability.amortizable = body.amortizable
        liability.chargeDay = body.chargeDay
        liability.updatedByUserId = by.id
        return liability.toDto()
    }

    private fun validateAmortization(body: LiabilityRequest) {
        if (body.chargeDay != null && body.chargeDay !in 1..31) throw AppException.badRequest("LIABILITY_CHARGE_DAY_INVALID")
        // An amortizable loan advances on its charge day, so it must have one.
        if (body.amortizable && body.chargeDay == null) throw AppException.badRequest("LIABILITY_CHARGE_DAY_REQUIRED")
    }

    @DeleteMapping("/{id}")
    @Transactional
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        val by = currentUser.requireUser()
        val liability = loadOwn(householdId, id)
        liability.deletedAt = Instant.now()
        liability.updatedByUserId = by.id
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}/values")
    fun listValues(@PathVariable householdId: UUID, @PathVariable id: UUID): List<LiabilityBalanceEntryDto> {
        loadOwn(householdId, id)
        return balances.findAllByLiabilityIdOrderByBalanceDateDescCreatedAtDesc(id)
            .map { LiabilityBalanceEntryDto(it.id, it.balanceDate, it.balance) }
    }

    @PostMapping("/{id}/values")
    @Transactional
    fun addValue(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: LiabilityBalanceEntryRequest,
    ): ResponseEntity<LiabilityBalanceEntryDto> {
        val by = currentUser.requireUser()
        loadOwn(householdId, id)
        val entry = LiabilityBalanceEntry(
            liabilityId = id,
            balanceDate = body.balanceDate,
            balance = Money.normalize(body.balance),
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        balances.save(entry)
        return ResponseEntity.status(201).body(LiabilityBalanceEntryDto(entry.id, entry.balanceDate, entry.balance))
    }

    @PatchMapping("/{id}/values/{entryId}")
    @Transactional
    fun updateValue(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @PathVariable entryId: UUID,
        @Valid @RequestBody body: LiabilityBalanceEntryRequest,
    ): LiabilityBalanceEntryDto {
        val by = currentUser.requireUser()
        loadOwn(householdId, id)
        val entry = ownEntry(id, entryId)
        entry.balanceDate = body.balanceDate
        entry.balance = Money.normalize(body.balance)
        entry.updatedByUserId = by.id
        return LiabilityBalanceEntryDto(entry.id, entry.balanceDate, entry.balance)
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

    private fun ownEntry(liabilityId: UUID, entryId: UUID): LiabilityBalanceEntry {
        val entry = balances.findById(entryId).orElseThrow { AppException.notFound("LIABILITY_VALUE_NOT_FOUND") }
        if (entry.liabilityId != liabilityId) throw AppException.notFound("LIABILITY_VALUE_NOT_FOUND")
        return entry
    }

    private fun loadOwn(householdId: UUID, id: UUID): Liability {
        val liability = liabilities.findById(id).orElseThrow { AppException.notFound("LIABILITY_NOT_FOUND") }
        if (liability.householdId != householdId) throw AppException.notFound("LIABILITY_NOT_FOUND")
        return liability
    }

    private fun Liability.toDto(): LiabilityDto {
        val latest = balances.findFirstByLiabilityIdOrderByBalanceDateDescCreatedAtDesc(id)
        return LiabilityDto(id, name, active, amortizable, chargeDay, latest?.balance, latest?.balanceDate)
    }
}
