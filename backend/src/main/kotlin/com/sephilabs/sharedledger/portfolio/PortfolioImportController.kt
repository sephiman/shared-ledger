package com.sephilabs.sharedledger.portfolio

import com.sephilabs.sharedledger.common.import.ExecuteResult
import com.sephilabs.sharedledger.common.import.PreviewSummary
import com.sephilabs.sharedledger.common.import.guarded
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import com.sephilabs.sharedledger.identity.auth.ImportRateLimiter
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/portfolio/import")
class PortfolioImportController(
    private val service: PortfolioImportService,
    private val currentUser: CurrentUser,
    private val rateLimiter: ImportRateLimiter,
) {

    @PostMapping("/preview")
    fun preview(
        @PathVariable householdId: UUID,
        @RequestParam("file") file: MultipartFile,
    ): PreviewSummary = rateLimiter.guarded(currentUser, file) { _, input -> service.preview(householdId, input) }

    @PostMapping("/execute")
    fun execute(
        @PathVariable householdId: UUID,
        @RequestParam("file") file: MultipartFile,
    ): ExecuteResult = rateLimiter.guarded(currentUser, file) { user, input -> service.execute(householdId, input, user) }
}
