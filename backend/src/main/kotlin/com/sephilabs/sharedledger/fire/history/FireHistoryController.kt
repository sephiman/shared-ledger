package com.sephilabs.sharedledger.fire.history

import com.sephilabs.sharedledger.observability.AppMetrics
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/fire/history")
class FireHistoryController(
    private val service: FireHistoryService,
    private val metrics: AppMetrics,
) {

    @GetMapping
    fun history(@PathVariable householdId: UUID): FireHistoryResponse =
        metrics.analyticsTimer("fire_history").record<FireHistoryResponse> { service.history(householdId) }

    @GetMapping("/attribution")
    fun attribution(@PathVariable householdId: UUID, @RequestParam from: LocalDate): FireAttribution =
        service.attribution(householdId, from)
}
