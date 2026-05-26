package com.sharedledger.recurring

import com.sharedledger.common.errors.AppException
import com.sharedledger.common.import.ExecuteResult
import com.sharedledger.common.import.PreviewSummary
import com.sharedledger.identity.auth.CurrentUser
import com.sharedledger.identity.auth.ImportRateLimiter
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}/recurring-templates/import")
class RecurringImportController(
    private val service: RecurringImportService,
    private val currentUser: CurrentUser,
    private val rateLimiter: ImportRateLimiter,
) {

    @PostMapping("/preview")
    fun preview(
        @PathVariable householdId: UUID,
        @RequestParam("file") file: MultipartFile,
    ): PreviewSummary {
        val user = currentUser.requireUser()
        if (!rateLimiter.tryAcquire(user.id)) throw AppException.tooManyRequests()
        if (file.isEmpty) throw AppException.badRequest("IMPORT_FILE_EMPTY")
        return file.inputStream.use { service.preview(householdId, it) }
    }

    @PostMapping("/execute")
    fun execute(
        @PathVariable householdId: UUID,
        @RequestParam("file") file: MultipartFile,
    ): ExecuteResult {
        val user = currentUser.requireUser()
        if (!rateLimiter.tryAcquire(user.id)) throw AppException.tooManyRequests()
        if (file.isEmpty) throw AppException.badRequest("IMPORT_FILE_EMPTY")
        return file.inputStream.use { service.execute(householdId, it, user) }
    }
}
