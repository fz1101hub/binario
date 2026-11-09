package it.binario.app.notifications

import it.binario.app.TestStringCatalogue
import it.binario.app.UNCONFIGURED_ROUTE_ID
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.live.MutablePublicSourceClock
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.sourceFixture
import it.binario.app.data.notices.NoticeDates
import it.binario.app.data.notices.NoticePublisher
import it.binario.app.data.notices.RailNotice
import it.binario.app.data.notices.RailNoticesService
import it.binario.app.data.notices.RouteWorksMatcher
import it.binario.app.data.notices.RouteWorksNotice
import it.binario.app.data.notices.RouteWorksReason
import it.binario.app.data.notices.WorksRoute
import it.binario.app.ui.common.LocalizedDates
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteWorksAlertTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val today = LocalDate.of(2026, 11, 9)
    private val tomorrow = today.plusDays(1)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = ZonedDateTime.of(date.atTime(hour, minute), rome)

    private val routeNames = mapOf("casa" to "Casa → Lavoro", "mare" to "Milano → Genova", "lago" to "Milano → Como")
    private val routeIds = listOf("casa", "mare", "lago")
    private fun reason(routeId: String, vararg stations: String, line: String? = null) =
        RouteWorksReason(routeId, routeNames.getValue(routeId), stations.toList(), line)
    private fun works(id: String, dates: NoticeDates?, vararg reasons: RouteWorksReason, title: String = "Lavori $id") =
        RouteWorksNotice(RailNotice(id, NoticePublisher.RFI, title, null, null, null), reasons.toList(), dates)
    private fun november(first: Int, last: Int = first) = NoticeDates(LocalDate.of(2026, 11, first), LocalDate.of(2026, 11, last))
    private fun ids(alert: RouteWorksAlert?) = alert?.items?.map { it.works.notice.id }

    @Test fun `the alert runs every evening at 19, at once when turned on during the evening`() {
        assertEquals(at(today, 19), RouteWorksAlertPlanner.nextRunAt(at(today, 9, 30), immediate = false))
        assertEquals(at(today, 19), RouteWorksAlertPlanner.nextRunAt(at(today, 3), immediate = true))
        assertEquals(at(tomorrow, 19), RouteWorksAlertPlanner.nextRunAt(at(today, 19), immediate = false))
        assertEquals(at(tomorrow, 19), RouteWorksAlertPlanner.nextRunAt(at(today, 20, 15), immediate = false))
        // Turned on in the evening window: tonight's alert is not lost; after 22 it waits for tomorrow evening.
        assertEquals(at(today, 20, 15), RouteWorksAlertPlanner.nextRunAt(at(today, 20, 15), immediate = true))
        assertEquals(at(tomorrow, 19), RouteWorksAlertPlanner.nextRunAt(at(today, 22, 30), immediate = true))
        // Across the changes of summer time the run stays at 19:00 local time.
        assertEquals(ZonedDateTime.of(2027, 3, 28, 19, 0, 0, 0, rome), RouteWorksAlertPlanner.nextRunAt(ZonedDateTime.of(2027, 3, 27, 20, 0, 0, 0, rome), false))
        assertEquals(ZonedDateTime.of(2026, 10, 25, 19, 0, 0, 0, rome), RouteWorksAlertPlanner.nextRunAt(ZonedDateTime.of(2026, 10, 25, 1, 30, 0, 0, rome), false))
    }

    @Test fun `it notifies only in the evening, never between 22 and 7`() {
        assertFalse(RouteWorksAlertPlanner.mayNotify(at(today, 18, 59)))
        assertTrue(RouteWorksAlertPlanner.mayNotify(at(today, 19)))
        assertTrue(RouteWorksAlertPlanner.mayNotify(at(today, 21, 59)))
        assertFalse(RouteWorksAlertPlanner.mayNotify(at(today, 22)))
        assertFalse(RouteWorksAlertPlanner.mayNotify(at(today, 23, 30)))
        assertFalse(RouteWorksAlertPlanner.mayNotify(at(tomorrow, 2)))
        assertFalse(RouteWorksAlertPlanner.mayNotify(at(tomorrow, 7)))
        assertTrue(RouteWorksAlertPlanner.quiet(at(today, 22)))
        assertTrue(RouteWorksAlertPlanner.quiet(at(tomorrow, 6, 59)))
        assertFalse(RouteWorksAlertPlanner.quiet(at(tomorrow, 7)))
        assertFalse(RouteWorksAlertPlanner.quiet(at(today, 21, 59)))
    }

    @Test fun `only works whose dates include tomorrow, never an undated notice nor a strike`() {
        val matched = listOf(
            works("tomorrow", november(10), reason("casa", "Pavia")),
            works("spanning", november(5, 12), reason("casa", "Voghera")),
            works("ending-tomorrow", november(3, 10), reason("casa", "Pavia")),
            works("today", november(9), reason("casa", "Pavia")),
            works("after", november(11, 13), reason("casa", "Pavia")),
            works("undated", null, reason("casa", "Pavia")),
            works("strike", november(10), reason("casa", "Pavia"), title = "Sciopero: circolazione sospesa a Pavia")
        )
        val alert = RouteWorksAlertPlanner.select(matched, routeIds, today, emptyMap())
        assertEquals(listOf("tomorrow", "spanning", "ending-tomorrow"), ids(alert))
        assertEquals(tomorrow, alert?.day)
        assertNull(RouteWorksAlertPlanner.select(matched.drop(3), routeIds, today, emptyMap()))
        assertNull(RouteWorksAlertPlanner.select(emptyList(), routeIds, today, emptyMap()))
        assertTrue(RouteWorksAlertPlanner.validOn(november(10), tomorrow))
        assertFalse(RouteWorksAlertPlanner.validOn(null, tomorrow))
    }

    @Test fun `a notice is never notified twice for the same day, and one alert an evening`() {
        val matched = listOf(works("a", november(9, 12), reason("casa", "Pavia")), works("b", november(10), reason("mare", "Genova Brignole")))
        val first = requireNotNull(RouteWorksAlertPlanner.select(matched, routeIds, today, emptyMap()))
        val notified = RouteWorksAlertPlanner.remember(emptyMap(), first, today)
        assertEquals(setOf("2026-11-10|a", "2026-11-10|b"), notified.keys)
        assertTrue(RouteWorksAlertPlanner.notifiedOn(notified, today))
        assertFalse(RouteWorksAlertPlanner.notifiedOn(notified, tomorrow))
        // Already notified for tomorrow: nothing again, also from another run the same evening.
        assertNull(RouteWorksAlertPlanner.select(matched, routeIds, today, notified))
        assertEquals(listOf("b"), ids(RouteWorksAlertPlanner.select(matched, routeIds, today, mapOf(RouteWorksAlertPlanner.notifiedKey("a", tomorrow) to today.toEpochDay()))))
        // The next evening the works still running are due for the day after: another day, another alert.
        assertEquals(listOf("a"), ids(RouteWorksAlertPlanner.select(matched, routeIds, tomorrow, notified)))
        // Entries are forgotten after three days.
        val later = requireNotNull(RouteWorksAlertPlanner.select(matched, routeIds, tomorrow, notified))
        assertEquals(setOf("2026-11-11|a"), RouteWorksAlertPlanner.remember(notified, later, today.plusDays(3)).keys)
    }

    @Test fun `at most three notices, in Per te's order of the routes, each under the first route it names`() {
        val matched = listOf(
            works("lago-1", november(10), reason("lago", "Como San Giovanni")),
            works("mare-1", november(10), reason("mare", "Genova Brignole"), reason("casa", "Pavia")),
            works("mare-2", november(8, 11), reason("mare", "Voghera", line = "Milano – Genova")),
            works("lago-2", november(10), reason("lago", "Monza")),
            works("unknown", november(10), RouteWorksReason("deleted", "Vecchia tratta", listOf("Lodi"), null))
        )
        val alert = requireNotNull(RouteWorksAlertPlanner.select(matched, routeIds, today, emptyMap()))
        assertEquals(listOf("mare-1", "mare-2", "lago-1"), ids(alert))
        assertEquals("casa", alert.items.first().reason.routeId)
        assertEquals(listOf("Casa → Lavoro", "Milano → Genova", "Milano → Como"), alert.routeNames)
        assertEquals(1, alert.hidden)
        // The notices left out of the notification are remembered too: the one alert of the evening counted them.
        assertEquals(4, RouteWorksAlertPlanner.remember(emptyMap(), alert, today).size)
    }

    private fun station(id: String) = Station(id, id.replaceFirstChar(Char::uppercase), id)

    @Test fun `the routes are those of Per te, pinned first, at most eight`() {
        val saved = listOf(SavedRoute(UNCONFIGURED_ROUTE_ID, "", station("a"), station("b"))) +
            (1..9).map { SavedRoute("r$it", "Tratta $it", station("a$it"), station("b$it"), isPinned = it == 5) } +
            SavedRoute("search-a-b", "Ricerca", station("a"), station("b"))
        assertEquals(listOf("r5", "r1", "r2", "r3", "r4", "r6", "r7", "r8"), RouteWorksAlertPlanner.routes(saved).map { it.id })
        assertEquals(8, RouteWorksAlertPlanner.MAX_ROUTES)
    }

    private fun italian(): RouteWorksAlertStrings {
        val catalogue = TestStringCatalogue.of(null)
        return RouteWorksAlertStrings(
            locale = Locale.ITALIAN,
            title = catalogue.getValue("route_works_alert_title"),
            titleMore = { route, others -> if (others == 1) "Domani lavori su $route e su $others altra tratta" else "Domani lavori su $route e su altre $others tratte" },
            names = catalogue.getValue("route_works_names"),
            routeNames = catalogue.getValue("route_works_route_names"),
            line = catalogue.getValue("route_works_line"),
            date = catalogue.getValue("route_works_date"),
            dates = catalogue.getValue("route_works_dates"),
            more = { count -> if (count == 1) "E $count altro avviso in Per te" else "E altri $count avvisi in Per te" },
            dateFormatter = LocalizedDates.dayMonth(Locale.ITALIAN)
        )
    }

    @Test fun `one route names the route in the title and what the notice names in the text`() {
        val strings = italian()
        val alert = requireNotNull(
            RouteWorksAlertPlanner.select(
                listOf(works("pavia", november(10, 13), reason("casa", "Pavia", "Voghera"), title = "Lavori notturni tra Pavia e Voghera")),
                routeIds, today, emptyMap()
            )
        )
        assertEquals("Domani lavori su Casa → Lavoro", RouteWorksAlertText.title(alert, strings))
        assertEquals("L’avviso cita: Pavia, Voghera · Date citate: dal 10 nov al 13 nov", RouteWorksAlertText.summary(alert.items.single(), alert, strings))
        assertEquals(
            listOf("L’avviso cita: Pavia, Voghera · Date citate: dal 10 nov al 13 nov\nLavori notturni tra Pavia e Voghera"),
            RouteWorksAlertText.lines(alert, strings)
        )
    }

    @Test fun `several routes put the route before each notice and count the notices left to Per te`() {
        val strings = italian()
        val matched = listOf(
            works("a", november(10), reason("casa", "Pavia"), title = "Bus sostitutivi a Pavia"),
            works("b", november(10), reason("mare", line = "Milano – Genova"), title = "Linea Milano – Genova: circolazione interrotta"),
            works("c", november(10), reason("mare", "Voghera")),
            works("d", november(10), reason("lago", "Monza")),
            works("e", november(10), reason("lago", "Lecco"))
        )
        val alert = requireNotNull(RouteWorksAlertPlanner.select(matched, routeIds, today, emptyMap()))
        assertEquals("Domani lavori su Casa → Lavoro e su altre 2 tratte", RouteWorksAlertText.title(alert, strings))
        assertEquals(
            listOf(
                "Casa → Lavoro — l’avviso cita: Pavia · Data citata: 10 nov\nBus sostitutivi a Pavia",
                "Milano → Genova — l’avviso cita: la linea Milano – Genova · Data citata: 10 nov\nLinea Milano – Genova: circolazione interrotta",
                "Milano → Genova — l’avviso cita: Voghera · Data citata: 10 nov\nLavori c",
                "E altri 2 avvisi in Per te"
            ),
            RouteWorksAlertText.lines(alert, strings)
        )
        val two = requireNotNull(RouteWorksAlertPlanner.select(matched.take(2), routeIds, today, emptyMap()))
        assertEquals("Domani lavori su Casa → Lavoro e su 1 altra tratta", RouteWorksAlertText.title(two, strings))
    }

    @Test fun `on RFI-shaped notices the night works between Pavia and Voghera are due the evening before`() {
        val catalogue: List<Station> = EmbeddedCuratedRailStationCatalog({ File("src/main/assets", it).inputStream() }).load()
        fun station(name: String): Station = catalogue.single { it.name == name }
        val notices = runBlocking {
            val rfi = RfiInfomobilityRssAdapter(fetcher = { sourceFixture("rfi-infomobilita-works.xml") }, clock = MutablePublicSourceClock(Instant.parse("2026-11-09T18:00:00Z")))
            RailNoticesService(emptyList(), listOf(rfi), null).general(includeTrenord = false).network
        }
        val routes = listOf(
            WorksRoute("pavia-milano", "Pavia → Milano", listOf("Pavia", "Milano Rogoredo", "Milano Centrale").map(::station)),
            WorksRoute("milano-venezia", "Milano → Venezia", listOf("Milano Centrale", "Brescia", "Verona Porta Nuova", "Venezia Santa Lucia").map(::station))
        )
        val works = RouteWorksMatcher(catalogue).match(notices, routes, today)
        val alert = requireNotNull(RouteWorksAlertPlanner.select(works, routes.map { it.id }, today, emptyMap()))
        // The strike that names Pavia and the Brescia – Verona works of the weekend are not for tomorrow.
        assertEquals(listOf("Lavori notturni tra Pavia e Voghera"), alert.items.map { it.works.notice.title })
        assertEquals(listOf("Pavia → Milano"), alert.routeNames)
        // On Friday evening the weekend works are due for Saturday.
        val friday = requireNotNull(RouteWorksAlertPlanner.select(works, routes.map { it.id }, LocalDate.of(2026, 11, 13), emptyMap()))
        assertTrue("Linea Milano – Venezia: lavori tra Brescia e Verona Porta Nuova" in friday.items.map { it.works.notice.title })
    }

    @Test fun `the alert has its own work, after the strike reminder in the background runs`() {
        assertEquals("binario-route-works-alert", RouteWorksAlertScheduler.WORK)
        assertEquals(BackgroundCheck.STRIKES.ordinal + 1, BackgroundCheck.ROUTE_WORKS.ordinal)
    }
}
