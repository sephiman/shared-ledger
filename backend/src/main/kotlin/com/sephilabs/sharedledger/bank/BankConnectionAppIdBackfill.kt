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
 * This deliberately lives in code rather than in V030. A migration that interpolates a
 * deployment-time value is a syntax error waiting to happen — any apostrophe in the id breaks
 * startup for a fresh install — and it makes the same migration mean different things per
 * environment. Here the id is a bound parameter.
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
