package com.sephilabs.sharedledger.analytics.review

import com.sephilabs.sharedledger.household.RequireHouseholdOwner
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Deterministic spending-review skeleton + underlying candidates endpoint (always available, no AI gate)
 *  and AI spending-review endpoints (404 without AI_API_KEY, 409 AI_FEATURES_DISABLED when toggle is off). */
@RestController
@RequestMapping("/api/households/{householdId}/analytics")
class AnalyticsAiReviewController(
    private val skeletonService: SpendingReviewSkeletonService,
    private val aiService: AnalyticsAiReviewService,
    private val currentUser: CurrentUser,
) {

    @GetMapping("/spending-review/candidates")
    fun candidates(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) month: Int?,
        @RequestParam(required = false) mode: String?,
    ): SpendingReviewCandidatesResponse =
        skeletonService.candidatesWithSkeleton(householdId, year, month, mode, currentUser.requireUser().locale)

    @GetMapping("/ai-review")
    fun list(@PathVariable householdId: UUID): List<AnalyticsAiRunDto> = aiService.list(householdId)

    /** Owner-only preview of the exact JSON payload bytes that will be sent to the AI provider. */
    @RequireHouseholdOwner
    @GetMapping("/ai-review/payload-preview")
    fun payloadPreview(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) month: Int?,
        @RequestParam(required = false) mode: String?,
    ): AnalyticsAiPayloadPreviewDto =
        aiService.previewPayload(householdId, year, month, mode, currentUser.requireUser().locale)

    @PostMapping("/ai-review")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(
        @PathVariable householdId: UUID,
        @RequestBody(required = false) body: StartAnalyticsAiReviewRequest?,
    ): AnalyticsAiRunDto = aiService.start(householdId, currentUser.requireUser(), body)

    @PostMapping("/ai-review/{runId}/followups")
    fun followup(
        @PathVariable householdId: UUID,
        @PathVariable runId: UUID,
        @RequestBody body: AnalyticsAiFollowupRequest,
    ): AnalyticsAiFollowupDto = aiService.askFollowup(householdId, runId, currentUser.requireUser(), body)

    @DeleteMapping("/ai-review/{runId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable householdId: UUID, @PathVariable runId: UUID) =
        aiService.delete(householdId, runId)
}
