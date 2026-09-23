package it.binario.app.data.live

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertNull
import org.junit.Test

class StationBoardInteropTest {
    @Test
    fun `operatore other resta sconosciuto invece di diventare un id fittizio`() {
        val fetchedAt = Instant.parse("2026-09-04T06:00:00Z")
        val board = LegacyStationBoardInterop.build(
            sourceId = SourceId("test-board"),
            confidence = Confidence.DERIVED,
            stationId = StationId("roma-termini"),
            kind = BoardKind.DEPARTURES,
            from = Instant.parse("2026-09-04T05:00:00Z"),
            to = Instant.parse("2026-09-04T09:00:00Z"),
            services = listOf(
                TrainService(
                    id = "unknown:1",
                    number = "123",
                    operator = RailOperator.OTHER,
                    trainCategory = TrainCategory.REGIONALE,
                    origin = "Roma Termini",
                    destination = "Napoli Centrale",
                    scheduledDeparture = "08:00",
                    expectedDeparture = "08:00",
                    platform = null,
                    delayMinutes = 0,
                    status = ServiceStatus.ON_TIME,
                    stops = emptyList()
                )
            ),
            fetchedAt = fetchedAt,
            ttl = Duration.ofMinutes(2)
        )

        assertNull(board.entries.single().operatorId)
    }
}
