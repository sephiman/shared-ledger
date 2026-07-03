package com.sephilabs.sharedledger.portfolio

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface HoldingLotRepository : JpaRepository<HoldingLot, UUID> {

    fun findAllByHoldingIdOrderByTradedOnAscCreatedAtAsc(holdingId: UUID): List<HoldingLot>

    fun findByIdAndHoldingId(id: UUID, holdingId: UUID): HoldingLot?

    fun findAllByHoldingIdIn(holdingIds: Collection<UUID>): List<HoldingLot>

    @Query("SELECT MIN(l.tradedOn) FROM HoldingLot l WHERE l.holdingId = :holdingId")
    fun findMinTradedOn(@Param("holdingId") holdingId: UUID): LocalDate?

    @Query("SELECT DISTINCT l.currency FROM HoldingLot l WHERE l.currency <> :base")
    fun findDistinctForeignCurrencies(@Param("base") base: String): List<String>
}
