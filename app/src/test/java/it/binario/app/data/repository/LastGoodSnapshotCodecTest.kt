package it.binario.app.data.repository

import it.binario.app.data.DataConfidence
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Audit proposal 1: the saved copy keeps the timetable and drops every live claim. */
class LastGoodSnapshotCodecTest {
    private val route = SavedRoute("r", "Roma–Napoli", Station("roma", "Roma Termini", "Roma"), Station("napoli", "Napoli Centrale", "Napoli"))
    private val savedAt = Instant.parse("2026-11-03T08:00:00Z").toEpochMilli()

    @Test fun `a route result comes back as a cached timetable without delays or platforms`() {
        val live = TrainService("s1", "9611", RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, "Roma Termini", "Napoli Centrale",
            "10:05", "10:20", "12", 15, ServiceStatus.DELAYED,
            listOf(TrainStop("Roma Termini", "10:05", "10:20", "12", 15, false, operationalStatusVerified = true),
                TrainStop("Napoli Centrale", "11:15", "11:30", null, 15, false)),
            operationalStatusVerified = true, scheduledDepartureDateTime = LocalDateTime.of(2026, 11, 3, 10, 5))
        val snapshot = RouteSnapshot(route, Direction.OUTBOUND, listOf(live), "RFI + ViaggiaTreno", savedAt, savedAt + 30_000,
            Freshness.FRESH, confidence = DataConfidence.OPERATOR_PRIMARY)

        val copy = LastGoodSnapshotCodec.decodeRoute(LastGoodSnapshotCodec.encodeRoute(snapshot), route, Direction.OUTBOUND)!!
        val service = copy.services.single()
        assertEquals(DataConfidence.CACHED, copy.confidence)
        assertEquals(Freshness.STALE, copy.freshness)
        assertEquals(savedAt, copy.retrievedAtEpochMillis)
        assertEquals("10:05", service.scheduledDeparture)
        assertEquals("10:05", service.expectedDeparture)
        assertEquals(0, service.delayMinutes)
        assertNull(service.platform)
        assertFalse(service.operationalStatusVerified)
        assertEquals(listOf("10:05", "11:15"), service.stops.map { it.expected })
        assertEquals(LocalDateTime.of(2026, 11, 3, 10, 5), service.scheduledDepartureDateTime)
    }

    @Test fun `a board comes back with its scheduled rows only`() {
        val zone = ZoneId.of("Europe/Rome")
        val ref = SourceRef(SourceId("rfi-station-board"), observedAt = Instant.ofEpochMilli(savedAt), fetchedAt = Instant.ofEpochMilli(savedAt),
            confidence = Confidence.INFRASTRUCTURE_PRIMARY, traceId = "t")
        val entry = StationBoardEntry(TrainRunId("rfi:2345"), LocalDateTime.of(2026, 11, 3, 9, 45).atZone(zone),
            SourcedValue(LocalDateTime.of(2026, 11, 3, 10, 15).atZone(zone), ref), StationId("published:napoli"), "Napoli Centrale",
            publicNumber = "2345", operatorId = OperatorId("trenitalia"), platform = SourcedValue("7", ref), lineLabel = "R", serviceKind = ServiceKind.REGIONAL)
        val board = StationBoardAggregate(route.outwardOrigin, BoardKind.DEPARTURES, listOf(entry), listOf("RFI"), emptySet(), Instant.ofEpochMilli(savedAt))

        val copy = LastGoodSnapshotCodec.decodeBoard(LastGoodSnapshotCodec.encodeBoard(board), route.outwardOrigin, BoardKind.DEPARTURES)!!
        val row = copy.entries.single()
        assertEquals(entry.scheduledTime, row.scheduledTime)
        assertEquals("2345", row.publicNumber)
        assertEquals("Napoli Centrale", row.destinationOrOriginLabel)
        assertEquals(ServiceKind.REGIONAL, row.serviceKind)
        assertNull(row.expectedTime)
        assertNull(row.platform)
        assertEquals(listOf("RFI"), copy.providerNames)
    }

    @Test fun `an unreadable or other-version copy is ignored`() {
        assertNull(LastGoodSnapshotCodec.decodeRoute("{}", route, Direction.OUTBOUND))
        assertNull(LastGoodSnapshotCodec.decodeRoute("not json", route, Direction.OUTBOUND))
        assertNull(LastGoodSnapshotCodec.decodeBoard("""{"v":99,"savedAt":1}""", route.outwardOrigin, BoardKind.DEPARTURES))
    }
}
