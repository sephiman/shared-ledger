package com.sephilabs.sharedledger.fire.levers

import com.sephilabs.sharedledger.observability.AppMetrics
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/fire/levers")
class FireLeversController(
    private val service: FireLeversService,
    private val metrics: AppMetrics,
) {

    @GetMapping
    fun levers(@PathVariable householdId: UUID): FireLeversResponse =
        metrics.analyticsTimer("fire_levers").record<FireLeversResponse> { service.levers(householdId) }
}
