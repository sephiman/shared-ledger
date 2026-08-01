package com.sephilabs.sharedledger.identity.auth

import io.github.bucket4j.Bucket
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Keyed rate-limit buckets that evict idle keys, so a stream of distinct keys (spoofed source IPs on
 *  login) can't grow the map without bound. Eviction is lossless: [retention] is the longest bandwidth
 *  window, so an evicted key had fully refilled. The sweep piggy-backs on [tryAcquire] — no background thread. */
class EvictingBucketStore<K : Any>(
    private val retention: Duration,
    private val build: () -> Bucket,
) {
    private class Entry(val bucket: Bucket, @Volatile var lastAccessNanos: Long)

    private val entries = ConcurrentHashMap<K, Entry>()
    private val nextSweepNanos = AtomicLong(Long.MIN_VALUE)

    fun tryAcquire(key: K): Boolean {
        val now = System.nanoTime()
        val entry = entries.compute(key) { _, existing ->
            (existing ?: Entry(build(), now)).also { it.lastAccessNanos = now }
        }!!
        maybeSweep(now)
        return entry.bucket.tryConsume(1)
    }

    fun size(): Int = entries.size

    private fun maybeSweep(now: Long) {
        val due = nextSweepNanos.get()
        if (now < due) return
        // Only one thread wins the CAS and runs the sweep; the rest skip it.
        if (!nextSweepNanos.compareAndSet(due, now + SWEEP_INTERVAL.toNanos())) return
        val cutoff = now - retention.toNanos()
        entries.entries.removeIf { it.value.lastAccessNanos < cutoff }
    }

    private companion object {
        val SWEEP_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
