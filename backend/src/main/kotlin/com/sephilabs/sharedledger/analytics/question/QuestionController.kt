package com.sephilabs.sharedledger.analytics.question

import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Any member. 404 without an AI provider, 409 `AI_FEATURES_DISABLED` with the setting off. */
@RestController
@RequestMapping("/api/households/{householdId}/questions")
class QuestionController(
    private val service: QuestionService,
    private val currentUser: CurrentUser,
) {

    @GetMapping("/example-category")
    fun exampleCategory(@PathVariable householdId: UUID): ExampleCategoryDto =
        service.exampleCategory(householdId, currentUser.requireUser())

    @PostMapping
    fun ask(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: QuestionRequest,
    ): QuestionAnswerDto = service.ask(householdId, body, currentUser.requireUser())
}
