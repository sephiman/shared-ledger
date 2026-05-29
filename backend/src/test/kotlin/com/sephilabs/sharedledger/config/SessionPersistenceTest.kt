package com.sephilabs.sharedledger.config

import com.sephilabs.sharedledger.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.session.jdbc.JdbcIndexedSessionRepository

/**
 * Proves Spring Session JDBC is actually engaged (see [SessionConfig]) rather than falling back to
 * in-memory sessions. Injecting [JdbcIndexedSessionRepository] only succeeds if @EnableJdbcHttpSession
 * registered it, and saving a session must write a row to the Postgres SPRING_SESSION table — that
 * persistence is what lets sessions survive a backend restart.
 */
class SessionPersistenceTest @Autowired constructor(
    private val sessionRepository: JdbcIndexedSessionRepository,
    private val jdbc: JdbcTemplate,
) : IntegrationTestBase() {

    @Test
    fun `saving a session persists a row to the postgres spring_session table`() {
        val before = count("SELECT count(*) FROM spring_session")

        val sessionId = createAndSaveSession(sessionRepository)

        assertThat(count("SELECT count(*) FROM spring_session")).isGreaterThan(before)
        assertThat(count("SELECT count(*) FROM spring_session WHERE session_id = ?", sessionId)).isEqualTo(1)
    }

    @Test
    fun `immediate flush mode persists the session at creation time, before save is called`() {
        // FlushMode.IMMEDIATE must write the row during createSession, not defer it to save().
        // Under the default ON_SAVE this row would not yet exist — that deferral is the read-after-write
        // race that 401s the SPA's first request after login. Asserting visibility pre-save guards it.
        val sessionId = createWithoutSaving(sessionRepository)

        assertThat(count("SELECT count(*) FROM spring_session WHERE session_id = ?", sessionId)).isEqualTo(1)
    }

    // Both helpers are generic over the repository's concrete session type, which is package-private
    // and cannot be named directly.
    private fun <S : Session> createAndSaveSession(repo: SessionRepository<S>): String {
        val session = repo.createSession()
        session.setAttribute("probe", "value")
        repo.save(session)
        return session.id
    }

    private fun <S : Session> createWithoutSaving(repo: SessionRepository<S>): String =
        repo.createSession().id

    private fun count(sql: String, vararg args: Any): Long =
        jdbc.queryForObject(sql, Long::class.javaObjectType, *args) ?: 0L
}
