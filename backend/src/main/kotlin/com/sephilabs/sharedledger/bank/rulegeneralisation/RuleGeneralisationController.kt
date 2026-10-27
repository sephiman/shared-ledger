package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Any member, like writing a rule. 404 without an AI provider, 409 `AI_SUGGESTIONS_DISABLED` with the setting off. */
@RestController
@RequestMapping("/api/households/{householdId}/banks/rules/generalisations")
class RuleGeneralisationController(
    private val service: RuleGeneralisationService,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun status(@PathVariable householdId: UUID): RuleGeneralisationStatusDto = service.status(householdId)

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(@PathVariable householdId: UUID): RuleGeneralisationRunDto =
        service.start(householdId, currentUser.requireUser())

    /** A dry run of accept, for a pattern the member is editing. Always 200: the verdict is the body. */
    @PostMapping("/validate")
    fun validate(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: GeneralisationRequest,
    ): ProposalCheckDto = service.validate(householdId, body)

    @PostMapping("/accept")
    fun accept(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: GeneralisationRequest,
    ): AcceptedGeneralisationDto = service.accept(householdId, body, currentUser.requireUser())
}
