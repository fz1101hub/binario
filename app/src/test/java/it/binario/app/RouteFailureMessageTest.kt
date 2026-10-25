package it.binario.app

import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.Station
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteFailureMessageTest {
    @Test
    fun `actionable route failures keep their own message instead of the generic one`() {
        assertEquals(UiMessage(UiMessageKey.RAIL_DATA_UNAVAILABLE), RouteFailureReason.Unavailable.uiMessage())
        assertEquals(UiMessage(UiMessageKey.RAIL_NETWORK_UNAVAILABLE), RouteFailureReason.NetworkUnavailable.uiMessage())
        assertEquals(
            UiMessage(UiMessageKey.ROUTE_SCHEDULE_OUTSIDE_VALIDITY, LocalDate.of(2026, 6, 14), LocalDate.of(2026, 12, 12)),
            RouteFailureReason.ScheduleOutsideValidity(LocalDate.of(2026, 6, 14), LocalDate.of(2026, 12, 12)).uiMessage()
        )
        assertEquals(UiMessage(UiMessageKey.ROUTE_NOT_COVERED), RouteFailureReason.RouteNotCovered.uiMessage())
        assertEquals(
            UiMessage(UiMessageKey.ROUTE_SCHEDULE_EXPIRED, LocalDate.of(2026, 12, 13)),
            RouteFailureReason.ScheduleExpired(LocalDate.of(2026, 12, 13)).uiMessage()
        )
        assertEquals(
            UiMessage(UiMessageKey.ROUTE_NO_SERVICE_AFTER, LocalDate.of(2026, 10, 6), LocalTime.of(22, 30)),
            RouteFailureReason.NoServiceAfter(LocalDate.of(2026, 10, 6), LocalTime.of(22, 30)).uiMessage()
        )
    }

    @Test
    fun `successful searches warn about field conflicts and let an unofficial source outrank them`() {
        val snapshot = RouteSnapshot(
            route = SavedRoute("roma-napoli", "Roma Napoli", Station("roma", "Roma Termini", "Roma"), Station("napoli", "Napoli Centrale", "Napoli")),
            direction = Direction.OUTBOUND,
            services = emptyList(),
            providerName = "RFI",
            retrievedAtEpochMillis = 1L,
            expiresAtEpochMillis = 2L,
            freshness = Freshness.FRESH
        )

        assertNull(snapshot.advisoryMessage())
        assertEquals(
            UiMessage(UiMessageKey.ROUTE_SOURCE_CONFLICT),
            snapshot.copy(qualityWarnings = setOf(SnapshotWarning.SOURCE_CONFLICT, SnapshotWarning.STALE_SOURCE)).advisoryMessage()
        )
        assertEquals(
            UiMessage(UiMessageKey.ROUTE_COVERAGE_FALLBACK),
            snapshot.copy(coverageFallback = true, qualityWarnings = setOf(SnapshotWarning.SOURCE_CONFLICT)).advisoryMessage()
        )
    }
}
