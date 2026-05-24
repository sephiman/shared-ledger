package com.sharedledger.household.admin

import com.sharedledger.IntegrationTestBase
import com.sharedledger.household.Household
import com.sharedledger.household.HouseholdRepository
import com.sharedledger.identity.user.User
import com.sharedledger.identity.user.UserRepository
import com.sharedledger.networth.liability.Liability
import com.sharedledger.networth.liability.LiabilityRepository
import com.sharedledger.networth.movement.MovementRepository
import com.sharedledger.networth.movement.MovementType
import com.sharedledger.networth.movement.NetWorthMovement
import com.sharedledger.networth.snapshot.Snapshot
import com.sharedledger.networth.snapshot.SnapshotAssetValue
import com.sharedledger.networth.snapshot.SnapshotAssetValueId
import com.sharedledger.networth.snapshot.SnapshotLiabilityBalance
import com.sharedledger.networth.snapshot.SnapshotLiabilityBalanceId
import com.sharedledger.networth.snapshot.SnapshotRepository
import com.sharedledger.transaction.Direction
import com.sharedledger.transaction.Transaction
import com.sharedledger.transaction.TransactionRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class HouseholdDataWipePersistenceTest @Autowired constructor(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val liabilities: LiabilityRepository,
    private val transactions: TransactionRepository,
    private val snapshots: SnapshotRepository,
    private val movements: MovementRepository,
    private val service: HouseholdDataWipeService,
) : IntegrationTestBase() {

    @PersistenceContext
    private lateinit var em: EntityManager

    @Test
    fun `wipe removes transactions, snapshots with child rows, and movements for the household`() {
        val (user, target) = seed("wp-a")
        val (_, other) = seed("wp-b")
        val liability = liabilities.save(Liability(
            householdId = target.id,
            name = "Mortgage ${System.nanoTime()}",
            createdByUserId = user.id,
            updatedByUserId = user.id,
        ))
        val otherLiability = liabilities.save(Liability(
            householdId = other.id,
            name = "Other-mortgage ${System.nanoTime()}",
            createdByUserId = user.id,
            updatedByUserId = user.id,
        ))

        // Target household: 2 transactions (one soft-deleted), 1 snapshot with one asset + one liability balance, 1 movement.
        val tx1 = transactions.save(sampleTx(target.id, user.id))
        val tx2 = transactions.save(sampleTx(target.id, user.id).also { it.deletedAt = Instant.now() })
        val snapshot = Snapshot(
            householdId = target.id,
            snapshotDate = LocalDate.of(2025, 4, 1),
            createdByUserId = user.id,
            updatedByUserId = user.id,
        )
        snapshot.assetValues.add(SnapshotAssetValue(SnapshotAssetValueId(snapshot.id, "etfs"), BigDecimal("1000.00")))
        snapshot.liabilityBalances.add(SnapshotLiabilityBalance(SnapshotLiabilityBalanceId(snapshot.id, liability.id), BigDecimal("250.00")))
        snapshots.save(snapshot)
        val mv = movements.save(sampleMovement(target.id, user.id))

        // Other household: one of each to confirm scoping.
        val otherTx = transactions.save(sampleTx(other.id, user.id))
        val otherSnapshot = Snapshot(
            householdId = other.id,
            snapshotDate = LocalDate.of(2025, 4, 1),
            createdByUserId = user.id,
            updatedByUserId = user.id,
        )
        otherSnapshot.assetValues.add(SnapshotAssetValue(SnapshotAssetValueId(otherSnapshot.id, "etfs"), BigDecimal("500.00")))
        otherSnapshot.liabilityBalances.add(SnapshotLiabilityBalance(SnapshotLiabilityBalanceId(otherSnapshot.id, otherLiability.id), BigDecimal("100.00")))
        snapshots.save(otherSnapshot)
        val otherMv = movements.save(sampleMovement(other.id, user.id))

        em.flush()
        em.clear()

        service.wipe(target.id, user.id)

        em.flush()
        em.clear()

        // Target household: everything gone (incl. soft-deleted tx2 — bypassing @SQLRestriction via native count).
        assertThat(countRows("transactions", target.id)).isZero
        assertThat(countRows("snapshots", target.id)).isZero
        assertThat(countSnapshotLiabilityBalances(target.id)).isZero
        assertThat(countSnapshotAssetValues(target.id)).isZero
        assertThat(countRows("net_worth_movements", target.id)).isZero

        // Other household untouched.
        assertThat(transactions.findById(otherTx.id)).isPresent
        assertThat(snapshots.findById(otherSnapshot.id)).isPresent
        assertThat(movements.findById(otherMv.id)).isPresent

        // Sanity: ensure the soft-deleted row really existed before wipe and is gone after.
        assertThat(tx1.id).isNotEqualTo(tx2.id)
        assertThat(transactions.findById(tx2.id)).isEmpty
        assertThat(transactions.findById(tx1.id)).isEmpty
        assertThat(movements.findById(mv.id)).isEmpty
    }

    private fun countRows(table: String, householdId: java.util.UUID): Long {
        val q = em.createNativeQuery("SELECT COUNT(*) FROM $table WHERE household_id = :hid")
        q.setParameter("hid", householdId)
        return (q.singleResult as Number).toLong()
    }

    private fun countSnapshotAssetValues(householdId: java.util.UUID): Long {
        val q = em.createNativeQuery("""
            SELECT COUNT(*) FROM snapshot_asset_values v
            WHERE v.snapshot_id IN (SELECT id FROM snapshots WHERE household_id = :hid)
        """.trimIndent())
        q.setParameter("hid", householdId)
        return (q.singleResult as Number).toLong()
    }

    private fun countSnapshotLiabilityBalances(householdId: java.util.UUID): Long {
        val q = em.createNativeQuery("""
            SELECT COUNT(*) FROM snapshot_liability_balances b
            WHERE b.snapshot_id IN (SELECT id FROM snapshots WHERE household_id = :hid)
        """.trimIndent())
        q.setParameter("hid", householdId)
        return (q.singleResult as Number).toLong()
    }

    private fun sampleTx(householdId: java.util.UUID, userId: java.util.UUID) = Transaction(
        householdId = householdId,
        occurrenceDate = LocalDate.of(2025, 1, 15),
        direction = Direction.expense,
        categoryCode = "groceries.groceries",
        amount = BigDecimal("12.34"),
        description = null,
        createdByUserId = userId,
        updatedByUserId = userId,
    )

    private fun sampleMovement(householdId: java.util.UUID, userId: java.util.UUID) = NetWorthMovement(
        householdId = householdId,
        movementDate = LocalDate.of(2025, 3, 1),
        type = MovementType.contribution,
        assetClassCode = "etfs",
        liabilityId = null,
        amount = BigDecimal("500.00"),
        description = null,
        createdByUserId = userId,
        updatedByUserId = userId,
    )

    private fun seed(prefix: String): Pair<User, Household> {
        val user = users.save(User(email = "$prefix-${System.nanoTime()}@example.com", passwordHash = "x", locale = "en"))
        val household = households.save(Household(name = "H-$prefix", currency = "EUR", defaultLocale = "en"))
        return user to household
    }
}
