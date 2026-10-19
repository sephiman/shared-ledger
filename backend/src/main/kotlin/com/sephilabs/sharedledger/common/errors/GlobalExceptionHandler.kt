package com.sephilabs.sharedledger.common.errors

import com.sephilabs.sharedledger.i18n.Messages
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import tools.jackson.core.JacksonException
import tools.jackson.databind.exc.InvalidNullException

data class ApiError(
    val code: String,
    val message: String,
    val fields: Map<String, String>? = null,
    val args: List<String>? = null,
)

@RestControllerAdvice
class GlobalExceptionHandler(private val messages: Messages) {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(AppException::class)
    fun handleAppException(ex: AppException): ResponseEntity<ApiError> {
        val message = messages.resolve(ex.code, ex.args, ex.fallbackMessage)
        val args = ex.args.map { it.toString() }.takeIf { it.isNotEmpty() }
        return ResponseEntity.status(ex.httpStatus).body(ApiError(ex.code, message, ex.fields, args))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val fields = ex.bindingResult.fieldErrors.associate { fe ->
            val key = fe.defaultMessage ?: "validation.invalid"
            fe.field to messages.resolve(key, emptyArray(), key)
        }
        return ResponseEntity.badRequest().body(
            ApiError(
                code = "VALIDATION_FAILED",
                message = messages.resolve("VALIDATION_FAILED", emptyArray(), "Validation failed"),
                fields = fields,
            )
        )
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ApiError> {
        val fields = ex.constraintViolations.associate { v ->
            val key = v.message
            v.propertyPath.toString() to messages.resolve(key, emptyArray(), key)
        }
        return ResponseEntity.badRequest().body(
            ApiError(
                code = "VALIDATION_FAILED",
                message = messages.resolve("VALIDATION_FAILED", emptyArray(), "Validation failed"),
                fields = fields,
            )
        )
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ApiError> {
        return ResponseEntity.badRequest().body(
            ApiError(
                code = "INVALID_PARAMETER",
                message = messages.resolve("INVALID_PARAMETER", arrayOf(ex.name), "Invalid parameter: ${ex.name}"),
            )
        )
    }

    /** A body Jackson could bind no further: a named field is a 400 the client can act on, broken JSON is a
     *  MALFORMED_BODY. Required-and-missing and required-and-null both arrive as [InvalidNullException]. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ResponseEntity<ApiError> {
        val cause = ex.mostSpecificCause as? JacksonException
        val field = cause?.let(::fieldPath)
        if (field == null) {
            return ResponseEntity.badRequest().body(
                ApiError("MALFORMED_BODY", messages.resolve("MALFORMED_BODY", emptyArray(), "Malformed request body"))
            )
        }
        val reason = if (cause is InvalidNullException) "validation.required" else "validation.invalid"
        return ResponseEntity.badRequest().body(
            ApiError(
                code = "INVALID_PARAMETER",
                message = messages.resolve("INVALID_PARAMETER", arrayOf(field), "Invalid parameter: $field"),
                fields = mapOf(field to messages.resolve(reason, emptyArray(), reason)),
                args = listOf(field),
            )
        )
    }

    /** "items[0].year" from Jackson's reference chain; null when the failure is not tied to a property. */
    private fun fieldPath(ex: JacksonException): String? {
        val path = ex.path.joinToString("") { ref ->
            when {
                ref.propertyName != null -> ".${ref.propertyName}"
                ref.index >= 0 -> "[${ref.index}]"
                else -> ""
            }
        }.removePrefix(".")
        return path.ifEmpty { null }
    }

    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentials(ex: BadCredentialsException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiError("INVALID_CREDENTIALS", messages.resolve("INVALID_CREDENTIALS", emptyArray(), "Invalid credentials"))
        )

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuth(ex: AuthenticationException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiError("UNAUTHORIZED", messages.resolve("UNAUTHORIZED", emptyArray(), "Authentication required"))
        )

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiError("FORBIDDEN", messages.resolve("FORBIDDEN", emptyArray(), "Access denied"))
        )

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ApiError> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiError(
                code = "DATA_INTEGRITY_VIOLATION",
                message = messages.resolve("DATA_INTEGRITY_VIOLATION", emptyArray(), "Conflict with existing data"),
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiError("INTERNAL_ERROR", messages.resolve("INTERNAL_ERROR", emptyArray(), "Unexpected error"))
        )
    }
}
