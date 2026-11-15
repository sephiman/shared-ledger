package com.sephilabs.sharedledger.portfolio.alert

import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/portfolio/alerts")
class PriceAlertController(
    private val service: PriceAlertService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) holdingId: UUID?,
    ): List<PriceAlertDto> = service.list(householdId, holdingId)

    @PostMapping
    fun create(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: CreatePriceAlertRequest,
    ): ResponseEntity<PriceAlertDto> =
        ResponseEntity.status(201).body(service.create(householdId, body, currentUser.requireUser()))

    @PutMapping("/{alertId}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable alertId: UUID,
        @Valid @RequestBody body: UpdatePriceAlertRequest,
    ): PriceAlertDto = service.update(householdId, alertId, body, currentUser.requireUser())

    @PostMapping("/{alertId}/cancel")
    fun cancel(
        @PathVariable householdId: UUID,
        @PathVariable alertId: UUID,
    ): PriceAlertDto = service.cancel(householdId, alertId)

    @DeleteMapping("/{alertId}")
    fun delete(
        @PathVariable householdId: UUID,
        @PathVariable alertId: UUID,
    ): ResponseEntity<Void> {
        service.delete(householdId, alertId)
        return ResponseEntity.noContent().build()
    }
}
