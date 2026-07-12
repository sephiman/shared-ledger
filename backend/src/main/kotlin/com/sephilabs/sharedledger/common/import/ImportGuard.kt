package com.sephilabs.sharedledger.common.import

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import com.sephilabs.sharedledger.identity.auth.ImportRateLimiter
import com.sephilabs.sharedledger.identity.user.User
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream

/**
 * Shared guard for every CSV-import endpoint: authenticate, rate-limit, reject empty
 * uploads, then hand the caller the resolved user and the open file stream.
 */
fun <T> ImportRateLimiter.guarded(
    currentUser: CurrentUser,
    file: MultipartFile,
    block: (user: User, input: InputStream) -> T,
): T {
    val user = currentUser.requireUser()
    if (!tryAcquire(user.id)) throw AppException.tooManyRequests()
    if (file.isEmpty) throw AppException.badRequest("IMPORT_FILE_EMPTY")
    return file.inputStream.use { block(user, it) }
}
