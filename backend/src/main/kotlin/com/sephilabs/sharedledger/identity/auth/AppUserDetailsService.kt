package com.sephilabs.sharedledger.identity.auth

import com.sephilabs.sharedledger.identity.user.UserRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User as SpringUser
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class AppUserDetailsService(private val users: UserRepository) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        val user = users.findByEmailIgnoreCase(username)
            ?: throw UsernameNotFoundException("user not found")
        return SpringUser.withUsername(user.email)
            .password(user.passwordHash)
            .authorities(listOf(SimpleGrantedAuthority("ROLE_USER")))
            .accountLocked(false)
            .build()
    }
}
