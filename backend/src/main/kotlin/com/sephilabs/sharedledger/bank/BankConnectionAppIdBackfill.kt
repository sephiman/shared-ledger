package com.sephilabs.sharedledger.bank

import com.sephilabs.sharedledger.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Attributes connections linked before credentials became per-household (V030) to the instance-wide
 * Enable Banking application they were authorized under, so an owner pasting that same application
 * into Settings -> Banks does not have to re-link.
 *
 * The stamp lives here rather than in the migration because the id is deployment configuration:
 * as a bound parameter it can hold any value, and the migration stays identical everywhere.
 *
 * Idempotent: only rows with no app id are touched, and a blank [AppProperties.EnableBanking.appId]
 * (the normal state, once an upgrade is done) skips the query entirely.
 */
@Configuration
class BankConnectionAppIdBackfill(
    private val props: AppProperties,
    private val connections: BankConnectionRepository,
    private val txManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(BankConnectionAppIdBackfill::class.java)

    @Bean
    fun backfillBankConnectionAppId(): ApplicationRunner = ApplicationRunner {
        val appId = props.enableBanking.appId.trim()
        if (appId.isEmpty()) return@ApplicationRunner

        val stamped = TransactionTemplate(txManager).execute { connections.stampMissingAppId(appId) } ?: 0
        if (stamped > 0) {
            log.info("Stamped {} unattributed bank connection(s) with legacy application id", stamped)
        }
    }
}
