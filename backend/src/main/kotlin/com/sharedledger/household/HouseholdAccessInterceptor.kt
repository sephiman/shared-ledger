package com.sharedledger.household

import com.sharedledger.common.errors.AppException
import com.sharedledger.identity.auth.CurrentUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

const val ATTR_HOUSEHOLD_ID = "sl.householdId"
const val ATTR_HOUSEHOLD_ROLE = "sl.householdRole"

@Component
class HouseholdAccessInterceptor(
    private val currentUser: CurrentUser,
    private val members: HouseholdMemberRepository,
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        @Suppress("UNCHECKED_CAST")
        val pathVars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
            ?: return true
        val raw = pathVars["householdId"] ?: return true
        val householdId = try { UUID.fromString(raw) } catch (_: IllegalArgumentException) {
            throw AppException.badRequest("INVALID_PARAMETER", "householdId")
        }
        val user = currentUser.requireUser()
        val membership = members.findByIdHouseholdIdAndIdUserId(householdId, user.id)
            ?: throw AppException.forbidden("NOT_A_HOUSEHOLD_MEMBER")

        if (handler is HandlerMethod && requiresOwner(handler) && membership.role != HouseholdRole.owner) {
            throw AppException.forbidden("NOT_HOUSEHOLD_OWNER")
        }

        request.setAttribute(ATTR_HOUSEHOLD_ID, householdId)
        request.setAttribute(ATTR_HOUSEHOLD_ROLE, membership.role)
        MDC.put("householdId", householdId.toString())
        return true
    }

    private fun requiresOwner(handler: HandlerMethod): Boolean =
        handler.hasMethodAnnotation(RequireHouseholdOwner::class.java) ||
            handler.beanType.isAnnotationPresent(RequireHouseholdOwner::class.java)
}
