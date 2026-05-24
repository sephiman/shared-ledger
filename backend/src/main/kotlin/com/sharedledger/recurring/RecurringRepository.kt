package com.sharedledger.recurring

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RecurringTemplateRepository : JpaRepository<RecurringTemplate, UUID> {
    fun findAllByHouseholdId(householdId: UUID): List<RecurringTemplate>
    fun findAllByActiveTrue(): List<RecurringTemplate>
    fun findAllByHouseholdIdAndActiveTrue(householdId: UUID): List<RecurringTemplate>
}
