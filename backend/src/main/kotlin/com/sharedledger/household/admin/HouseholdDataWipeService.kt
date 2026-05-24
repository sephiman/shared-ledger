package com.sharedledger.household.admin

import com.sharedledger.networth.movement.MovementRepository
import com.sharedledger.networth.snapshot.SnapshotRepository
import com.sharedledger.transaction.TransactionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class HouseholdDataWipeService(
    private val transactions: TransactionRepository,
    private val snapshots: SnapshotRepository,
    private val movements: MovementRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun wipe(householdId: UUID, byUserId: UUID) {
        val txCount = transactions.hardDeleteAllByHouseholdId(householdId)
        val balanceCount = snapshots.deleteAllLiabilityBalancesByHouseholdId(householdId)
        val snapshotCount = snapshots.hardDeleteAllByHouseholdId(householdId)
        val movementCount = movements.hardDeleteAllByHouseholdId(householdId)
        log.info(
            "household_data_wipe household={} by_user={} tx={} snap={} snap_liab_balances={} mv={}",
            householdId, byUserId, txCount, snapshotCount, balanceCount, movementCount,
        )
    }
}
