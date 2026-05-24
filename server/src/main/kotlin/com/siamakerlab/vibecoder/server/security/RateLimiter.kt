package com.siamakerlab.vibecoder.server.security

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

/**
 * v0.56.0 — Phase 35 per-IP token bucket rate limiter.
 *
 * Token bucket semantics:
 *   - Each IP gets a bucket of [capacity] tokens.
 *   - Tokens refill at [refillTokensPerSecond] tokens / second up to [capacity].
 *   - Each request costs 1 token. If the bucket would go negative, the request is rejected
 *     with a `RetryAfter` in seconds.
 *
 * Implementation notes:
 *   - In-memory `ConcurrentHashMap<ip, Bucket>` — no persistence (restart wipes state, OK for
 *     short-lived rate limit window).
 *   - Buckets store the last sampling timestamp (`lastNanos`) and current token count as
 *     atomic longs. The lock-free refill formula is `tokens += elapsed * refillRate`.
 *   - LRU eviction: capped at [maxIps] (`MAX_IPS` constant) — when full, we drop the oldest
 *     entry. Single-user dev server with maybe a dozen IPs has no real eviction pressure.
 *
 * Trusted bypass:
 *   - Admin Bearer tokens skip the bucket entirely (admin operations like backup / install /
 *     restart shouldn't be throttled). The caller passes `isAdmin = true` to [tryAcquire].
 */
class RateLimiter(
    private val capacity: Int,
    private val refillTokensPerSecond: Double,
) {

    private data class Bucket(
        @Volatile var tokens: Double,
        @Volatile var lastNanos: Long,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val accessClock = AtomicLong(0)

    /**
     * Try to consume 1 token for [ip]. Returns `null` on success (request allowed),
     * or a `RejectionResult` carrying the recommended retry-after seconds on failure.
     */
    fun tryAcquire(ip: String, isAdmin: Boolean = false): RejectionResult? {
        if (isAdmin) return null
        if (ip.isBlank()) return null
        if (buckets.size > MAX_IPS) {
            // Hard guard — should never happen in single-user mode; just clear half.
            log.warn { "rate limiter overflow at ${buckets.size} IPs — clearing oldest half" }
            val toRemove = buckets.entries.sortedBy { it.value.lastNanos }.take(MAX_IPS / 2)
            toRemove.forEach { buckets.remove(it.key) }
        }
        val now = System.nanoTime()
        val bucket = buckets.computeIfAbsent(ip) {
            Bucket(tokens = capacity.toDouble(), lastNanos = now)
        }
        // Refill based on elapsed time, lock-free read.
        synchronized(bucket) {
            val elapsedSec = (now - bucket.lastNanos) / 1_000_000_000.0
            bucket.tokens = (bucket.tokens + elapsedSec * refillTokensPerSecond)
                .coerceAtMost(capacity.toDouble())
            bucket.lastNanos = now
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return null
            }
            // Compute how long until 1 token is available.
            val deficit = 1.0 - bucket.tokens
            val retryAfterSec = (deficit / refillTokensPerSecond).coerceAtLeast(1.0)
            return RejectionResult(retryAfterSeconds = retryAfterSec.toInt().coerceAtLeast(1))
        }
    }

    fun currentBucketCount(): Int = buckets.size

    /**
     * Per-IP snapshot for the /settings/rate-limit page. Sorted by last-seen DESC.
     */
    fun snapshot(): List<BucketSnapshot> = buckets.entries
        .sortedByDescending { it.value.lastNanos }
        .take(50)
        .map { (ip, b) ->
            BucketSnapshot(
                ip = ip,
                tokens = b.tokens.coerceAtLeast(0.0),
                capacity = capacity,
                lastSeenAgoMs = (System.nanoTime() - b.lastNanos) / 1_000_000,
            )
        }

    data class RejectionResult(val retryAfterSeconds: Int)

    data class BucketSnapshot(
        val ip: String,
        val tokens: Double,
        val capacity: Int,
        val lastSeenAgoMs: Long,
    )

    companion object {
        private const val MAX_IPS = 10_000
    }
}
