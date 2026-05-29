package com.sephilabs.sharedledger.config

import org.springframework.context.annotation.Configuration
import org.springframework.session.FlushMode
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession

/**
 * Persist HTTP sessions in PostgreSQL via Spring Session JDBC so they survive backend restarts
 * and work across instances. Enabled explicitly because the classpath auto-configuration was not
 * engaging under Spring Boot 4 — without this, sessions live only in Tomcat memory and every
 * restart logs everyone out. Table is the SPRING_SESSION schema created by Flyway V001; the
 * 30-day inactive interval matches the session cookie max-age.
 *
 * FlushMode.IMMEDIATE writes the session row when it is created, inside the login request, instead
 * of deferring the insert to request completion (the default ON_SAVE). With durable DB sessions the
 * SPA fires its first authenticated request within milliseconds of receiving the login response —
 * faster than the deferred insert becomes visible — so those requests 401 until a retry. Writing
 * immediately closes that read-after-write window. (In-memory sessions never hit this because memory
 * is instantly visible; the race only surfaced once sessions became durable.)
 */
@Configuration
@EnableJdbcHttpSession(
    maxInactiveIntervalInSeconds = 2_592_000,
    tableName = "SPRING_SESSION",
    flushMode = FlushMode.IMMEDIATE,
)
class SessionConfig
