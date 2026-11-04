package it.binario.app.journeys

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.journey.JourneyFixtures
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.repository.JourneySearchService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** «Più presto» / «Più tardi»: the windows around a search, for the direct trains and the journeys with changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultsPagingTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val day = LocalDate.of(2026, 11, 20)
    private fun at(time: String): LocalDateTime = day.atTime(LocalTime.parse(time))

    /** A source answering from a time with at most [cap] departures of [times], as the route sources do. */
    private class Source(val times: List<LocalDateTime>, val cap: Int) {
        val asked = ArrayList<LocalDateTime>()
        suspend fun load(from: LocalDateTime): List<LocalDateTime>? {
            asked += from
            return times.filter { !it.isBefore(from) }.sorted().take(cap)
        }
    }

    @Test fun `later continues from the last departure, the same minute included`() = runBlocking {
        val source = Source(listOf(at("08:00"), at("09:00"), at("09:00"), at("10:30"), at("23:50")), cap = 3)
        assertEquals(listOf(at("09:00"), at("09:00"), at("10:30")), ResultsPaging.later(at("09:00"), { it }, source::load))
        assertEquals(listOf(at("09:00")), source.asked)
        assertNull(ResultsPaging.later(at("09:00"), { it: LocalDateTime -> it }) { null })
    }

    @Test fun `earlier looks one hour back, then further, and fills a full window up to the first departure`() = runBlocking {
        // Nothing in the hour before 12:00, one train in three hours, two in six: twelve hours back, never before the day.
        val sparse = Source(listOf(day.minusDays(1).atTime(23, 0), at("06:00"), at("09:30"), at("12:00"), at("13:00")), cap = 24)
        assertEquals(listOf(at("06:00"), at("09:30")), ResultsPaging.earlier(at("12:00"), { it }, notBefore = day.atStartOfDay(), load = sparse::load))
        assertEquals(listOf(at("11:00"), at("09:00"), at("06:00"), at("00:00")), sparse.asked)
        // A page is the six closest to the first departure.
        val hourly = Source((0..23).map { at("%02d:15".format(it)) }, cap = 24)
        assertEquals((6..11).map { at("%02d:15".format(it)) }, ResultsPaging.earlier(at("12:15"), { it }, load = hourly::load))
        assertEquals(listOf(at("11:15"), at("09:15"), at("06:15")), hourly.asked)
        // A busy line: the window from 11:00 fills up three times before 12:00, so the next one starts at 11:30 and,
        // continued from its last train, reaches 12:00 with no gap.
        val busy = Source((0 until 12).map { at("11:00").plusMinutes(it * 5L) } + at("12:00"), cap = 3)
        val earlier = ResultsPaging.earlier(at("12:00"), { it }, load = busy::load)!!
        assertEquals((6 until 12).map { at("11:00").plusMinutes(it * 5L) }, earlier)
        assertEquals(listOf("11:00", "11:11", "11:26", "11:30", "11:41", "11:56").map(::at), busy.asked)
        // Nothing at all in the twelve hours before: empty; no answer: null.
        assertEquals(emptyList<LocalDateTime>(), ResultsPaging.earlier(at("06:00"), { it }, load = Source(listOf(at("06:00")), 6)::load))
        assertEquals(emptyList<LocalDateTime>(), ResultsPaging.earlier(at("00:10"), { it }, notBefore = at("00:10"), load = sparse::load))
        assertNull(ResultsPaging.earlier(at("12:00"), { it: LocalDateTime -> it }) { null })
    }

    private fun service(id: String, number: String, time: String, date: LocalDate? = day) = TrainService(
        id, number, RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Alfa", "Delta", time, time, null, 0, ServiceStatus.ON_TIME,
        listOf(TrainStop("Alfa", time, time, null, 0, false)), operationalStatusVerified = false,
        scheduledDepartureDateTime = date?.atTime(LocalTime.parse(time))
    )

    @Test fun `a page drops the trains shown, by id or by number and minute`() {
        val shown = listOf(service("gtfs:1", "2153", "08:00"), service("rfi:2155", "2155", "08:30"))
        val page = listOf(
            service("gtfs:1", "2153", "08:00"),
            // The same run from another source.
            service("gtfs:2", "R 2155", "08:30"),
            service("gtfs:3", "2157", "09:00"),
            service("gtfs:3", "2157", "09:00"),
            // A live row without a date takes the day of the search.
            service("vt:2159", "2159", "09:30", date = null)
        )
        assertEquals(listOf("gtfs:3", "vt:2159"), ResultsPaging.newServices(shown, page, day).map { it.id })
        assertEquals(at("09:30"), ResultsPaging.departureOf(service("vt:2159", "2159", "09:30", date = null), day))
        assertNull(ResultsPaging.departureOf(service("x", "1", "—", date = null), day))
    }

    // --- The journeys with changes on the offline planner ---

    private val hourly = JourneyFixtures.provider(mapOf(JourneyFixtures.pack() to JourneyFixtures.feed(
        (6..20).map { hour -> Trip("T$hour", "${2000 + hour}", listOf("A" to "%02d:10".format(hour), "D" to "%02d:50".format(hour))) }
    )))

    private fun model() = JourneySearchViewModel(JourneySearchService(listOf(hourly)))

    private fun JourneySearchViewModel.await(condition: (JourneySearchUiState) -> Boolean) = runBlocking {
        withTimeout(10_000) { state.first(condition) }
    }

    @Test fun `later and earlier merge their windows into the same search`() {
        val model = model()
        model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(10, 0))
        val first = model.await { !it.loading && it.result != null }
        val query = first.query
        assertEquals((10..15).map { "%02d:10".format(it) }, first.presentation!!.rows.map { it.departureTime })
        model.loadLater()
        val later = model.await { !it.later.loading && it.result!!.options.size > 6 }
        assertEquals((10..20).map { "%02d:10".format(it) }, later.presentation!!.rows.map { it.departureTime })
        assertFalse(later.later.nothingNew)
        // The search answered is unchanged, so the results screen keeps showing it.
        assertEquals(query, later.query)
        model.loadLater()
        val end = model.await { !it.later.loading && it.later.nothingNew }
        assertEquals(11, end.result!!.options.size)
        model.loadEarlier()
        // Every train of the morning before 10:10 (fewer than a page), never the evening before.
        val earlier = model.await { !it.earlier.loading && it.result!!.options.size > 11 }
        assertEquals((6..20).map { "%02d:10".format(it) }, earlier.presentation!!.rows.map { it.departureTime })
        assertFalse(earlier.earlier.nothingNew)
        model.loadEarlier()
        assertTrue(model.await { !it.earlier.loading && it.earlier.nothingNew }.earlier.nothingNew)
        assertEquals(15, model.state.value.result!!.options.size)
        // A new search starts without the pages.
        model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(18, 0))
        val fresh = model.await { !it.loading && it.result != null }
        assertEquals(listOf("18:10", "19:10", "20:10"), fresh.presentation!!.rows.map { it.departureTime })
        assertEquals(ResultsPageState(), fresh.later)
        assertEquals(ResultsPageState(), fresh.earlier)
    }

    @Test fun `the first option after an empty window is marked, never one of a page`() {
        val rare = JourneyFixtures.provider(mapOf(JourneyFixtures.pack() to JourneyFixtures.feed(listOf(
            Trip("M1", "1", listOf("A" to "06:00", "D" to "07:00")),
            Trip("M2", "2", listOf("A" to "19:00", "D" to "20:00")),
            Trip("M3", "3", listOf("A" to "21:00", "D" to "22:00"))
        ))))
        val model = JourneySearchViewModel(JourneySearchService(listOf(rare)))
        // The window of 07:30 ends at 15:30 with nothing: 19:00 follows, the first after it.
        model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(7, 30))
        val state = model.await { !it.loading && it.result != null }
        val row = state.presentation!!.rows.single()
        assertEquals("19:00", row.departureTime)
        assertTrue(row.firstAfterEmptyWindow)
        model.loadLater()
        val later = model.await { !it.later.loading && it.result!!.options.size > 1 }
        assertEquals(listOf(true, false), later.presentation!!.rows.map { it.firstAfterEmptyWindow })
        // A window with options marks none.
        model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(5, 30))
        assertFalse(model.await { !it.loading && it.result != null }.presentation!!.rows.any { it.firstAfterEmptyWindow })
    }
}
