package com.sharedledger.identity.auth

import com.sharedledger.common.errors.AppException
import com.sharedledger.identity.user.User
import com.sharedledger.identity.user.UserRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Component

@Component
class CurrentUser(private val users: UserRepository) {

    fun requireUser(): User {
        val auth = SecurityContextHolder.getContext().authentication
            ?: throw AppException.unauthorized()
        val principal = auth.principal
        val email = when (principal) {
            is UserDetails -> principal.username
            is String -> principal
            else -> throw AppException.unauthorized()
        }
        return users.findByEmailIgnoreCase(email) ?: throw AppException.unauthorized()
    }

    fun currentUserOrNull(): User? {
        val auth = SecurityContextHolder.getContext().authentication ?: return null
        val principal = auth.principal
        val email = when (principal) {
            is UserDetails -> principal.username
            is String -> if (principal == "anonymousUser") return null else principal
            else -> return null
        }
        return users.findByEmailIgnoreCase(email)
    }
}
