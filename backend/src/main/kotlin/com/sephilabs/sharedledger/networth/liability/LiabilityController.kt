package com.sephilabs.sharedledger.networth.liability

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.i18n.Messages
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class LiabilityCreateRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 120)
    val name: String,
    val type: LiabilityType = LiabilityType.other_debt,
    val active: Boolean = true,
    val countsTowardNetWorth: Boolean = true,
    val amortizable: Boolean = false,
    val chargeDay: Int? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val initialBalance: BigDecimal? = null,
    val initialBalanceDate: LocalDate? = null,
)

data class LiabilityUpdateRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 120)
    val name: String,
    val type: LiabilityType = LiabilityType.other_debt,
    val active: Boolean = true,
    val countsTowardNetWorth: Boolean = true,
    val amortizable: Boolean = false,
    val chargeDay: Int? = null,
)

data class LiabilityDto(
    val id: UUID,
    val name: String,
    val type: LiabilityType = LiabilityType.other_debt,
    val active: Boolean,
    val countsTowardNetWorth: Boolean = true,
    val amortizable: Boolean = false,
    val chargeDay: Int? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val latestBalance: BigDecimal? = null,
    val latestBalanceDate: LocalDate? = null,
    // Aggregated schedule figures for amortizable loans, so the list row shows them without opening.
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val computedBalance: BigDecimal? = null,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val computedInstalment: BigDecimal? = null,
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
    private val amortization: com.sephilabs.sharedledger.networth.amortization.AmortizationScheduleService,
    private val currentUser: CurrentUser,
    private val messages: Messages,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<LiabilityDto> =
        liabilities.findAllByHouseholdIdOrderByNameAsc(householdId).map { l ->
            val dto = l.toDto()
            if (l.amortizable) {
                val totals = amortization.totals(l)
                dto.copy(computedBalance = totals.currentBalance, computedInstalment = totals.monthlyInstalment)
            } else dto
        }

    @PostMapping
    @Transactional
    fun create(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: LiabilityCreateRequest,
    ): ResponseEntity<LiabilityDto> {
        val by = currentUser.requireUser()
        val today = LocalDate.now(clock)
        val initialDate = validateCreate(body, today)
        val liability = Liability(
            householdId = householdId,
            name = body.name.trim(),
            type = body.type,
            active = body.active,
            countsTowardNetWorth = body.countsTowardNetWorth,
            amortizable = body.amortizable,
            chargeDay = body.chargeDay,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        liabilities.saveAndFlush(liability)
        if (!body.amortizable) {
            val entry = LiabilityBalanceEntry(
                liabilityId = liability.id,
                balanceDate = initialDate!!,
                balance = Money.normalize(body.initialBalance!!),
                createdByUserId = by.id,
                updatedByUserId = by.id,
            )
            balances.saveAndFlush(entry)
        }
        log.info(
            "liability_created liability={} type={} active={} counts_toward_net_worth={} amortizable={}",
            liability.id, liability.type, liability.active, liability.countsTowardNetWorth, liability.amortizable,
        )
        return ResponseEntity.status(201).body(liability.toDto())
    }

    @PatchMapping("/{id}")
    @Transactional
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: LiabilityUpdateRequest,
    ): LiabilityDto {
        val by = currentUser.requireUser()
        validateChargeDay(body.amortizable, body.chargeDay)
        val liability = loadOwn(householdId, id)
        val wasActive = liability.active
        val wasAmortizable = liability.amortizable
        val wasCounted = liability.countsTowardNetWorth
        liability.name = body.name.trim()
        liability.type = body.type
        liability.active = body.active
        liability.countsTowardNetWorth = body.countsTowardNetWorth
        liability.amortizable = body.amortizable
        liability.chargeDay = body.chargeDay
        liability.updatedByUserId = by.id
        log.info(
            "liability_updated liability={} type={} active={}->{} counts_toward_net_worth={}->{} amortizable={}->{}",
            liability.id, liability.type, wasActive, liability.active, wasCounted, liability.countsTowardNetWorth, wasAmortizable, liability.amortizable,
        )
        return liability.toDto()
    }

    private fun validateCreate(body: LiabilityCreateRequest, today: LocalDate): LocalDate? {
        val fields = linkedMapOf<String, String>()
        collectChargeDayErrors(body.amortizable, body.chargeDay, fields)
        var initialDate: LocalDate? = null
        if (body.amortizable) {
            if (body.initialBalance != null) {
                fields["initialBalance"] = messages.resolve("validation.liability.initial_balance_not_allowed")
            }
            if (body.initialBalanceDate != null) {
                fields["initialBalanceDate"] = messages.resolve("validation.liability.initial_balance_not_allowed")
            }
        } else {
            val bal = body.initialBalance
            if (bal == null) {
                fields["initialBalance"] = messages.resolve("validation.required")
            } else if (bal <= BigDecimal.ZERO) {
                fields["initialBalance"] = messages.resolve("validation.amount.positive")
            }
            val date = body.initialBalanceDate ?: today
            if (date.isAfter(today)) {
                fields["initialBalanceDate"] = messages.resolve("validation.date.not_future")
            } else {
                initialDate = date
            }
        }
        if (fields.isNotEmpty()) throw AppException.badRequest("VALIDATION_FAILED", fields = fields)
        return initialDate
    }

    private fun validateChargeDay(amortizable: Boolean, chargeDay: Int?) {
        val fields = linkedMapOf<String, String>()
        collectChargeDayErrors(amortizable, chargeDay, fields)
        if (fields.isNotEmpty()) throw AppException.badRequest("VALIDATION_FAILED", fields = fields)
    }

    private fun collectChargeDayErrors(amortizable: Boolean, chargeDay: Int?, fields: MutableMap<String, String>) {
        if (chargeDay != null && chargeDay !in 1..31) {
            fields["chargeDay"] = messages.resolve("validation.liability.charge_day_invalid")
        } else if (amortizable && chargeDay == null) {
            // An amortizable loan advances on its charge day, so it must have one.
            fields["chargeDay"] = messages.resolve("validation.liability.charge_day_required")
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        val by = currentUser.requireUser()
        val liability = loadOwn(householdId, id)
        liability.deletedAt = Instant.now()
        liability.updatedByUserId = by.id
        log.info("liability_deleted liability={}", liability.id)
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
        val liability = loadOwn(householdId, id)
        if (liability.amortizable) {
            throw AppException.badRequest(
                "VALIDATION_FAILED",
                fields = mapOf("balance" to messages.resolve("validation.liability.manual_value_not_allowed")),
            )
        }
        val entry = LiabilityBalanceEntry(
            liabilityId = id,
            balanceDate = body.balanceDate,
            balance = Money.normalize(body.balance),
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        balances.save(entry)
        log.info("liability_value_added liability={} entry={}", id, entry.id)
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
        log.info("liability_value_updated liability={} entry={}", id, entry.id)
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
        log.info("liability_value_deleted liability={} entry={}", id, entry.id)
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
        return LiabilityDto(
            id = id,
            name = name,
            type = type,
            active = active,
            countsTowardNetWorth = countsTowardNetWorth,
            amortizable = amortizable,
            chargeDay = chargeDay,
            latestBalance = latest?.balance,
            latestBalanceDate = latest?.balanceDate,
        )
    }
}
