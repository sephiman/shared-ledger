package com.sephilabs.sharedledger.bank.aisuggestion

import com.sephilabs.sharedledger.household.RequireHouseholdOwner
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Every endpoint answers 404 when the instance has no AI provider, as if the feature did not exist. */
@RestController
@RequestMapping("/api/households/{householdId}")
class AiSuggestionController(
    private val settings: AiSuggestionSettingsService,
    private val service: AiSuggestionService,
    private val currentUser: CurrentUser,
) {

    @GetMapping("/ai-suggestions/settings")
    fun settings(@PathVariable householdId: UUID): AiSuggestionSettingsDto = settings.get(householdId)

    @RequireHouseholdOwner
    @PutMapping("/ai-suggestions/settings")
    fun updateSettings(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: AiSuggestionSettingsRequest,
    ): AiSuggestionSettingsDto = settings.update(householdId, body.enabled, currentUser.requireUser())

    @GetMapping("/banks/pending/ai-suggestions")
    fun status(@PathVariable householdId: UUID): AiSuggestionStatusDto = service.status(householdId)

    /** Any member may press it. Answers once the run is recorded; the inbox then polls [status]. */
    @PostMapping("/banks/pending/ai-suggestions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(@PathVariable householdId: UUID): AiSuggestionRunDto =
        service.start(householdId, currentUser.requireUser())
}
