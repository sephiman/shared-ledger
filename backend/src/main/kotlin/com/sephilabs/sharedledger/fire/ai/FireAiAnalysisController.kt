package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.identity.auth.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Every endpoint answers 404 without a provider and 409 `AI_FEATURES_DISABLED` when the household setting is off. */
@RestController
@RequestMapping("/api/households/{householdId}/fire/ai-analysis")
class FireAiAnalysisController(
    private val service: FireAiAnalysisService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<FireAiRunDto> = service.list(householdId)

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(@PathVariable householdId: UUID): FireAiRunDto =
        service.start(householdId, currentUser.requireUser())

    @PostMapping("/{runId}/followups")
    fun followup(
        @PathVariable householdId: UUID,
        @PathVariable runId: UUID,
        @RequestBody body: FireAiFollowupRequest,
    ): FireAiFollowupDto = service.askFollowup(householdId, runId, currentUser.requireUser(), body)

    @DeleteMapping("/{runId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable householdId: UUID, @PathVariable runId: UUID) =
        service.delete(householdId, runId)
}
