package com.sephilabs.sharedledger

import org.hibernate.cfg.AvailableSettings
import org.hibernate.resource.jdbc.spi.StatementInspector
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/** Captures the SQL Hibernate prepares on the calling thread while [record] runs. Thread-local because test
 *  classes run concurrently against the one shared context; MockMvc serves a request on the test's own thread. */
object SqlRecorder {
    private val recording = ThreadLocal<MutableList<String>?>()

    fun <T> record(block: () -> T): Pair<T, List<String>> {
        val statements = mutableListOf<String>()
        recording.set(statements)
        try {
            return block() to statements.toList()
        } finally {
            recording.remove()
        }
    }

    internal fun capture(sql: String) {
        recording.get()?.add(sql)
    }
}

// A class, not the object itself: StatementInspector is Serializable, and a serializable singleton would need readResolve.
private class RecordingStatementInspector : StatementInspector {
    override fun inspect(sql: String): String {
        SqlRecorder.capture(sql)
        return sql
    }
}

@TestConfiguration
class SqlRecorderConfig {
    @Bean
    fun sqlRecorderCustomizer() = HibernatePropertiesCustomizer { props ->
        props[AvailableSettings.STATEMENT_INSPECTOR] = RecordingStatementInspector()
    }
}
