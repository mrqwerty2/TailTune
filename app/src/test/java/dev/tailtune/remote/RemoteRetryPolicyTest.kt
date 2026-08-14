package dev.tailtune.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteRetryPolicyTest {
    @Test
    fun exponentialBackoffCapsAtMaximum() {
        assertEquals(1_000L, RemoteRetryPolicy.delayForAttempt(1))
        assertEquals(2_000L, RemoteRetryPolicy.delayForAttempt(2))
        assertEquals(4_000L, RemoteRetryPolicy.delayForAttempt(3))
        assertEquals(8_000L, RemoteRetryPolicy.delayForAttempt(4))
        assertEquals(16_000L, RemoteRetryPolicy.delayForAttempt(5))
        assertEquals(30_000L, RemoteRetryPolicy.delayForAttempt(6))
        assertEquals(30_000L, RemoteRetryPolicy.delayForAttempt(100))
    }

    @Test
    fun nonPositiveAttemptUsesFirstDelay() {
        assertEquals(1_000L, RemoteRetryPolicy.delayForAttempt(0))
        assertEquals(1_000L, RemoteRetryPolicy.delayForAttempt(-4))
    }
}
