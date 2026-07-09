package com.sephilabs.sharedledger.catalog

import com.sephilabs.sharedledger.common.TimestampedEntity
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.io.Serializable
import java.util.UUID

@Embeddable
data class CustomCategoryId(
    @Column(name = "household_id") var householdId: UUID = UUID.randomUUID(),
    @Column(name = "code") var code: String = "",
) : Serializable

@Entity
@Table(name = "custom_categories")
class CustomCategoryEntity(
    @EmbeddedId
    var id: CustomCategoryId = CustomCategoryId(),

    @Column(name = "name", nullable = false, length = 80)
    var name: String = "",

    @Column(name = "kind", nullable = false, length = 16)
    var kind: String = "",

    @Column(name = "group_code", length = 32)
    var groupCode: String? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 1000,

    @Column(name = "essential", nullable = false)
    var essential: Boolean = false,

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    var createdByUserId: UUID = UUID.randomUUID(),
) : TimestampedEntity()

interface CustomCategoryRepository : JpaRepository<CustomCategoryEntity, CustomCategoryId> {

    @Modifying
    @Query(value = "DELETE FROM custom_categories WHERE household_id = :hid", nativeQuery = true)
    fun hardDeleteAllByHouseholdId(@Param("hid") householdId: UUID): Int

    fun findAllByIdHouseholdIdOrderBySortOrderAsc(householdId: UUID): List<CustomCategoryEntity>
    fun findByIdHouseholdIdAndIdCode(householdId: UUID, code: String): CustomCategoryEntity?
}
