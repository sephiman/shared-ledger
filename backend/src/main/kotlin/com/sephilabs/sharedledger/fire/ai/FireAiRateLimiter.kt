package com.sephilabs.sharedledger.fire.ai

import com.sephilabs.sharedledger.ai.AiReviewRateLimiter
import org.springframework.stereotype.Component
import java.util.UUID

/** Delegates FIRE review rate-limiting to the shared [AiReviewRateLimiter]. */
@Component
class FireAiRateLimiter(private val limiter: AiReviewRateLimiter) {

    fun tryAcquire(userId: UUID, nowMs: Long = System.currentTimeMillis()): Boolean =
        limiter.tryAcquire("fire_ai", userId, nowMs)
}
