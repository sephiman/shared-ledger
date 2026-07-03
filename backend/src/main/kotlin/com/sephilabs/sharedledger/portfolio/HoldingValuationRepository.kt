package com.sephilabs.sharedledger.portfolio

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface HoldingValuationRepository : JpaRepository<HoldingValuation, UUID> {

    fun findAllBySnapshotId(snapshotId: UUID): List<HoldingValuation>

    @Modifying
    @Query("DELETE FROM HoldingValuation v WHERE v.snapshotId = :snapshotId")
    fun deleteAllBySnapshotId(@Param("snapshotId") snapshotId: UUID): Int
}
