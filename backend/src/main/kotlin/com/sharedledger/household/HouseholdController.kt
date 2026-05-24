package com.sharedledger.household

import com.sharedledger.common.errors.AppException
import com.sharedledger.identity.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class HouseholdDto(
    val id: UUID,
    val name: String,
    val currency: String,
    val defaultLocale: String,
    val role: String?,
)

data class HouseholdUpdateRequest(
    @field:Size(min = 1, max = 120, message = "validation.required")
    val name: String?,
    @field:Pattern(regexp = "[A-Za-z]{3}", message = "validation.invalid")
    val currency: String?,
    @field:Pattern(regexp = "en|es", message = "validation.invalid")
    val defaultLocale: String?,
)

@RestController
@RequestMapping("/api/households")
class HouseholdController(
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val currentUser: CurrentUser,
    private val householdContext: HouseholdContext,
) {

    @GetMapping
    fun listMine(): List<HouseholdDto> {
        val user = currentUser.requireUser()
        val memberships = members.findAllByIdUserId(user.id).associateBy { it.id.householdId }
        return households.findAllByUser(user.id).map { h ->
            HouseholdDto(h.id, h.name, h.currency, h.defaultLocale, memberships[h.id]?.role?.name)
        }
    }

    @GetMapping("/{householdId}")
    fun get(@PathVariable householdId: UUID): HouseholdDto {
        val h = households.findById(householdId).orElseThrow { AppException.notFound("HOUSEHOLD_NOT_FOUND") }
        return HouseholdDto(h.id, h.name, h.currency, h.defaultLocale, householdContext.role().name)
    }

    @RequireHouseholdOwner
    @PatchMapping("/{householdId}")
    @Transactional
    fun update(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: HouseholdUpdateRequest,
    ): HouseholdDto {
        val h = households.findById(householdId).orElseThrow { AppException.notFound("HOUSEHOLD_NOT_FOUND") }
        body.name?.let { h.name = it }
        body.currency?.let { h.currency = it.uppercase() }
        body.defaultLocale?.let { h.defaultLocale = it }
        return HouseholdDto(h.id, h.name, h.currency, h.defaultLocale, householdContext.role().name)
    }
}
