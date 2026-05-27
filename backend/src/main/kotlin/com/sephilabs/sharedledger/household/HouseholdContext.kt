package com.sephilabs.sharedledger.household

import com.sephilabs.sharedledger.common.errors.AppException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class HouseholdContext(private val request: HttpServletRequest) {

    fun householdId(): UUID =
        request.getAttribute(ATTR_HOUSEHOLD_ID) as? UUID
            ?: throw AppException.forbidden("NOT_A_HOUSEHOLD_MEMBER")

    fun role(): HouseholdRole =
        request.getAttribute(ATTR_HOUSEHOLD_ROLE) as? HouseholdRole
            ?: throw AppException.forbidden("NOT_A_HOUSEHOLD_MEMBER")
}
