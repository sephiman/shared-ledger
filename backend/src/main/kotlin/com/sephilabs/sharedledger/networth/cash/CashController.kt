package com.sephilabs.sharedledger.networth.cash

import com.fasterxml.jackson.annotation.JsonFormat
import com.sephilabs.sharedledger.common.Money
import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CashAdjustmentRequest(
    @field:NotNull val adjustmentDate: LocalDate,
    @field:NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
)

data class CashAdjustmentDto(
    val id: UUID,
    val adjustmentDate: LocalDate,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
    val source: CashAdjustmentSource,
)

data class OtherCashRequest(
    @field:NotNull
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val amount: BigDecimal,
)

data class BankCashAccountRequest(@field:NotNull val countsAsCash: Boolean)

data class BankCashAccountDto(
    val id: UUID,
    val bankName: String,
    val accountName: String?,
    val ibanMasked: String?,
    val currency: String?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val balance: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val balanceBase: BigDecimal?,
    val balanceType: String?,
    val balanceDate: LocalDate?,
    val countsAsCash: Boolean,
    val paused: Boolean,
)

data class BankCashDto(
    val accounts: List<BankCashAccountDto>,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val bankTotal: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val otherCash: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val total: BigDecimal,
)

data class CashEstimateDto(
    val date: LocalDate,
    val anchorDate: LocalDate?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val anchorAmount: BigDecimal?,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val netTransactions: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val netLendings: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val netMovements: BigDecimal,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val netFlows: BigDecimal,
    // Null when there is no adjustment yet (cash has no series; the UI falls back to carry-over).
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val estimate: BigDecimal?,
)

data class CashSettingsRequest(
    val includeTransactions: Boolean = true,
    val includeLendings: Boolean = true,
    val includeMovements: Boolean = true,
)

data class CashSettingsDto(
    val includeTransactions: Boolean,
    val includeLendings: Boolean,
    val includeMovements: Boolean,
)

@RestController
@RequestMapping("/api/households/{householdId}/cash")
class CashController(
    private val adjustments: CashAdjustmentRepository,
    private val estimates: CashEstimateService,
    private val bankCash: BankCashService,
    private val currentUser: CurrentUser,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/adjustments")
    fun listAdjustments(@PathVariable householdId: UUID): List<CashAdjustmentDto> =
        adjustments.findAllByHouseholdIdOrderByAdjustmentDateDescCreatedAtDesc(householdId)
            .map { it.toDto() }

    @PostMapping("/adjustments")
    @Transactional
    fun addAdjustment(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: CashAdjustmentRequest,
    ): ResponseEntity<CashAdjustmentDto> {
        val by = currentUser.requireUser()
        val entry = CashAdjustment(
            householdId = householdId,
            adjustmentDate = body.adjustmentDate,
            amount = Money.normalize(body.amount),
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        adjustments.save(entry)
        log.info("cash_adjustment_added adjustment={}", entry.id)
        return ResponseEntity.status(201).body(entry.toDto())
    }

    @PatchMapping("/adjustments/{id}")
    @Transactional
    fun updateAdjustment(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: CashAdjustmentRequest,
    ): CashAdjustmentDto {
        val by = currentUser.requireUser()
        val entry = loadOwn(householdId, id)
        // The next balance refresh would overwrite an edit; a bank anchor is corrected via other cash.
        if (entry.source == CashAdjustmentSource.bank) throw AppException.conflict("CASH_ADJUSTMENT_FROM_BANK")
        entry.adjustmentDate = body.adjustmentDate
        entry.amount = Money.normalize(body.amount)
        entry.updatedByUserId = by.id
        log.info("cash_adjustment_updated adjustment={}", entry.id)
        return entry.toDto()
    }

    @DeleteMapping("/adjustments/{id}")
    @Transactional
    fun deleteAdjustment(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        val by = currentUser.requireUser()
        val entry = loadOwn(householdId, id)
        entry.deletedAt = Instant.now()
        entry.updatedByUserId = by.id
        log.info("cash_adjustment_deleted adjustment={}", entry.id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/estimate")
    fun estimate(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
    ): CashEstimateDto {
        val e = estimates.estimateAt(householdId, date ?: LocalDate.now())
        return CashEstimateDto(
            date = e.date,
            anchorDate = e.anchorDate,
            anchorAmount = e.anchorAmount,
            netTransactions = e.flows.transactions,
            netLendings = e.flows.lendings,
            netMovements = e.flows.movements,
            netFlows = e.flows.net,
            estimate = e.estimate,
        )
    }

    @GetMapping("/settings")
    fun settings(@PathVariable householdId: UUID): CashSettingsDto =
        estimates.getOrCreateSettings(householdId).toDto()

    @PutMapping("/settings")
    fun updateSettings(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: CashSettingsRequest,
    ): CashSettingsDto =
        estimates.updateSettings(
            householdId,
            body.includeTransactions,
            body.includeLendings,
            body.includeMovements,
            currentUser.requireUser(),
        ).toDto()

    @GetMapping("/bank")
    fun bank(@PathVariable householdId: UUID): BankCashDto = bankCash.overview(householdId).toDto()

    @PutMapping("/other-cash")
    fun updateOtherCash(@PathVariable householdId: UUID, @Valid @RequestBody body: OtherCashRequest): BankCashDto =
        bankCash.setOtherCash(householdId, body.amount, currentUser.requireUser()).toDto()

    @PutMapping("/bank/accounts/{accountId}")
    fun updateBankAccount(
        @PathVariable householdId: UUID,
        @PathVariable accountId: UUID,
        @Valid @RequestBody body: BankCashAccountRequest,
    ): BankCashDto {
        currentUser.requireUser()
        return bankCash.setCountsAsCash(householdId, accountId, body.countsAsCash).toDto()
    }

    private fun loadOwn(householdId: UUID, id: UUID): CashAdjustment {
        val entry = adjustments.findById(id).orElseThrow { AppException.notFound("CASH_ADJUSTMENT_NOT_FOUND") }
        if (entry.householdId != householdId) throw AppException.notFound("CASH_ADJUSTMENT_NOT_FOUND")
        return entry
    }

    private fun CashAdjustment.toDto() = CashAdjustmentDto(id, adjustmentDate, amount, source)

    private fun BankCashOverview.toDto() = BankCashDto(
        accounts = accounts.map {
            BankCashAccountDto(
                id = it.account.id,
                bankName = it.connection.label ?: it.connection.aspspName,
                accountName = it.account.name,
                ibanMasked = it.account.ibanMasked,
                currency = it.account.balanceCurrency ?: it.account.currency,
                balance = it.account.balanceAmount,
                balanceBase = it.account.balanceAmountBase,
                balanceType = it.account.balanceType,
                balanceDate = it.account.balanceDate,
                countsAsCash = it.account.countsAsCash,
                paused = it.paused,
            )
        },
        bankTotal = bankTotal,
        otherCash = otherCash,
        total = total,
    )

    private fun CashEstimateSettings.toDto() =
        CashSettingsDto(includeTransactions, includeLendings, includeMovements)
}
