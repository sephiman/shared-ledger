package com.sharedledger.identity.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {

    fun findByEmailIgnoreCase(email: String): User?

    fun existsByEmailIgnoreCase(email: String): Boolean

    fun existsByDefaultHouseholdId(defaultHouseholdId: UUID): Boolean
}
