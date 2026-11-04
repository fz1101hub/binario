package it.binario.app.data.notices

import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.live.MutablePublicSourceClock
import it.binario.app.data.live.RecordingPublicSourceFetcher
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.TrenordLineNoticesAdapter
import it.binario.app.data.live.sourceFixture
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Lavori e interruzioni sulle tue tratte» on invented notices in the shape of RFI's infomobility RSS
 * (`sources/rfi-infomobilita-works.xml`), read through the app's own RSS adapter and notices service, with the real
 * station catalogue for the names.
 */
class RouteWorksTest {
    private val clock = MutablePublicSourceClock(Instant.parse("2026-11-04T08:00:00Z"))
    private val today = LocalDate.of(2026, 11, 4)
    private val catalogue: List<Station> by lazy {
        EmbeddedCuratedRailStationCatalog({ File("src/main/assets", it).inputStream() }).load()
    }
    private val matcher by lazy { RouteWorksMatcher(catalogue) }

    private fun station(name: String): Station = catalogue.single { it.name == name }
    private fun route(id: String, vararg names: String) = WorksRoute(id, id, names.map(::station))

    private val notices: List<RailNotice> by lazy {
        runBlocking {
            val rfi = RfiInfomobilityRssAdapter(fetcher = { sourceFixture("rfi-infomobilita-works.xml") }, clock = clock)
            // The works sections never ask Trenord's line list.
            val trenord = TrenordLineNoticesAdapter(RecordingPublicSourceFetcher { throw AssertionError("Trenord asked: $it") }, clock, 0)
            RailNoticesService(emptyList(), listOf(rfi), trenord).general(includeTrenord = false).network
        }
    }

    private val routes by lazy {
        listOf(
            route("milano-venezia", "Milano Centrale", "Milano Lambrate", "Brescia", "Desenzano Del Garda-Sirmione", "Verona Porta Nuova", "Vicenza", "Venezia Santa Lucia"),
            route("roma-napoli", "Roma Termini", "Napoli Centrale"),
            route("monza-milano", "Monza", "Milano Centrale"),
            route("pavia-milano", "Pavia", "Milano Rogoredo", "Milano Centrale")
        )
    }

    @Test fun `works notices are tied to the routes whose stations or line they name`() {
        assertEquals(8, notices.size)
        val works = matcher.match(notices, routes, today)
        assertEquals(
            listOf("Linea Milano – Venezia: lavori tra Brescia e Verona Porta Nuova", "Linea Roma - Napoli via Cassino: circolazione interrotta tra Cassino e Caianello", "Lavori notturni tra Pavia e Voghera"),
            works.map { it.notice.title }
        )
        with(works[0]) {
            val reason = reasons.single()
            assertEquals("milano-venezia", reason.routeId)
            assertEquals("Milano – Venezia", reason.line)
            assertEquals(listOf("Brescia", "Desenzano Del Garda-Sirmione", "Verona Porta Nuova"), reason.stations)
            assertEquals(NoticeDates(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16)), dates)
        }
        with(works[1]) {
            // Neither Cassino nor Caianello is on the route: only the line, whose two ends it serves.
            assertEquals(listOf(RouteWorksReason("roma-napoli", "roma-napoli", emptyList(), "Roma – Napoli")), reasons)
            assertEquals(NoticeDates(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21)), dates)
        }
        with(works[2]) {
            assertEquals(listOf(RouteWorksReason("pavia-milano", "pavia-milano", listOf("Pavia"), null)), reasons)
            assertEquals(NoticeDates(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 19)), dates)
        }
    }

    @Test fun `never a match the text does not support`() {
        val works = matcher.match(notices, routes, today).map { it.notice.title }
        // A slowdown for a fault is no work nor interruption; a strike has its own section.
        assertTrue(works.none { it.startsWith("Lombardia: circolazione rallentata") || it.startsWith("Sciopero") })
        // "Monza Sobborghi" is another station than Monza; "Milano" alone is a city, not Milano Centrale.
        assertTrue(works.none { it.startsWith("Lavori nella stazione di Monza") })
        assertTrue(matcher.match(notices, listOf(route("monza-milano", "Monza", "Milano Centrale")), today).isEmpty())
        // The Bologna – Firenze line and the "piano" of works name no station of these routes.
        assertTrue(works.none { it.startsWith("Linea Bologna") || it.startsWith("Piano") })
        assertTrue(matcher.match(notices, emptyList(), today).isEmpty())
    }

    @Test fun `a station name counts whole, with a capital, and not inside a longer station`() {
        fun notice(title: String, body: String? = null) = RailNotice(title, NoticePublisher.RFI, title, body, null, null)
        val cancello = listOf(route("c", "Cancello", "Caserta"))
        assertEquals(1, matcher.match(listOf(notice("Circolazione sospesa tra Maddaloni e Cancello per lavori")), cancello, today).size)
        assertTrue(matcher.match(listOf(notice("Lavori nella stazione di Cancello ed Arnone")), cancello, today).isEmpty())
        assertTrue(matcher.match(listOf(notice("Lavori di potenziamento: il cancello della stazione resta chiuso")), cancello, today).isEmpty())
        // An all-capitals title still names the station.
        assertEquals(1, matcher.match(listOf(notice("CIRCOLAZIONE INTERROTTA TRA CANCELLO E NAPOLI")), cancello, today).size)
        // A catalogue alias written as the Italian name ("BOLZANO" for "Bolzano Bozen"), never a translation.
        val bolzano = listOf(route("b", "Bolzano Bozen", "Merano Meran"))
        assertEquals(listOf("Bolzano Bozen"), matcher.match(listOf(notice("Lavori tra Bolzano e Ponte Gardena")), bolzano, today).single().reasons.single().stations)
        assertTrue(matcher.match(listOf(notice("Lavori a Mailand e dintorni")), listOf(route("m", "Milano Centrale", "Monza")), today).isEmpty())
    }

    @Test fun `the dates of the text, the year nearest to the publication`() {
        fun dates(text: String, reference: LocalDate = today) = NoticeDateParser.dates(text, reference)
        assertEquals(NoticeDates(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 12, 6)), dates("Dal 2 novembre al 6 dicembre bus sostitutivi"))
        assertEquals(NoticeDates(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)), dates("Lavori dal 28 al 3 gennaio", LocalDate.of(2026, 12, 20)))
        assertEquals(NoticeDates(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 14)), dates("Sospesa il 14/11/26 dalle 9.00"))
        assertEquals(NoticeDates(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1)), dates("Dal 1° dicembre nuovo orario"))
        assertEquals(LocalDate.of(2026, 1, 10), dates("Lavori il 10 gennaio", LocalDate.of(2026, 1, 5))?.first)
        assertNull(dates("Lavori sui binari 4 e 5 dalle ore 23.30"))
        assertNull(dates("Nessuna data"))
    }

    @Test fun `works words decide, strikes stay out`() {
        assertTrue(RouteWorksMatcher.isWorks("Interruzione della circolazione tra Alfa e Beta"))
        assertTrue(RouteWorksMatcher.isWorks("Attivi bus SOSTITUTIVI"))
        assertFalse(RouteWorksMatcher.isWorks("Circolazione rallentata per un guasto"))
        assertFalse(RouteWorksMatcher.isWorks("Sciopero: circolazione sospesa"))
        assertEquals("milano centrale sant ilario d enza", RouteWorksMatcher.fold("Milano  Centrale — Sant’Ilario d'Enza"))
    }
}
