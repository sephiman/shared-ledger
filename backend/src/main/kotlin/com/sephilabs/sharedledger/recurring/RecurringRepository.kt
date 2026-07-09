package com.sephilabs.sharedledger.recurring

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface RecurringTemplateRepository : JpaRepository<RecurringTemplate, UUID> {

    /** Hard-delete every template of the household, INCLUDING soft-deleted (native, bypasses @SQLRestriction). */
    @Modifying
    @Query(value = "DELETE FROM recurring_templates WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findAllByHouseholdId(householdId: UUID): List<RecurringTemplate>
    fun findAllByActiveTrue(): List<RecurringTemplate>
    fun findAllByHouseholdIdAndActiveTrue(householdId: UUID): List<RecurringTemplate>
}
