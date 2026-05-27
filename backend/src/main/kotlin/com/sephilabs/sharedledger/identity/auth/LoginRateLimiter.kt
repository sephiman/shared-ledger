package com.sephilabs.sharedledger.identity.auth

import com.sephilabs.sharedledger.config.AppProperties
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

@Component
class LoginRateLimiter(private val props: AppProperties) {

    private val buckets: MutableMap<String, Bucket> = ConcurrentHashMap()

    fun tryAcquire(key: String): Boolean {
        val bucket = buckets.computeIfAbsent(key) { build() }
        return bucket.tryConsume(1)
    }

    fun reset(key: String) {
        buckets.remove(key)
    }

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
