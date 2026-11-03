package it.binario.app.data.live

import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DbTimetablesStationBoardAdapterTest {
    private val now = Instant.parse("2026-09-04T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `risolve EVA dalla ricerca stazione`() {
        val xml = """
            <stations>
              <station eva="8000261" name="München Hbf" ds100="MH"/>
              <station eva="8004136" name="München Ost" ds100="MOP"/>
            </stations>
        """.trimIndent().toByteArray()
        val adapter = DbTimetablesStationBoardAdapter(FakeFetcher(), clock)

        assertEquals("8000261", adapter.resolveEva(xml, "München Hbf"))
    }

    @Test
    fun `fonde Sollfahrplan e full changes in un tabellone canonico`() = runBlocking {
        val adapter = DbTimetablesStationBoardAdapter(FakeFetcher(), clock)
        val result = adapter.getStationBoard(
            StationBoardRequest(
                stationId = StationId("munchen-hbf"),
                stationName = "München Hbf",
                from = Instant.parse("2026-09-04T06:00:00Z"),
                to = Instant.parse("2026-09-04T08:30:00Z"),
                kind = BoardKind.DEPARTURES
            )
        )

        assertTrue(result is ProviderResult.Success)
        val entry = (result as ProviderResult.Success).value.entries.single()
        assertEquals("1218", entry.publicNumber)
        assertEquals("14", entry.platform?.value)
        assertEquals(RunStatus.DELAYED, entry.status?.value)
        assertEquals("Verona Porta Nuova", entry.destinationOrOriginLabel)
        assertEquals(12, java.time.Duration.between(entry.scheduledTime, entry.expectedTime!!.value).toMinutes())
    }

    @Test
    fun `percorso cambiato DB viene esposto come variazione operativa`() = runBlocking {
        val adapter = DbTimetablesStationBoardAdapter(FakeFetcher(changedPath = true), clock)
        val result = adapter.getStationBoard(
            StationBoardRequest(
                stationId = StationId("munchen-hbf"),
                stationName = "München Hbf",
                from = Instant.parse("2026-09-04T06:00:00Z"),
                to = Instant.parse("2026-09-04T08:30:00Z"),
                kind = BoardKind.DEPARTURES
            )
        ) as ProviderResult.Success

        val change = result.value.entries.single().routeChange?.value
        assertTrue(change != null)
        assertTrue(change!!.viaStopLabels.contains("Salzburg Hbf"))
    }

    private class FakeFetcher(private val changedPath: Boolean = false) : DbTimetablesFetcher {
        override suspend fun findStation(pattern: String): ByteArray =
            "<stations><station eva=\"8000261\" name=\"München Hbf\" ds100=\"MH\"/></stations>".toByteArray()

        override suspend fun planned(eva: String, date: String, hour: String): ByteArray = if (hour == "08") {
            """
                <timetable station="München Hbf">
                  <s id="trip-1218-2609040830-1">
                    <tl f="F" t="p" o="DB" c="EC" n="1218"/>
                    <dp pt="2609040830" pp="12" l="EC 1218" ppth="Kufstein|Innsbruck Hbf|Brennero/Brenner|Bolzano Bozen|Verona Porta Nuova"/>
                  </s>
                </timetable>
            """.trimIndent().toByteArray()
        } else "<timetable station=\"München Hbf\"/>".toByteArray()

        override suspend fun fullChanges(eva: String): ByteArray {
            val path = if (changedPath) {
                "Salzburg Hbf|Villach Hbf|Tarvisio Boscoverde|Udine|Venezia Santa Lucia"
            } else {
                "Kufstein|Innsbruck Hbf|Brennero/Brenner|Bolzano Bozen|Verona Porta Nuova"
            }
            return """
                <timetable station="München Hbf">
                  <s id="trip-1218-2609040830-1">
                    <tl f="F" t="p" o="DB" c="EC" n="1218"/>
                    <dp ct="2609040842" cp="14" l="EC 1218" cpth="$path"/>
                  </s>
                </timetable>
            """.trimIndent().toByteArray()
        }
    }
}
