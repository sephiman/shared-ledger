package com.sephilabs.sharedledger.common.errors

import com.sephilabs.sharedledger.i18n.Messages
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class ApiError(
    val code: String,
    val message: String,
    val fields: Map<String, String>? = null,
)

@RestControllerAdvice
class GlobalExceptionHandler(private val messages: Messages) {

    @ExceptionHandler(AppException::class)
    fun handleAppException(ex: AppException): ResponseEntity<ApiError> {
        val message = messages.resolve(ex.code, ex.args, ex.fallbackMessage)
        return ResponseEntity.status(ex.httpStatus).body(ApiError(ex.code, message, ex.fields))
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
    fun handleGeneric(ex: Exception): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiError("INTERNAL_ERROR", messages.resolve("INTERNAL_ERROR", emptyArray(), "Unexpected error"))
        )
}
