package com.sharedledger.bootstrap

import com.sharedledger.config.AppProperties
import com.sharedledger.household.Household
import com.sharedledger.household.HouseholdMember
import com.sharedledger.household.HouseholdMemberId
import com.sharedledger.household.HouseholdMemberRepository
import com.sharedledger.household.HouseholdRepository
import com.sharedledger.household.HouseholdRole
import com.sharedledger.identity.user.User
import com.sharedledger.identity.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Configuration
class BootstrapRunner(
    private val props: AppProperties,
    private val users: UserRepository,
    private val households: HouseholdRepository,
    private val members: HouseholdMemberRepository,
    private val encoder: PasswordEncoder,
    private val txManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(BootstrapRunner::class.java)

    @Bean
    fun bootstrap(): ApplicationRunner = ApplicationRunner {
        val tx = TransactionTemplate(txManager)
        tx.execute {
            if (users.count() > 0L) {
                log.info("Bootstrap skipped: users already exist")
                return@execute
            }
            val email = props.bootstrap.adminEmail
            val password = props.bootstrap.adminPassword
            if (email.isBlank() || password.isBlank()) {
                if (props.registration.mode == AppProperties.RegistrationMode.OPEN) {
                    log.warn("Bootstrap skipped: ADMIN_EMAIL/PASSWORD not set. First user must self-register (REGISTRATION_MODE=open).")
                    return@execute
                }
                error("ADMIN_EMAIL and ADMIN_PASSWORD are required for first-run bootstrap when REGISTRATION_MODE is not 'open'")
            }

            val admin = User(
                email = email.lowercase(),
                passwordHash = encoder.encode(password)!!,
                locale = props.bootstrap.householdLocale,
            )
            users.save(admin)

            val household = Household(
                name = props.bootstrap.householdName,
                currency = props.bootstrap.householdCurrency.uppercase(),
                defaultLocale = props.bootstrap.householdLocale,
            )
            households.save(household)
            members.save(HouseholdMember(HouseholdMemberId(household.id, admin.id), HouseholdRole.owner))
            log.info("Bootstrap created admin user {} and household {}", admin.email, household.name)
        }
    }
}
