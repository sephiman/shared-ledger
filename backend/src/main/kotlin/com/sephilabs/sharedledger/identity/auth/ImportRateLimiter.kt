package com.sephilabs.sharedledger.identity.auth

import com.sephilabs.sharedledger.config.AppProperties
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class ImportRateLimiter(private val props: AppProperties) {

    // Keyed by user id (bounded by the user count), but still evicted so departed users don't linger.
    private val store = EvictingBucketStore<UUID>(retention = Duration.ofHours(1), build = ::build)

    fun tryAcquire(userId: UUID): Boolean = store.tryAcquire(userId)

    private fun build(): Bucket {
        val perHour = Bandwidth.builder()
            .capacity(props.security.importRate.perHour)
            .refillIntervally(props.security.importRate.perHour, Duration.ofHours(1))
            .build()
        return Bucket.builder().addLimit(perHour).build()
    }
}
