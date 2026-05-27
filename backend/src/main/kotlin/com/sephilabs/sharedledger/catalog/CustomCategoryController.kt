package com.sephilabs.sharedledger.catalog

import com.sephilabs.sharedledger.household.RequireHouseholdOwner
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CustomCategoryCreateRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 80, message = "validation.too_long")
    val name: String,

    @field:NotBlank(message = "validation.required")
    @field:Pattern(regexp = "income|expense", message = "validation.invalid")
    val kind: String,

    @field:Size(max = 32, message = "validation.too_long")
    val groupCode: String? = null,

    val essential: Boolean = false,
)

data class CustomCategoryUpdateRequest(
    @field:Size(min = 1, max = 80, message = "validation.invalid")
    val name: String? = null,

    @field:Size(max = 32, message = "validation.invalid")
    val groupCode: String? = null,

    val essential: Boolean? = null,
)

@RestController
@RequestMapping("/api/households/{householdId}/categories")
class CustomCategoryController(
    private val writer: CustomCategoryWriter,
    private val currentUser: CurrentUser,
) {

    @PostMapping
    @RequireHouseholdOwner
    fun create(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: CustomCategoryCreateRequest,
    ): ResponseEntity<CategoryDto> {
        val user = currentUser.requireUser()
        val saved = writer.create(
            householdId = householdId,
            name = body.name,
            kind = body.kind,
            groupCode = body.groupCode,
            essential = body.essential,
            by = user,
        )
        return ResponseEntity.status(201).body(saved.toDto())
    }

    @PatchMapping("/{code}")
    @RequireHouseholdOwner
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable code: String,
        @Valid @RequestBody body: CustomCategoryUpdateRequest,
    ): CategoryDto =
        writer.update(
            householdId = householdId,
            code = code,
            name = body.name,
            groupCode = body.groupCode,
            essential = body.essential,
        ).toDto()

    @DeleteMapping("/{code}")
    @RequireHouseholdOwner
    fun delete(
        @PathVariable householdId: UUID,
        @PathVariable code: String,
    ): ResponseEntity<Void> {
        writer.delete(householdId, code)
        return ResponseEntity.noContent().build()
    }

    private fun CustomCategoryEntity.toDto() = CategoryDto(
        code = id.code,
        kind = kind,
        group = groupCode,
        sortOrder = sortOrder,
        essential = kind == "expense" && essential,
        name = name,
        custom = true,
    )
}
