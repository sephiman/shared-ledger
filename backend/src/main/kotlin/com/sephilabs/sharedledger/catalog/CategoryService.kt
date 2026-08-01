package com.sephilabs.sharedledger.catalog

import com.sephilabs.sharedledger.common.errors.AppException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Unified view of a category, global or per-household custom. Callers outside the catalog package depend
 *  on this and CategoryService, never on the entities. `name` is the owner's text for customs and the
 *  literal i18n key for globals, so there is always a non-null display value. */
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

    /** Resolve a category and require its kind to match the direction. Replaces the V004
     *  `enforce_transaction_direction` SQL trigger. */
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
