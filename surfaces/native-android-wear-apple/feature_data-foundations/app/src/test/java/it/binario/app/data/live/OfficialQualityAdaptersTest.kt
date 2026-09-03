package it.binario.app.data.live

import it.binario.app.data.core.AlertSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OfficialQualityAdaptersTest {
    @Test
    fun `il parser RFI RSS crea avvisi con provenienza infrastrutturale`() {
        val adapter = RfiInfomobilityRssAdapter()
        val alerts = adapter.parseUpdates(
            """<?xml version="1.0"?><rss><channel><item><title>Circolazione sospesa fra Alfa e Beta</title><description>Intervento tecnico in corso</description><link>https://example.test/rfi/1</link></item></channel></rss>""",
            Instant.parse("2026-08-14T10:00:00Z")
        )

        assertEquals(1, alerts.size)
        assertEquals(AlertSeverity.SEVERE, alerts.single().severity)
        assertEquals("rfi-infomobility-rss", alerts.single().source.sourceId.value)
    }

    @Test
    fun `il parser STA normalizza Trip Updates senza inventare un evento effettivo`() {
        val adapter = StaRealtimeAdapter()
        val updates = adapter.parseTripUpdates(
            """{"header":{"timestamp":1786724040},"entity":[{"id":"tu1","trip_update":{"trip":{"trip_id":"run-1"},"stop_time_update":[{"stop_id":"it:22021:1","stop_sequence":3,"departure":{"delay":120,"time":1786724160}}]}}]}""",
            Instant.parse("2026-08-14T10:00:00Z")
        )

        assertEquals(1, updates.size)
        assertEquals("sta:run-1", updates.single().runId.value)
        assertEquals("it:22021:1", updates.single().sourceStopId)
        assertEquals(3, updates.single().stopSequence)
        assertEquals(120L, updates.single().officialDelay?.value?.seconds)
        assertTrue(updates.single().predictedAt != null)
        assertEquals(null, updates.single().actualAt)
    }

    @Test
    fun `il parser STA conserva alert e posizioni GTFS realtime`() {
        val adapter = StaRealtimeAdapter()
        val alerts = adapter.parseAlerts(
            """{"header":{"timestamp":1786724040},"entity":[{"id":"a1","alert":{"effect":"NO_SERVICE","severity_level":"WARNING","header_text":{"translation":[{"text":"Cantiere","language":"it"}]},"informed_entity":[{"stop_id":"it:22021:1"}]}}]}""",
            Instant.parse("2026-08-14T10:00:00Z")
        )
        val positions = adapter.parsePositions(
            """{"entity":[{"id":"v1","vehicle":{"trip":{"trip_id":"run-1"},"position":{"latitude":46.5,"longitude":11.3},"timestamp":1786724040}}]}""",
            Instant.parse("2026-08-14T10:00:00Z")
        )

        assertEquals(1, alerts.size)
        assertEquals(AlertSeverity.SEVERE, alerts.single().severity)
        assertEquals("sta:it:22021:1", alerts.single().affectedStations.single().value)
        assertEquals(1, positions.size)
        assertEquals("sta:run-1", positions.single().runId.value)
        assertTrue(positions.single().point.latitude > 46.0)
    }
}
