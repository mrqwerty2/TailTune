package dev.tailtune.remote

/** Pure retry/backoff helper so service recovery can be unit-tested without Android. */
object RemoteRetryPolicy {
    fun delayForAttempt(
        attempt: Int,
        initialDelayMs: Long = 1_000L,
        maxDelayMs: Long = 30_000L,
        maxExponent: Int = 6
    ): Long {
        require(initialDelayMs > 0L) { "initialDelayMs must be positive" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs" }
        require(maxExponent >= 1) { "maxExponent must be >= 1" }

        val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(maxExponent - 1)
        var delay = initialDelayMs
        repeat(exponent) {
            if (delay >= maxDelayMs / 2L) return maxDelayMs
            delay *= 2L
        }
        return delay.coerceAtMost(maxDelayMs)
    }
}
