package it.binario.app.data

import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.live.RfiMonitorFetcher
import it.binario.app.data.live.RfiStationBoardAdapter
import it.binario.app.data.live.parseItaloPublicBoard
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.data.repository.StationBoardRealtimeReconciler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Times and numbers the data layer writes are data, read back by regexes, `LocalTime.parse` and the saved timetables:
 * they must keep ASCII digits when the app runs in Arabic or Burmese, whose default `%d` digits are not ASCII.
 */
class LocaleDigitsTest {
    private val saved = Locale.getDefault()
    private val rome = ZoneId.of("Europe/Rome")
    private val ascii = Regex("[0-9]{2}:[0-9]{2}")

    @After fun restoreLocale() = Locale.setDefault(saved)

    private fun eachNonAsciiLocale(block: (Locale) -> Unit) {
        for (tag in listOf("ar", "my")) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            // The precondition: in these languages a plain "%02d" would not produce ASCII digits.
            assertTrue(tag, !String.format("%02d", 7).matches(Regex("[0-9]+")))
            block(locale)
        }
    }

    @Test fun `formatTime keeps ASCII digits`() = eachNonAsciiLocale { locale ->
        assertEquals(locale.toLanguageTag(), "10:05", formatTime(605))
        assertEquals(locale.toLanguageTag(), "00:10", formatTime(24 * 60 + 10))
    }

    @Test fun `RFI and Italo expected times stay ASCII`() = eachNonAsciiLocale { locale ->
        val route = SavedRoute("t", "t", Station("roma-termini", "Roma Termini", "Roma"), Station("napoli", "Napoli Centrale", "Napoli"))
        val html = """<table><tr name="treno"><td id="RVettore"><img alt="TRENITALIA"/></td><td id="RCategoria"><img alt="Regionale"/></td>
            <td id="RTreno">2345</td><td id="RStazione">Napoli Centrale</td><td id="ROrario">09:45</td>
            <td id="RRitardo" aria-label="30 minuti">30</td><td id="RBinario"><div>7</div></td></tr></table>"""
        val rfi = RfiStationBoardAdapter(fetcher = RfiMonitorFetcher { html }).parseDepartures(html, "Roma Termini", "", route, Direction.OUTBOUND)
        assertEquals(locale.toLanguageTag(), "10:15", rfi.single().expectedDeparture)

        val clock = Clock.fixed(Instant.parse("2026-10-16T08:00:00Z"), ZoneOffset.UTC)
        val json = """{"IsEmpty":false,"LastUpdate":"2026-10-16T07:59:00Z","ListaTreniPartenza":[
            {"Numero":"9911","DescrizioneLocalita":"Napoli Centrale","OraPassaggio":"10:05","Ritardo":"12","Binario":"3"}]}"""
        val italo = parseItaloPublicBoard(json, "Roma Termini", route, Direction.OUTBOUND, clock)
        assertEquals(locale.toLanguageTag(), "10:17", italo.single().expectedDeparture)
    }

    @Test fun `board reconciliation keeps ASCII times and the reliable quality band`() = eachNonAsciiLocale { locale ->
        val now = Instant.parse("2026-09-04T09:00:00Z") // 11:00 in Rome
        val clock = Clock.fixed(now, rome)
        val date = LocalDate.of(2026, 9, 4)
        val ref = SourceRef(SourceId("rfi-station-board"), observedAt = now, fetchedAt = now, expiresAt = now.plusSeconds(45),
            confidence = Confidence.INFRASTRUCTURE_PRIMARY, traceId = "locale")
        val entry = StationBoardEntry(TrainRunId("r"), date.atTime(12, 0).atZone(rome), SourcedValue(date.atTime(12, 7).atZone(rome), ref),
            StationId("published:b"), "B", publicNumber = "9401", operatorId = OperatorId("trenitalia"),
            status = SourcedValue(RunStatus.DELAYED, ref))
        val provider = object : StationBoardProvider {
            override val descriptor = ProviderDescriptor(SourceId("rfi-station-board"), "RFI", setOf(ProviderCapability.STATION_BOARD),
                CoverageDescriptor(description = "test"), AccessMode.PUBLIC_BEST_EFFORT, RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofSeconds(45)), 1)
            override suspend fun getStationBoard(request: StationBoardRequest) = ProviderResult.Success(
                StationBoard(request.stationId, request.kind, request.from, request.to, listOf(entry),
                    SnapshotMeta(1, now, now.plusSeconds(45), it.binario.app.data.core.Freshness.FRESH, Completeness.PARTIAL, setOf(ref))),
                ProviderResponseMeta(SourceId("rfi-station-board"), now))
        }
        val service = TrainService("s", "9401", RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, "A", "B", "12:00", "12:00", null, 0,
            ServiceStatus.ON_TIME, listOf(TrainStop("A", "12:00", "12:00", null, 0, false), TrainStop("B", "13:00", "13:00", null, 0, false)),
            operationalStatusVerified = false, scheduledDepartureDateTime = date.atTime(12, 0))
        val route = SavedRoute("t", "t", Station("a", "A", "A"), Station("b", "B", "B"))
        val snapshot = RouteSnapshot(route, Direction.OUTBOUND, listOf(service), "GTFS", now.toEpochMilli(), now.plusSeconds(3600).toEpochMilli(),
            Freshness.AGING, confidence = DataConfidence.STATIC_ONLY)

        val enriched = runBlocking { StationBoardRealtimeReconciler(listOf(provider), clock, rome).enrich(snapshot, JourneyQuery(date, LocalTime.of(11, 0))) }
        val shown = enriched.services.single()
        val quality = SnapshotQualityAssessor.assess(enriched, now.toEpochMilli())

        assertEquals(locale.toLanguageTag(), "12:00", shown.scheduledDeparture)
        assertEquals(locale.toLanguageTag(), "12:07", shown.expectedDeparture)
        assertTrue(shown.scheduledDeparture.matches(ascii))
        assertEquals(locale.toLanguageTag(), DataQualityBand.RELIABLE, quality.band)
        assertTrue(locale.toLanguageTag(), SnapshotWarning.INCOMPLETE_SERVICE !in quality.warnings)
    }
}
