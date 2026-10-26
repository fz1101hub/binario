package it.binario.app

import it.binario.app.data.DataConfidence
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuterRefreshPolicyTest {
    private val now = 1_800_000_000_000L
    private val snapshot = RouteSnapshot(
        route = SavedRoute("route", "Casa", Station("a", "A", "A"), Station("b", "B", "B")),
        direction = Direction.OUTBOUND, services = emptyList(), providerName = "Official GTFS",
        retrievedAtEpochMillis = now, expiresAtEpochMillis = now + 3_600_000,
        freshness = Freshness.FRESH, confidence = DataConfidence.STATIC_ONLY
    )

    @Test fun `a fresh static fetch is never live`() {
        assertEquals(SnapshotAvailability.SCHEDULED, snapshot.availabilityAt(now))
    }

    @Test fun `expiry boundary removes current status without another network response`() {
        assertEquals(SnapshotAvailability.STALE, snapshot.availabilityAt(snapshot.expiresAtEpochMillis))
        assertEquals(SnapshotAvailability.STALE, snapshot.copy(sourceObservedAtEpochMillis = now + 60_001).availabilityAt(now))
    }

    @Test fun `planned searches and in flight requests are never polled`() {
        assertFalse(CommuterRefreshPolicy.shouldRefresh(false, false, null, snapshot, now))
        assertFalse(CommuterRefreshPolicy.shouldRefresh(true, true, null, snapshot, now))
    }

    @Test fun `static schedules use a slower refresh interval`() {
        assertFalse(CommuterRefreshPolicy.shouldRefresh(true, false, now - 60_000, snapshot, now))
        assertTrue(CommuterRefreshPolicy.shouldRefresh(true, false, now - 300_000, snapshot, now))
    }

    @Test fun `failed fetches back off rather than hammering an unavailable source`() {
        assertFalse(CommuterRefreshPolicy.shouldRefresh(true, false, now - 60_000, null, now, failed = true))
        assertTrue(CommuterRefreshPolicy.shouldRefresh(true, false, now - 120_000, null, now, failed = true))
    }

    @Test fun `unavailable and demonstration states cannot become current operational data`() {
        assertEquals(SnapshotAvailability.UNAVAILABLE, snapshot.copy(freshness = Freshness.UNAVAILABLE).availabilityAt(now))
        assertEquals(SnapshotAvailability.DEMONSTRATION, snapshot.copy(confidence = DataConfidence.DEMONSTRATION).availabilityAt(now))
    }
}
