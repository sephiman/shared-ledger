package com.sephilabs.sharedledger.household

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.common.validation.ValidCurrency
import com.sephilabs.sharedledger.identity.auth.CurrentUser
import com.sephilabs.sharedledger.identity.user.UserRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
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

data class MemberRoleUpdateRequest(
    @field:NotNull(message = "validation.required")
    val role: HouseholdRole,
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

    private val log = LoggerFactory.getLogger(javaClass)

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
        val isFirstHousehold = members.findAllByIdUserId(user.id).isEmpty()
        val household = Household(
            name = body.name.trim(),
            currency = body.currency.uppercase(),
            defaultLocale = body.defaultLocale,
        )
        households.save(household)
        members.save(HouseholdMember(HouseholdMemberId(household.id, user.id), HouseholdRole.owner))
        // On the user's first household, adopt its language as the profile locale so the UI
        // matches the language they just chose. `user` is managed within this transaction,
        // so the change persists via dirty checking (see AuthService.updateLocale).
        if (isFirstHousehold) {
            user.locale = body.defaultLocale
        }
        log.info(
            "household_created household={} currency={} locale={} first={}",
            household.id, household.currency, household.defaultLocale, isFirstHousehold,
        )
        val dto = HouseholdDto(household.id, household.name, household.currency, household.defaultLocale, HouseholdRole.owner.name)
        return ResponseEntity.status(201).body(dto)
    }

    @GetMapping("/{householdId}")
    fun get(@PathVariable householdId: UUID): HouseholdDto {
        val h = households.getOrThrow(householdId)
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

    /** Promote a member to `owner` or demote one back. The household must keep at least one owner — this
     *  endpoint is itself owner-only, so emptying the role would make settings and deletion unreachable. */
    @RequireHouseholdOwner
    @PatchMapping("/{householdId}/members/{userId}")
    @Transactional
    fun updateMemberRole(
        @PathVariable householdId: UUID,
        @PathVariable userId: UUID,
        @Valid @RequestBody body: MemberRoleUpdateRequest,
    ): HouseholdMemberDto {
        val membership = members.findByIdHouseholdIdAndIdUserId(householdId, userId)
            ?: throw AppException.notFound("MEMBER_NOT_FOUND")
        val user = users.findById(userId).orElseThrow { AppException.notFound("MEMBER_NOT_FOUND") }
        if (membership.role == HouseholdRole.owner && body.role != HouseholdRole.owner) {
            val owners = members.findAllByIdHouseholdId(householdId).count { it.role == HouseholdRole.owner }
            if (owners <= 1) throw AppException.conflict("LAST_HOUSEHOLD_OWNER")
        }
        val roleBefore = membership.role
        membership.role = body.role
        log.info("household_member_role_changed target_user={} role={}->{}", userId, roleBefore, membership.role)
        return HouseholdMemberDto(userId, user.email, membership.role, membership.joinedAt)
    }

    @RequireHouseholdOwner
    @PatchMapping("/{householdId}")
    @Transactional
    fun update(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: HouseholdUpdateRequest,
    ): HouseholdDto {
        val h = households.getOrThrow(householdId)
        val currencyBefore = h.currency
        val localeBefore = h.defaultLocale
        body.name?.let { h.name = it }
        body.currency?.let { h.currency = it.uppercase() }
        body.defaultLocale?.let { h.defaultLocale = it }
        log.info(
            "household_updated currency={}->{} locale={}->{} name_changed={}",
            currencyBefore, h.currency, localeBefore, h.defaultLocale, body.name != null,
        )
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
        log.info("household_deleted household={}", householdId)
        return ResponseEntity.noContent().build()
    }
}
