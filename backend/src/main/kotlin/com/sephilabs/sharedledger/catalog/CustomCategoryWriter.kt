package com.sephilabs.sharedledger.catalog

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.user.User
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer
import java.util.UUID

@Service
class CustomCategoryWriter(
    private val globals: CategoryRepository,
    private val customs: CustomCategoryRepository,
) {

    @PersistenceContext
    private lateinit var em: EntityManager

    private val combiningMarks = Regex("\\p{M}+")
    private val nonAlnum = Regex("[^a-z0-9]+")
    private val edgeUnderscores = Regex("(^_+)|(_+$)")

    @Transactional
    fun create(
        householdId: UUID,
        name: String,
        kind: String,
        groupCode: String?,
        essential: Boolean,
        by: User,
    ): CustomCategoryEntity {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw AppException.badRequest("INVALID_NAME")

        val resolvedGroup = validateAndResolveGroup(kind, groupCode)
        val slug = slugify(trimmed)
        if (slug.isEmpty()) throw AppException.badRequest("INVALID_NAME")
        val code = if (kind == "expense") "$resolvedGroup.$slug" else "income.$slug"
        if (code.length > 64) throw AppException.badRequest("INVALID_NAME")
        rejectIfCodeTaken(householdId, code)

        val entity = CustomCategoryEntity(
            id = CustomCategoryId(householdId = householdId, code = code),
            name = trimmed,
            kind = kind,
            groupCode = resolvedGroup,
            sortOrder = 1000,
            essential = essential,
            createdByUserId = by.id,
        )
        return customs.save(entity)
    }

    @Transactional
    fun update(
        householdId: UUID,
        code: String,
        name: String?,
        groupCode: String?,
        essential: Boolean?,
    ): CustomCategoryEntity {
        val entity = customs.findByIdHouseholdIdAndIdCode(householdId, code)
            ?: throw AppException.notFound("CUSTOM_CATEGORY_NOT_FOUND")

        if (name != null) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) throw AppException.badRequest("INVALID_NAME")
            entity.name = trimmed
        }
        if (groupCode != null) {
            entity.groupCode = validateAndResolveGroup(entity.kind, groupCode)
        }
        if (essential != null) {
            entity.essential = essential
        }
        return entity
    }

    /**
     * Hard-deletes the custom category along with every transaction, budget, and
     * recurring template referencing it in this household. All in one transaction.
     */
    @Transactional
    fun delete(householdId: UUID, code: String) {
        val entity = customs.findByIdHouseholdIdAndIdCode(householdId, code)
            ?: throw AppException.notFound("CUSTOM_CATEGORY_NOT_FOUND")

        em.createNativeQuery(
            "DELETE FROM transactions WHERE household_id = :hid AND category_code = :code"
        ).setParameter("hid", householdId).setParameter("code", code).executeUpdate()

        em.createNativeQuery(
            "DELETE FROM budgets WHERE household_id = :hid AND category_code = :code"
        ).setParameter("hid", householdId).setParameter("code", code).executeUpdate()

        em.createNativeQuery(
            "DELETE FROM recurring_templates WHERE household_id = :hid AND category_code = :code"
        ).setParameter("hid", householdId).setParameter("code", code).executeUpdate()

        customs.delete(entity)
    }

    private fun validateAndResolveGroup(kind: String, groupCode: String?): String? {
        when (kind) {
            "expense" -> {
                if (groupCode.isNullOrBlank()) throw AppException.badRequest("INVALID_GROUP")
                if (groupCode !in allowedExpenseGroups()) throw AppException.badRequest("INVALID_GROUP")
                return groupCode
            }
            "income" -> {
                if (!groupCode.isNullOrBlank()) throw AppException.badRequest("INVALID_GROUP")
                return null
            }
            else -> throw AppException.badRequest("INVALID_KIND")
        }
    }

    /** Returns the set of distinct expense group codes from the global catalog. */
    private fun allowedExpenseGroups(): Set<String> =
        globals.findAllByOrderBySortOrderAsc()
            .asSequence()
            .filter { it.kind == "expense" }
            .mapNotNull { it.groupCode }
            .toSet()

    private fun rejectIfCodeTaken(householdId: UUID, code: String) {
        if (globals.findById(code).isPresent) throw AppException.conflict("CATEGORY_CODE_CONFLICT")
        if (customs.findByIdHouseholdIdAndIdCode(householdId, code) != null)
            throw AppException.conflict("CATEGORY_CODE_CONFLICT")
    }

    private fun slugify(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val noMarks = combiningMarks.replace(decomposed, "")
        val lowered = noMarks.lowercase()
        val collapsed = nonAlnum.replace(lowered, "_")
        return edgeUnderscores.replace(collapsed, "")
    }
}
