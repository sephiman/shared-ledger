package com.sharedledger.catalog

import com.sharedledger.common.errors.AppException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Unified view of a category, regardless of whether it's a global catalog entry
 * or a per-household custom one. Callers outside the catalog package should
 * depend on this type and CategoryService — never on the underlying entities.
 *
 * - `name`: for customs, the free text the owner typed; for globals, the literal i18n key
 *   string (e.g. "category.home.rent") so callers always have a non-null display value.
 * - `custom`: lets the UI conditionally show edit/delete affordances.
 */
data class CategoryView(
    val code: String,
    val name: String,
    val kind: String,
    val group: String?,
    val sortOrder: Int,
    val essential: Boolean,
    val custom: Boolean,
)

@Service
class CategoryService(
    private val globals: CategoryRepository,
    private val customs: CustomCategoryRepository,
) {

    @Transactional(readOnly = true)
    fun listForHousehold(householdId: UUID): List<CategoryView> {
        val globalViews = globals.findAllByOrderBySortOrderAsc()
            .filter { it.active }
            .map { it.toView() }
        val customViews = customs.findAllByIdHouseholdIdOrderBySortOrderAsc(householdId)
            .map { it.toView() }
        return (globalViews + customViews).sortedBy { it.sortOrder }
    }

    @Transactional(readOnly = true)
    fun find(householdId: UUID, code: String): CategoryView? {
        globals.findById(code).orElse(null)?.let { if (it.active) return it.toView() }
        return customs.findByIdHouseholdIdAndIdCode(householdId, code)?.toView()
    }

    /**
     * Resolve a category and require its kind to match the given direction.
     * Replaces the V004 `enforce_transaction_direction` SQL trigger.
     */
    @Transactional(readOnly = true)
    fun requireForDirection(householdId: UUID, code: String, direction: String): CategoryView {
        val view = find(householdId, code) ?: throw AppException.notFound("CATEGORY_NOT_FOUND")
        if (view.kind != direction) throw AppException.badRequest("CATEGORY_DIRECTION_MISMATCH")
        return view
    }

    private fun CategoryEntity.toView() = CategoryView(
        code = code,
        name = "category.$code",
        kind = kind,
        group = groupCode,
        sortOrder = sortOrder,
        essential = kind == "expense" && essential,
        custom = false,
    )

    private fun CustomCategoryEntity.toView() = CategoryView(
        code = id.code,
        name = name,
        kind = kind,
        group = groupCode,
        sortOrder = sortOrder,
        essential = kind == "expense" && essential,
        custom = true,
    )
}
