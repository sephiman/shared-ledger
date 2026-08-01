package com.sephilabs.sharedledger.config

import org.springframework.context.annotation.Configuration
import org.springframework.session.FlushMode
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession

/** Postgres-backed sessions (Boot 4 does not auto-configure it), table from Flyway V001, 30-day interval
 *  matching the cookie max-age. FlushMode.IMMEDIATE writes the row inside the login request: the SPA's
 *  first authenticated call beats a deferred ON_SAVE insert and would 401 until a retry. */
@Configuration
@EnableJdbcHttpSession(
    maxInactiveIntervalInSeconds = 2_592_000,
    tableName = "SPRING_SESSION",
    flushMode = FlushMode.IMMEDIATE,
)
class SessionConfig
