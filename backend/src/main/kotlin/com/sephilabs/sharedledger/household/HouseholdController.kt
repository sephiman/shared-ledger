package com.sephilabs.sharedledger.household

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.common.validation.ValidCurrency
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import com.sephilabs.sharedledger.identity.user.UserRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.*

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
    @field:ValidCurrency
    val currency: String?,
    @field:Pattern(regexp = "en|es", message = "validation.invalid")
    val defaultLocale: String?,
)

data class HouseholdCreateRequest(
    @field:NotBlank(message = "validation.required")
    @field:Size(max = 120, message = "validation.required")
    val name: String,
    @field:NotBlank(message = "validation.required")
    @field:ValidCurrency
    val currency: String,
    @field:Pattern(regexp = "en|es", message = "validation.invalid")
    val defaultLocale: String = "en",
)

data class HouseholdMemberDto(
    val userId: UUID,
    val email: String,
    val role: HouseholdRole,
    val joinedAt: Instant,
)

@RestController
@RequestMapping("/api/households")
class HouseholdController(
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val users: UserRepository,
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

    @PostMapping
    @Transactional
    fun create(@Valid @RequestBody body: HouseholdCreateRequest): ResponseEntity<HouseholdDto> {
        val user = currentUser.requireUser()
        val household = Household(
            name = body.name.trim(),
            currency = body.currency.uppercase(),
            defaultLocale = body.defaultLocale,
        )
        households.save(household)
        members.save(HouseholdMember(HouseholdMemberId(household.id, user.id), HouseholdRole.owner))
        val dto = HouseholdDto(household.id, household.name, household.currency, household.defaultLocale, HouseholdRole.owner.name)
        return ResponseEntity.status(201).body(dto)
    }

    @GetMapping("/{householdId}")
    fun get(@PathVariable householdId: UUID): HouseholdDto {
        val h = households.findById(householdId).orElseThrow { AppException.notFound("HOUSEHOLD_NOT_FOUND") }
        return HouseholdDto(h.id, h.name, h.currency, h.defaultLocale, householdContext.role().name)
    }

    @GetMapping("/{householdId}/members")
    @Transactional(readOnly = true)
    fun listMembers(@PathVariable householdId: UUID): List<HouseholdMemberDto> {
        val memberships = members.findAllByIdHouseholdId(householdId)
        if (memberships.isEmpty()) return emptyList()
        val emailByUserId = users.findAllById(memberships.map { it.id.userId })
            .associate { it.id to it.email }
        return memberships
            .mapNotNull { m ->
                val email = emailByUserId[m.id.userId] ?: return@mapNotNull null
                HouseholdMemberDto(m.id.userId, email, m.role, m.joinedAt)
            }
            .sortedWith(compareBy({ if (it.role == HouseholdRole.owner) 0 else 1 }, { it.joinedAt }))
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

    @RequireHouseholdOwner
    @DeleteMapping("/{householdId}")
    @Transactional
    fun delete(@PathVariable householdId: UUID): ResponseEntity<Void> {
        if (!households.existsById(householdId)) {
            throw AppException.notFound("HOUSEHOLD_NOT_FOUND")
        }
        if (users.existsByDefaultHouseholdId(householdId)) {
            throw AppException.conflict("HOUSEHOLD_IS_DEFAULT")
        }
        households.deleteById(householdId)
        return ResponseEntity.noContent().build()
    }
}
