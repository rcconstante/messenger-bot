package com.pagerelay.bot

import com.pagerelay.bot.automation.CallGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallGuardTest {

    @Test
    fun `active call lock prevents concurrent duplicate calls`() {
        var now = 1_000L
        val guard = CallGuard(clock = { now })

        val first = guard.tryAcquire(
            requestId = "req-1",
            dedupeKey = "ops secondary",
            cooldownSeconds = 60
        )
        assertTrue(first is CallGuard.AcquireResult.Allowed)
        assertTrue(guard.isCallActive())

        now += 500L
        val concurrent = guard.tryAcquire(
            requestId = "req-2",
            dedupeKey = "other recipient",
            cooldownSeconds = 60
        )
        assertTrue(concurrent is CallGuard.AcquireResult.Rejected)

        guard.release("req-1", recordCooldown = true)
        assertFalse(guard.isCallActive())
    }

    @Test
    fun `cooldown blocks rapid repeat triggers and allows after expiry`() {
        var now = 10_000L
        val guard = CallGuard(clock = { now })

        val first = guard.tryAcquire(
            requestId = "req-1",
            dedupeKey = "ops secondary",
            cooldownSeconds = 90
        )
        assertTrue(first is CallGuard.AcquireResult.Allowed)

        now += 5_000L // t = 15s
        guard.release("req-1", recordCooldown = true)

        now += 30_000L // t = 45s (30s after release, cooldown = 90s)
        val blocked = guard.tryAcquire(
            requestId = "req-2",
            dedupeKey = "ops secondary",
            cooldownSeconds = 90
        )
        assertTrue(blocked is CallGuard.AcquireResult.Rejected)
        val rejected = blocked as CallGuard.AcquireResult.Rejected
        assertTrue(rejected.remainingCooldownSeconds > 0L)

        now += 61_000L // t = 106s (> 90s after release)
        val allowedAfterExpiry = guard.tryAcquire(
            requestId = "req-3",
            dedupeKey = "ops secondary",
            cooldownSeconds = 90
        )
        assertTrue(allowedAfterExpiry is CallGuard.AcquireResult.Allowed)
    }

    @Test
    fun `emergency stop reset clears active lock and cooldown immediately`() {
        var now = 50_000L
        val guard = CallGuard(clock = { now })

        val first = guard.tryAcquire(
            requestId = "req-1",
            dedupeKey = "ops secondary",
            cooldownSeconds = 120
        )
        assertTrue(first is CallGuard.AcquireResult.Allowed)

        guard.reset()
        assertFalse(guard.isCallActive())

        now += 1_000L
        val afterReset = guard.tryAcquire(
            requestId = "req-2",
            dedupeKey = "ops secondary",
            cooldownSeconds = 120
        )
        assertTrue(afterReset is CallGuard.AcquireResult.Allowed)
    }
}
