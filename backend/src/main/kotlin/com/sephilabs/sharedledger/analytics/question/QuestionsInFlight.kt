package com.sephilabs.sharedledger.analytics.question

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One question at a time per user, in this instance's memory only: nothing about a question is stored.
 *  Assumes a single backend instance, as the rest of the app does. */
@Component
class QuestionsInFlight {

    private val users = ConcurrentHashMap.newKeySet<UUID>()

    fun begin(userId: UUID): Boolean = users.add(userId)

    fun end(userId: UUID) {
        users.remove(userId)
    }
}
