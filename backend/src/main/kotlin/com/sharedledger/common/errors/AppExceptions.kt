package com.sharedledger.common.errors

class AppException(
    val code: String,
    val httpStatus: Int,
    val args: Array<out Any> = emptyArray(),
    val fields: Map<String, String>? = null,
    val fallbackMessage: String = code,
    cause: Throwable? = null,
) : RuntimeException(fallbackMessage, cause) {

    companion object {
        fun notFound(code: String = "NOT_FOUND", vararg args: Any): AppException =
            AppException(code = code, httpStatus = 404, args = args)

        fun forbidden(code: String = "FORBIDDEN", vararg args: Any): AppException =
            AppException(code = code, httpStatus = 403, args = args)

        fun unauthorized(code: String = "UNAUTHORIZED", vararg args: Any): AppException =
            AppException(code = code, httpStatus = 401, args = args)

        fun badRequest(code: String, vararg args: Any, fields: Map<String, String>? = null): AppException =
            AppException(code = code, httpStatus = 400, args = args, fields = fields)

        fun conflict(code: String, vararg args: Any): AppException =
            AppException(code = code, httpStatus = 409, args = args)

        fun tooManyRequests(code: String = "RATE_LIMITED", vararg args: Any): AppException =
            AppException(code = code, httpStatus = 429, args = args)
    }
}
