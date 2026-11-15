package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/fire/question")
class FireQuestionController(
    private val service: FireQuestionService,
    private val currentUser: CurrentUser,
) {

    @PostMapping
    fun ask(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: FireQuestionRequest,
    ): FireQuestionAnswerDto = service.ask(householdId, body, currentUser.requireUser())
}
