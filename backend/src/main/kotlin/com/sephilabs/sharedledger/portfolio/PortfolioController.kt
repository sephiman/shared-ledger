package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.common.Csv
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.getOrThrow
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import com.sephilabs.sharedledger.portfolio.price.SymbolCandidate
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/portfolio")
class PortfolioController(
    private val service: HoldingService,
    private val valuation: PortfolioValuationService,
    private val symbolSearch: SymbolSearchService,
    private val currentUser: CurrentUser,
    private val households: HouseholdRepository,
) {

    @GetMapping("/export.csv")
    fun exportCsv(@PathVariable householdId: UUID): ResponseEntity<String> {
        val csv = service.exportCsv(householdId)
        return Csv.download(households.getOrThrow(householdId).name, "portfolio", csv)
    }

    @GetMapping("/symbol-search")
    fun searchSymbols(
        @PathVariable householdId: UUID,
        @RequestParam assetClass: HoldingAssetClass,
        @RequestParam q: String,
    ): List<SymbolCandidate> = symbolSearch.search(assetClass, q)

    @GetMapping("/summary")
    fun summary(@PathVariable householdId: UUID): PortfolioSummaryDto = valuation.summary(householdId)

    @GetMapping("/valuation")
    fun valuationAt(
        @PathVariable householdId: UUID,
        @RequestParam date: LocalDate,
    ): PortfolioValuationDto = valuation.valuationAt(householdId, date)

    @GetMapping("/evolution")
    fun evolution(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) assetClass: HoldingAssetClass?,
        @RequestParam(required = false) holdingId: UUID?,
    ): PortfolioEvolutionDto = valuation.evolution(householdId, from, to, assetClass, holdingId)

    @GetMapping("/holdings")
    fun list(@PathVariable householdId: UUID): List<HoldingDto> = service.list(householdId)

    @GetMapping("/holdings/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): HoldingDto =
        service.get(householdId, id)

    @PostMapping("/holdings")
    fun create(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: HoldingRequest,
    ): ResponseEntity<HoldingDto> =
        ResponseEntity.status(201).body(service.create(householdId, body, currentUser.requireUser()))

    @PatchMapping("/holdings/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: HoldingUpdateRequest,
    ): HoldingDto = service.update(householdId, id, body, currentUser.requireUser())

    @DeleteMapping("/holdings/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(householdId, id, currentUser.requireUser())
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/holdings/{id}/link")
    fun link(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: LinkRequest,
    ): HoldingDto = service.link(householdId, id, body, currentUser.requireUser())

    @PostMapping("/holdings/{id}/unlink")
    fun unlink(@PathVariable householdId: UUID, @PathVariable id: UUID): HoldingDto =
        service.unlink(householdId, id, currentUser.requireUser())

    @PostMapping("/holdings/{id}/lots")
    fun addLot(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: LotRequest,
    ): ResponseEntity<LotDto> =
        ResponseEntity.status(201).body(service.addLot(householdId, id, body, currentUser.requireUser()))

    @PatchMapping("/holdings/{id}/lots/{lotId}")
    fun updateLot(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @PathVariable lotId: UUID,
        @Valid @RequestBody body: LotRequest,
    ): LotDto = service.updateLot(householdId, id, lotId, body, currentUser.requireUser())

    @DeleteMapping("/holdings/{id}/lots/{lotId}")
    fun deleteLot(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @PathVariable lotId: UUID,
    ): ResponseEntity<Void> {
        service.deleteLot(householdId, id, lotId, currentUser.requireUser())
        return ResponseEntity.noContent().build()
    }
}
