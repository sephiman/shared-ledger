package com.sephilabs.sharedledger.fire

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FireSettingsRepository : JpaRepository<FireSettings, UUID> {

    @Modifying
    @Query(value = "DELETE FROM fire_settings WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    /** Changes whenever anything a FIRE figure is derived from changes: snapshots and their values, movements,
     *  transactions, and which categories count as essential. */
    @Query(
        value = """
        SELECT md5(concat_ws('|',
            (SELECT string_agg(s.id::text || '@' || s.snapshot_date || ':' || coalesce(v.asset_class_code, '') || '=' || coalesce(v.value::text, ''), ',' ORDER BY s.id, v.asset_class_code)
               FROM snapshots s LEFT JOIN snapshot_asset_values v ON v.snapshot_id = s.id WHERE s.household_id = :hid),
            (SELECT string_agg(m.id::text || ':' || m.movement_date || ':' || m.type || ':' || coalesce(m.asset_class_code, '') || '=' || m.amount::text, ',' ORDER BY m.id)
               FROM net_worth_movements m WHERE m.household_id = :hid AND m.deleted_at IS NULL),
            (SELECT string_agg(t.id::text || ':' || t.occurrence_date || ':' || t.direction || ':' || t.category_code || '=' || t.amount::text, ',' ORDER BY t.id)
               FROM transactions t WHERE t.household_id = :hid AND t.deleted_at IS NULL),
            (SELECT string_agg(c.code || ':' || c.essential || ':' || coalesce(c.group_code, ''), ',' ORDER BY c.code)
               FROM custom_categories c WHERE c.household_id = :hid),
            (SELECT string_agg(g.code || ':' || g.essential || ':' || g.active || ':' || coalesce(g.group_code, ''), ',' ORDER BY g.code)
               FROM categories g)
        ))
        """,
        nativeQuery = true,
    )
    fun dataFingerprint(@Param("hid") householdId: UUID): String
}

interface FireTaxBracketRepository : JpaRepository<FireTaxBracket, FireTaxBracketId> {

    @Query("SELECT b FROM FireTaxBracket b WHERE b.id.householdId = :hid ORDER BY b.id.lowerBound ASC")
    fun findAllForHousehold(@Param("hid") householdId: UUID): List<FireTaxBracket>

    @Modifying
    @Query(value = "DELETE FROM fire_tax_brackets WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int
}
