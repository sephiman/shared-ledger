package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.bank.connector.PsuContext
import com.sephilabs.sharedledger.bank.sync.BankSyncService
import com.sephilabs.sharedledger.bank.sync.SyncMode
import com.sephilabs.sharedledger.household.RequireHouseholdOwner
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.servlet.http.HttpServletRequest
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
        request: HttpServletRequest,
    ): BankConnectionDto =
        // The holder is online here — capture their PSU context so the initial full-history sync
        // runs as an interactive (not background-rate-limited) fetch.
        bankService.completeLink(householdId, body, currentUser.requireUser(), psuOf(request))

    @RequireHouseholdOwner
    @PostMapping("/connections/{id}/sync")
    fun sync(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        // Authorize the connection belongs to this household, then run off-thread (provider I/O),
        // returning 202 immediately. The result surfaces via the connection's status/last-sync.
        bankService.listConnections(householdId).firstOrNull { it.id == id }
            ?: return ResponseEntity.notFound().build()
        // User pressed "Sync now" → interactive incremental sync with their PSU context.
        syncService.enqueue(id, SyncMode.MANUAL, psuOf(request))
        return ResponseEntity.accepted().build()
    }

    /** Best-effort PSU context from the current request (first X-Forwarded-For hop, else remote addr). */
    private fun psuOf(request: HttpServletRequest): PsuContext {
        val ip = request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.ifBlank { null }
            ?: request.remoteAddr
        val userAgent = request.getHeader("User-Agent")?.ifBlank { null } ?: "SharedLedger"
        return PsuContext(ipAddress = ip, userAgent = userAgent)
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
