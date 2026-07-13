package com.sephilabs.sharedledger.identity.auth

import com.sephilabs.sharedledger.config.AppProperties
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class LoginRateLimiter(private val props: AppProperties) {

    // Keyed by client IP, which is attacker-influenced, so the store evicts idle keys to stay bounded.
    private val store = EvictingBucketStore<String>(retention = Duration.ofHours(1), build = ::build)

    fun tryAcquire(key: String): Boolean = store.tryAcquire(key)

    private fun build(): Bucket {
        val perMinute = Bandwidth.builder()
            .capacity(props.security.loginRate.perMinute)
            .refillIntervally(props.security.loginRate.perMinute, Duration.ofMinutes(1))
            .build()
        val perHour = Bandwidth.builder()
            .capacity(props.security.loginRate.perHour)
            .refillIntervally(props.security.loginRate.perHour, Duration.ofHours(1))
            .build()
        return Bucket.builder().addLimit(perMinute).addLimit(perHour).build()
    }
}
