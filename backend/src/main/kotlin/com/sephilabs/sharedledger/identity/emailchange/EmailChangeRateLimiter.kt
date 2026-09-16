package com.sephilabs.sharedledger.identity.emailchange

import com.sephilabs.sharedledger.config.AppProperties
import com.sephilabs.sharedledger.identity.auth.EvictingBucketStore
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/** Bounds outgoing mail per account: the request is authenticated, so the caller is already known and the
 *  limit exists to stop one account from mailing arbitrary addresses. */
@Component
class EmailChangeRateLimiter(private val props: AppProperties) {

    private val byUser = EvictingBucketStore<UUID>(retention = Duration.ofHours(1)) {
        val capacity = props.emailChange.perHourPerUser
        Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(capacity).refillIntervally(capacity, Duration.ofHours(1)).build())
            .build()
    }

    fun tryAcquire(userId: UUID): Boolean = byUser.tryAcquire(userId)
}
