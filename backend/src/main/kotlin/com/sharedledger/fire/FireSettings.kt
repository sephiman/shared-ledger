package com.sharedledger.fire

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class ReturnScenario(
    val meanPercent: BigDecimal = BigDecimal.ZERO,
    val stdDevPercent: BigDecimal = BigDecimal.ZERO,
)

@Entity
@Table(name = "fire_settings")
class FireSettings(
    @Id
    @Column(name = "household_id", nullable = false, updatable = false)
    var householdId: UUID,

    @Column(name = "target_amount", nullable = false, precision = 15, scale = 2)
    var targetAmount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "target_year", nullable = false)
    var targetYear: Short = 2050,

    @Column(name = "monthly_contribution", nullable = false, precision = 15, scale = 2)
    var monthlyContribution: BigDecimal = BigDecimal.ZERO,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "return_scenarios", nullable = false, columnDefinition = "jsonb")
    var returnScenarios: List<ReturnScenario> = listOf(
        ReturnScenario(BigDecimal("4.0"), BigDecimal("5.0")),
        ReturnScenario(BigDecimal("6.0"), BigDecimal("12.0")),
        ReturnScenario(BigDecimal("8.0"), BigDecimal("18.0")),
    ),

    @Column(name = "qualifying_asset_classes", nullable = false, columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    var qualifyingAssetClasses: Array<String> = arrayOf("index_funds", "etfs", "stocks", "crypto", "pension"),

    @Column(name = "updated_by_user_id")
    var updatedByUserId: UUID? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
