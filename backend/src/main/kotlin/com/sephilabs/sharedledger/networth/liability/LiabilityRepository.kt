package com.sephilabs.sharedledger.networth.liability

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LiabilityRepository : JpaRepository<Liability, UUID> {
    fun findAllByHouseholdIdOrderByNameAsc(householdId: UUID): List<Liability>
    fun findAllByHouseholdIdAndActiveTrueOrderByNameAsc(householdId: UUID): List<Liability>
}
