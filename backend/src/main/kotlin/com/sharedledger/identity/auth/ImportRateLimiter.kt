package com.sharedledger.identity.auth

import com.sharedledger.config.AppProperties
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class ImportRateLimiter(private val props: AppProperties) {

    private val buckets: MutableMap<UUID, Bucket> = ConcurrentHashMap()

    fun tryAcquire(userId: UUID): Boolean {
        val bucket = buckets.computeIfAbsent(userId) { build() }
        return bucket.tryConsume(1)
    }

    private fun build(): Bucket {
        val perHour = Bandwidth.builder()
            .capacity(props.security.importRate.perHour)
            .refillIntervally(props.security.importRate.perHour, Duration.ofHours(1))
            .build()
        return Bucket.builder().addLimit(perHour).build()
    }
}
