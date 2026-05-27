package com.sephilabs.sharedledger.catalog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Entity
@Table(name = "categories")
class CategoryEntity(
    @Id
    @Column(name = "code", nullable = false, updatable = false)
    var code: String = "",

    @Column(name = "kind", nullable = false, length = 16)
    var kind: String = "",

    @Column(name = "group_code")
    var groupCode: String? = null,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "active", nullable = false)
    var active: Boolean = true,

    @Column(name = "essential", nullable = false)
    var essential: Boolean = false,
)

@Entity
@Table(name = "asset_classes")
class AssetClassEntity(
    @Id
    @Column(name = "code", nullable = false, updatable = false)
    var code: String = "",

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,
)

interface CategoryRepository : JpaRepository<CategoryEntity, String> {
    fun findAllByOrderBySortOrderAsc(): List<CategoryEntity>
}

interface AssetClassRepository : JpaRepository<AssetClassEntity, String> {
    fun findAllByOrderBySortOrderAsc(): List<AssetClassEntity>
}

data class CategoryDto(
    val code: String,
    val kind: String,
    val group: String?,
    val sortOrder: Int,
    val essential: Boolean,
    val name: String,
    val custom: Boolean,
)

data class AssetClassDto(
    val code: String,
    val sortOrder: Int,
)

@RestController
class CatalogController(
    private val categoryService: CategoryService,
    private val assetClasses: AssetClassRepository,
) {
    @GetMapping("/api/households/{householdId}/categories")
    fun categories(@PathVariable householdId: UUID): List<CategoryDto> =
        categoryService.listForHousehold(householdId).map { it.toDto() }

    @GetMapping("/api/asset-classes")
    fun assetClasses(): List<AssetClassDto> =
        assetClasses.findAllByOrderBySortOrderAsc().map { AssetClassDto(it.code, it.sortOrder) }

    private fun CategoryView.toDto() = CategoryDto(
        code = code,
        kind = kind,
        group = group,
        sortOrder = sortOrder,
        essential = essential,
        name = name,
        custom = custom,
    )
}
