package com.sephilabs.sharedledger.bank.rulegeneralisation

import com.sephilabs.sharedledger.bank.MovementStatus
import com.sephilabs.sharedledger.bank.PendingMovement
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

/** A movement as the household confirmed it: one row per distinct category it was booked under. */
interface ConfirmedCategory {
    val counterparty: String?
    val description: String?
    val categoryCode: String
    val direction: String
}

interface ConfirmedCategoryHistory : Repository<PendingMovement, UUID> {

    /** Through the link table, so split and merged items count with every category they produced. */
    @Query(
        value = """
            SELECT DISTINCT m.counterparty AS counterparty, m.description AS description,
                   t.category_code AS categoryCode, t.direction AS direction
            FROM pending_movements m
            JOIN pending_movement_transactions l ON l.pending_movement_id = m.id
            JOIN transactions t ON t.id = l.transaction_id AND t.deleted_at IS NULL
            WHERE m.household_id = :hid AND m.status = 'confirmed' AND m.booking_date >= :since
            ORDER BY m.counterparty, m.description
        """,
        nativeQuery = true,
    )
    fun confirmedSince(@Param("hid") householdId: UUID, @Param("since") since: LocalDate): List<ConfirmedCategory>

    /** What the replay runs the engine over: every item still in the inbox, and what was confirmed lately. */
    @Query(
        """
        SELECT m FROM PendingMovement m WHERE m.householdId = :hid
          AND (m.status = :pending OR (m.status = :confirmed AND m.bookingDate >= :since))
        """,
    )
    fun knownMovements(
        @Param("hid") householdId: UUID,
        @Param("since") since: LocalDate,
        @Param("pending") pending: MovementStatus = MovementStatus.pending,
        @Param("confirmed") confirmed: MovementStatus = MovementStatus.confirmed,
    ): List<PendingMovement>
}
