package it.binario.app

import it.binario.app.data.TrainStop
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.repository.StationBoardRunDetails
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The run details of a station board stay on screen while they are read again (audit2 android-state-6). */
class StationBoardRunRetentionTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val loadedAt = 1_800_000_000_000L
    private val ref = SourceRef(
        SourceId("rfi-station-board"), observedAt = Instant.ofEpochMilli(loadedAt), fetchedAt = Instant.ofEpochMilli(loadedAt),
        expiresAt = Instant.ofEpochMilli(loadedAt + 60_000), confidence = Confidence.INFRASTRUCTURE_PRIMARY, traceId = "t"
    )
    private val entry = StationBoardEntry(
        TrainRunId("rfi:2345"), LocalDateTime.of(2026, 11, 3, 9, 45).atZone(zone),
        SourcedValue(LocalDateTime.of(2026, 11, 3, 9, 52).atZone(zone), ref), StationId("published:napoli"), "Napoli Centrale",
        publicNumber = "2345", platform = SourcedValue("7", ref)
    )
    private val details = StationBoardRunDetails(
        entry = entry, origin = "Roma Termini", destination = "Napoli Centrale",
        stops = (1..25).map { TrainStop("Fermata $it", "09:%02d".format(it), "09:%02d".format(it + 7), "3", 7, completed = it < 4, operationalStatusVerified = true) },
        providerName = "RFI", observedAt = Instant.ofEpochMilli(loadedAt)
    )

    @Test fun `the stops stay while the details are read again thirty seconds later`() {
        val kept = retainedRunDetails(details, loadedAt, loadedAt + 30_000)!!
        assertEquals(details.stops, kept.stops)
        assertEquals("7", kept.entry.platform?.value)
        assertNotNull(kept.entry.expectedTime)
    }

    @Test fun `after a failed reading the stops remain without the live claims that have expired`() {
        // Two failed readings later (the phone lost its network): the 25 stops are still listed, as a timetable.
        val kept = retainedRunDetails(details, loadedAt, loadedAt + STATION_RUN_DETAILS_MAX_AGE_MILLIS + 1)!!
        assertEquals(25, kept.stops.size)
        assertEquals(details.stops.map { it.station }, kept.stops.map { it.station })
        assertEquals(setOf(false), kept.stops.map { it.operationalStatusVerified }.toSet())
        assertNull(kept.entry.expectedTime)
        assertNull(kept.entry.platform)
        // The entry's own observations expire on their own clock, before the stops do.
        val between = retainedRunDetails(details, loadedAt, loadedAt + 90_000)!!
        assertNull(between.entry.expectedTime)
        assertEquals(setOf(true), between.stops.map { it.operationalStatusVerified }.toSet())
    }

    @Test fun `nothing is kept without details and an unknown age is never taken as current`() {
        assertNull(retainedRunDetails(null, loadedAt, loadedAt))
        assertEquals(setOf(false), retainedRunDetails(details, null, loadedAt)!!.stops.map { it.operationalStatusVerified }.toSet())
        // A wall clock set back does not make old details current again.
        assertEquals(setOf(false), retainedRunDetails(details, loadedAt, loadedAt - 5_000)!!.stops.map { it.operationalStatusVerified }.toSet())
    }
}
