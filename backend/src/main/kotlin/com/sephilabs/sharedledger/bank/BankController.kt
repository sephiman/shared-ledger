package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.bank.sync.BankSyncService
import com.sephilabs.sharedledger.household.RequireHouseholdOwner
import com.sephilabs.sharedledger.identity.auth.CurrentUser
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
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/banks")
class BankController(
    private val bankService: BankService,
    private val syncService: BankSyncService,
    private val currentUser: CurrentUser,
) {

    @GetMapping("/config")
    fun config(@PathVariable householdId: UUID): BankConfigDto = bankService.config(householdId)

    @GetMapping("/aspsps")
    fun aspsps(@PathVariable householdId: UUID, @RequestParam country: String): List<AspspDto> =
        bankService.listAspsps(country)

    @GetMapping("/connections")
    fun connections(@PathVariable householdId: UUID): List<BankConnectionDto> =
        bankService.listConnections(householdId)

    @RequireHouseholdOwner
    @PostMapping("/connections/start")
    fun startLink(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: StartLinkRequest,
    ): StartLinkResponse = bankService.startLink(householdId, body, currentUser.requireUser())

    @RequireHouseholdOwner
    @PostMapping("/connections/complete")
    fun completeLink(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: CompleteLinkRequest,
    ): BankConnectionDto = bankService.completeLink(householdId, body, currentUser.requireUser())

    @RequireHouseholdOwner
    @PostMapping("/connections/{id}/sync")
    fun sync(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        // Authorize the connection belongs to this household, then run off-thread (provider I/O),
        // returning 202 immediately. The result surfaces via the connection's status/last-sync.
        bankService.listConnections(householdId).firstOrNull { it.id == id }
            ?: return ResponseEntity.notFound().build()
        syncService.enqueue(id)
        return ResponseEntity.accepted().build()
    }

    @RequireHouseholdOwner
    @PatchMapping("/connections/{id}")
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: UpdateConnectionRequest,
    ): BankConnectionDto = bankService.update(householdId, id, body, currentUser.requireUser())

    @RequireHouseholdOwner
    @DeleteMapping("/connections/{id}")
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        bankService.delete(householdId, id)
        return ResponseEntity.noContent().build()
    }
}
