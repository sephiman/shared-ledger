package com.sephilabs.sharedledger.features

import com.sephilabs.sharedledger.bank.aisuggestion.AiAvailability
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Instance capabilities for signed-in users. Kept apart from the public `/auth/features` so an anonymous
 *  caller learns nothing about which integrations this instance has. */
data class InstanceFeaturesDto(val aiCategorySuggestions: Boolean)

@RestController
@RequestMapping("/api/features")
class FeaturesController(private val ai: AiAvailability) {

    @GetMapping
    fun features(): InstanceFeaturesDto = InstanceFeaturesDto(aiCategorySuggestions = ai.enabled)
}
