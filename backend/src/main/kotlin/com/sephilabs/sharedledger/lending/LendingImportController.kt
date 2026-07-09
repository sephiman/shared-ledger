package com.sephilabs.sharedledger.lending

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.common.import.ExecuteResult
import com.sephilabs.sharedledger.common.import.PreviewSummary
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
@RequestMapping("/api/households/{householdId}/lendings/import")
class LendingImportController(
    private val service: LendingImportService,
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

@RestController
@RequestMapping("/api/households/{householdId}/lendings/payments/import")
class LendingPaymentImportController(
    private val service: LendingPaymentImportService,
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
