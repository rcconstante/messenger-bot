package com.pagerelay.bot.automation

class CallGuard(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    sealed class AcquireResult {
        data class Allowed(val requestId: String) : AcquireResult()
        data class Rejected(
            val reason: String,
            val remainingCooldownSeconds: Long = 0L
        ) : AcquireResult()
    }

    private val lock = Any()
    private var activeRequestId: String? = null
    private var lastCallTimestampMillis: Long = 0L
    private val recentRequestTimestamps = LinkedHashMap<String, Long>()
    private val recentDedupeTimestamps = LinkedHashMap<String, Long>()

    fun tryAcquire(
        requestId: String,
        dedupeKey: String? = null,
        cooldownSeconds: Int,
        enforceCooldown: Boolean = true
    ): AcquireResult = synchronized(lock) {
        val now = clock()
        val cooldownMillis = cooldownSeconds.coerceAtLeast(0) * 1000L
        pruneExpiredLocked(now, cooldownMillis.coerceAtLeast(DEFAULT_PRUNE_WINDOW_MS))

        val currentActive = activeRequestId
        if (currentActive != null) {
            return AcquireResult.Rejected(
                reason = "Another Messenger call automation ($currentActive) is already in progress."
            )
        }

        val prevReqTs = recentRequestTimestamps[requestId]
        if (prevReqTs != null && (now - prevReqTs) < cooldownMillis.coerceAtLeast(DEFAULT_PRUNE_WINDOW_MS)) {
            return AcquireResult.Rejected(
                reason = "Duplicate call request ($requestId) already handled."
            )
        }

        val normalizedKey = dedupeKey?.trim()?.takeIf { it.isNotEmpty() }
        if (enforceCooldown && normalizedKey != null && cooldownMillis > 0L) {
            val prevKeyTs = recentDedupeTimestamps[normalizedKey]
            if (prevKeyTs != null) {
                val elapsed = now - prevKeyTs
                if (elapsed in 0 until cooldownMillis) {
                    val remainingSec = ((cooldownMillis - elapsed) + 999L) / 1000L
                    return AcquireResult.Rejected(
                        reason = "Duplicate trigger for '$normalizedKey' suppressed during cooldown (${remainingSec}s remaining).",
                        remainingCooldownSeconds = remainingSec
                    )
                }
            }
        }

        if (enforceCooldown && cooldownMillis > 0L && lastCallTimestampMillis > 0L) {
            val elapsed = now - lastCallTimestampMillis
            if (elapsed in 0 until cooldownMillis) {
                val remainingSec = ((cooldownMillis - elapsed) + 999L) / 1000L
                return AcquireResult.Rejected(
                    reason = "Duplicate call cooldown active (${remainingSec}s remaining).",
                    remainingCooldownSeconds = remainingSec
                )
            }
        }

        activeRequestId = requestId
        recentRequestTimestamps[requestId] = now
        if (normalizedKey != null && enforceCooldown) {
            recentDedupeTimestamps[normalizedKey] = now
        }
        AcquireResult.Allowed(requestId)
    }

    fun release(
        requestId: String? = null,
        recordCooldown: Boolean = true
    ) = synchronized(lock) {
        if (requestId == null || activeRequestId == requestId) {
            activeRequestId = null
        }
        if (recordCooldown) {
            lastCallTimestampMillis = clock()
        }
    }

    fun isCallActive(): Boolean = synchronized(lock) {
        activeRequestId != null
    }

    fun reset() = synchronized(lock) {
        activeRequestId = null
        lastCallTimestampMillis = 0L
        recentRequestTimestamps.clear()
        recentDedupeTimestamps.clear()
    }

    private fun pruneExpiredLocked(now: Long, retentionMillis: Long) {
        val cutoff = now - retentionMillis
        recentRequestTimestamps.entries.removeAll { it.value < cutoff }
        recentDedupeTimestamps.entries.removeAll { it.value < cutoff }
    }

    companion object {
        private const val DEFAULT_PRUNE_WINDOW_MS = 60_000L
    }
}
