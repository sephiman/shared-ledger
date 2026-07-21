package com.sephilabs.sharedledger.identity.auth

import com.sephilabs.sharedledger.common.errors.AppException
import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.household.Household
import com.sephilabs.sharedledger.household.HouseholdMember
import com.sephilabs.sharedledger.household.HouseholdMemberId
import com.sephilabs.sharedledger.household.HouseholdMemberRepository
import com.sephilabs.sharedledger.household.HouseholdRepository
import com.sephilabs.sharedledger.household.HouseholdRole
import com.sephilabs.sharedledger.household.invitation.InvitationService
import com.sephilabs.sharedledger.identity.user.HomePanel
import com.sephilabs.sharedledger.identity.user.PortfolioReturnBasis
import com.sephilabs.sharedledger.identity.user.User
import com.sephilabs.sharedledger.identity.user.UserRepository
import com.sephilabs.sharedledger.observability.AppMetrics
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class AuthService(
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val invitations: InvitationService,
    private val encoder: PasswordEncoder,
    private val metrics: AppMetrics,
    private val props: AppProperties,
) {

    @Transactional
    fun register(request: RegisterRequest): User {
        val mode = props.registration.mode
        if (mode == AppProperties.RegistrationMode.CLOSED) {
            metrics.registration(mode.name, "disabled")
            throw AppException.forbidden("REGISTRATION_DISABLED")
        }

        // Validate the invitation / registration-mode path BEFORE probing whether the email exists,
        // so an unauthenticated caller without a valid invitation can't enumerate registered emails
        // (the email-taken 409 is only reachable once the invite/household path is satisfied).
        val token = request.invitationToken
        if (token == null) {
            if (mode == AppProperties.RegistrationMode.INVITE_ONLY) {
                metrics.registration(mode.name, "failed_invalid_invite")
                throw AppException.forbidden("REGISTRATION_REQUIRES_INVITATION")
            }
            if (request.household == null) {
                throw AppException.badRequest("REGISTRATION_HOUSEHOLD_REQUIRED")
            }
        } else {
            invitations.validateToken(token)
        }

        if (users.existsByEmailIgnoreCase(request.email)) {
            metrics.registration(mode.name, "failed_email_taken")
            throw AppException.conflict("EMAIL_ALREADY_REGISTERED")
        }

        val user = User(
            email = request.email.lowercase(),
            passwordHash = encoder.encode(request.password)!!,
            locale = request.locale,
        )
        users.save(user)

        if (token != null) {
            invitations.consumeIfPresent(token, user)
        } else {
            val input = request.household!!
            val household = Household(name = input.name, currency = input.currency.uppercase(), defaultLocale = input.defaultLocale)
            households.save(household)
            members.save(HouseholdMember(HouseholdMemberId(household.id, user.id), HouseholdRole.owner))
        }

        metrics.registration(mode.name, "success")
        return user
    }

    @Transactional
    fun recordLogin(userId: UUID) {
        val user = loadManaged(userId)
        user.lastLoginAt = Instant.now()
    }

    @Transactional
    fun changePassword(userId: UUID, current: String, newPassword: String) {
        val user = loadManaged(userId)
        if (!encoder.matches(current, user.passwordHash)) {
            throw AppException.badRequest("PASSWORD_MISMATCH")
        }
        user.passwordHash = encoder.encode(newPassword)!!
    }

    @Transactional
    fun updateLocale(userId: UUID, locale: String): User {
        val user = loadManaged(userId)
        user.locale = locale
        return user
    }

    @Transactional
    fun setHiddenHomePanels(userId: UUID, hiddenPanels: List<String>): User {
        val unknown = hiddenPanels.filterNot { it in HomePanel.ids }
        if (unknown.isNotEmpty()) {
            throw AppException.badRequest("INVALID_HOME_PANEL")
        }
        val user = loadManaged(userId)
        // Store in enum order so equivalent selections serialize identically.
        user.hiddenHomePanels = HomePanel.entries.map { it.id }.filter { it in hiddenPanels }.joinToString(",")
        return user
    }

    @Transactional
    fun setPortfolioReturnBasis(userId: UUID, basis: String): User {
        if (basis !in PortfolioReturnBasis.ids) {
            throw AppException.badRequest("INVALID_PORTFOLIO_RETURN_BASIS")
        }
        val user = loadManaged(userId)
        user.portfolioReturnBasis = basis
        return user
    }

    @Transactional
    fun setDefaultHousehold(userId: UUID, householdId: UUID): User {
        val user = loadManaged(userId)
        members.findByIdHouseholdIdAndIdUserId(householdId, userId)
            ?: throw AppException.forbidden("NOT_A_HOUSEHOLD_MEMBER")
        user.defaultHouseholdId = householdId
        return user
    }

    private fun loadManaged(userId: UUID): User =
        users.findById(userId).orElseThrow { AppException.unauthorized() }
}
