package com.sephilabs.sharedledger.networth.liability

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class LiabilityRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 120)
    val name: String,
    val active: Boolean = true,
)

data class LiabilityDto(
    val id: UUID,
    val name: String,
    val active: Boolean,
)

fun Liability.toDto() = LiabilityDto(id, name, active)

@RestController
@RequestMapping("/api/households/{householdId}/liabilities")
class LiabilityController(
    private val liabilities: LiabilityRepository,
    private val currentUser: CurrentUser,
) {

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<LiabilityDto> =
        liabilities.findAllByHouseholdIdOrderByNameAsc(householdId).map { it.toDto() }

    @PostMapping
    @Transactional
    fun create(@PathVariable householdId: UUID, @Valid @RequestBody body: LiabilityRequest): ResponseEntity<LiabilityDto> {
        val by = currentUser.requireUser()
        val liability = Liability(
            householdId = householdId,
            name = body.name.trim(),
            active = body.active,
            createdByUserId = by.id,
            updatedByUserId = by.id,
        )
        liabilities.save(liability)
        return ResponseEntity.status(201).body(liability.toDto())
    }

    @PatchMapping("/{id}")
    @Transactional
    fun update(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody body: LiabilityRequest,
    ): LiabilityDto {
        val by = currentUser.requireUser()
        val liability = liabilities.findById(id).orElseThrow { AppException.notFound("LIABILITY_NOT_FOUND") }
        if (liability.householdId != householdId) throw AppException.notFound("LIABILITY_NOT_FOUND")
        liability.name = body.name.trim()
        liability.active = body.active
        liability.updatedByUserId = by.id
        return liability.toDto()
    }

    @DeleteMapping("/{id}")
    @Transactional
    fun delete(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        val by = currentUser.requireUser()
        val liability = liabilities.findById(id).orElseThrow { AppException.notFound("LIABILITY_NOT_FOUND") }
        if (liability.householdId != householdId) throw AppException.notFound("LIABILITY_NOT_FOUND")
        liability.deletedAt = Instant.now()
        liability.updatedByUserId = by.id
        return ResponseEntity.noContent().build()
    }
}
