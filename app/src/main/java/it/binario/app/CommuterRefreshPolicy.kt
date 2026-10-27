package it.binario.app

import it.binario.app.data.DataConfidence
import it.binario.app.data.Freshness
import it.binario.app.data.RouteSnapshot

/** Presentation state, never a substitute for an operator's observation. */
internal enum class SnapshotAvailability { LIVE, SCHEDULED, STALE, DEMONSTRATION, UNAVAILABLE }

internal fun RouteSnapshot.availabilityAt(now: Long): SnapshotAvailability = when {
    confidence == DataConfidence.DEMONSTRATION -> SnapshotAvailability.DEMONSTRATION
    freshness == Freshness.UNAVAILABLE -> SnapshotAvailability.UNAVAILABLE
    expiresAtEpochMillis <= now || freshness == Freshness.STALE ||
        sourceObservedAtEpochMillis > now + 60_000L -> SnapshotAvailability.STALE
    confidence == DataConfidence.STATIC_ONLY || services.none { it.operationalStatusVerified } ->
        SnapshotAvailability.SCHEDULED
    else -> SnapshotAvailability.LIVE
}

/** Foreground only. Retry live data even when the previous result was schedule-only. */
internal object CommuterRefreshPolicy {
    const val TICK_MILLIS = 15_000L
    const val LIVE_INTERVAL_MILLIS = 60_000L
    const val RETRY_INTERVAL_MILLIS = 2 * 60_000L

    fun shouldRefresh(
        followsCurrentTime: Boolean,
        loading: Boolean,
        lastAttempt: Long?,
        now: Long,
        failed: Boolean = false
    ): Boolean {
        if (!followsCurrentTime || loading) return false
        if (lastAttempt == null) return true
        val interval = when {
            failed -> RETRY_INTERVAL_MILLIS
            else -> LIVE_INTERVAL_MILLIS
        }
        return now >= lastAttempt && now - lastAttempt >= interval
    }
}
