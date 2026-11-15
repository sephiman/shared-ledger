package com.sephilabs.sharedledger.fire.ai

import org.springframework.stereotype.Component
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Bounds per-user AI plan review & follow-up calls so one user cannot flood the provider. */
@Component
class FireAiRateLimiter {

    private val windows = ConcurrentHashMap<UUID, ArrayDeque<Long>>()

    fun tryAcquire(userId: UUID, nowMs: Long = System.currentTimeMillis()): Boolean {
        val queue = windows.computeIfAbsent(userId) { ArrayDeque() }
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
