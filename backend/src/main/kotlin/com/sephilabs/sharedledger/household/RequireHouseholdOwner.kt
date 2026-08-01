package com.sephilabs.sharedledger.household

/** Requires the caller to hold [HouseholdRole.owner] on the `{householdId}` path variable. Enforced by
 *  [HouseholdAccessInterceptor]. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequireHouseholdOwner
