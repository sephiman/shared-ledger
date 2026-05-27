package com.sephilabs.sharedledger.household

/**
 * Marks a controller method (or whole controller class) as requiring the caller
 * to have the [HouseholdRole.owner] role on the household identified by the
 * `{householdId}` path variable. Enforced by [HouseholdAccessInterceptor].
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequireHouseholdOwner
