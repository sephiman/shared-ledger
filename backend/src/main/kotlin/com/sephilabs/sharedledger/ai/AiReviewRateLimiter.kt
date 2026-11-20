package com.sephilabs.sharedledger.ai

import org.springframework.stereotype.Component
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Bounds per-user AI review & follow-up calls per feature so one user cannot flood the provider. */
@Component
class AiReviewRateLimiter {

    private data class Key(val feature: String, val userId: UUID)

    private val windows = ConcurrentHashMap<Key, ArrayDeque<Long>>()

    fun tryAcquire(feature: String, userId: UUID, nowMs: Long = System.currentTimeMillis()): Boolean {
        val queue = windows.computeIfAbsent(Key(feature, userId)) { ArrayDeque() }
        synchronized(queue) {
            val cutoff = nowMs - WINDOW_MS
            while (queue.isNotEmpty() && queue.peekFirst() <= cutoff) {
                queue.removeFirst()
            }
            if (queue.size >= MAX_REQUESTS_PER_WINDOW) return false
            queue.addLast(nowMs)
            return true
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_REQUESTS_PER_WINDOW = 10
    }
}
