package com.sephilabs.sharedledger.household

import com.sephilabs.sharedledger.common.errors.AppException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface HouseholdRepository : JpaRepository<Household, UUID> {

    @Query("""
        SELECT h FROM Household h
        WHERE h.id IN (SELECT m.id.householdId FROM HouseholdMember m WHERE m.id.userId = :userId)
        ORDER BY h.name ASC
    """)
    fun findAllByUser(@Param("userId") userId: UUID): List<Household>
}

fun HouseholdRepository.getOrThrow(id: UUID): Household =
    findById(id).orElseThrow { AppException.notFound("HOUSEHOLD_NOT_FOUND") }

interface HouseholdMemberRepository : JpaRepository<HouseholdMember, HouseholdMemberId> {
    fun findAllByIdUserId(userId: UUID): List<HouseholdMember>
    fun findAllByIdHouseholdId(householdId: UUID): List<HouseholdMember>
    fun findByIdHouseholdIdAndIdUserId(householdId: UUID, userId: UUID): HouseholdMember?
}
