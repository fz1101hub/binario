package it.binario.app.journeys

import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import it.binario.app.data.DataConfidence
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.SourceId
import it.binario.app.data.journey.JourneyFixtures
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.JourneyProvider
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.repository.JourneySearchService
import it.binario.app.data.repository.JourneySearchStatus
import it.binario.app.data.repository.TrainNumberMatch
import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.data.repository.TrainNumberSearchProvider
import it.binario.app.data.repository.TrainNumberSearchService
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JourneySearchViewModelTest {
    @get:Rule val main = ViewModelMainDispatcherRule()

    private val offline = JourneyFixtures.provider(mapOf(JourneyFixtures.pack() to JourneyFixtures.feed(listOf(
        Trip("D1", "1", listOf("A" to "08:00", "D" to "10:00")),
        Trip("C1", "2", listOf("A" to "08:15", "B" to "08:45")),
        Trip("C2", "3", listOf("B" to "08:55", "D" to "09:40"))
    ))))

    private fun model(providers: List<JourneyProvider> = listOf(offline)) = main.track(JourneySearchViewModel(JourneySearchService(providers)))

    private fun JourneySearchViewModel.awaitIdle() = runBlocking {
        withTimeout(10_000) { state.first { !it.loading && it.result != null } }
    }

    @Test fun `a search exposes the presented options and the filter re-sorts them`() {
        val model = model()
        assertTrue(model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(7, 55)))
        val state = model.awaitIdle()
        assertEquals(JourneySearchStatus.FOUND, state.result?.status)
        val presentation = state.presentation!!
        assertEquals(listOf("08:00", "08:15"), presentation.rows.map { it.departureTime })
        assertEquals(listOf(JourneyChangesLabel.Direct, JourneyChangesLabel.Changes(1)), presentation.rows.map { it.changesLabel })
        model.selectFilter(JourneyResultsFilter.FASTEST)
        assertEquals(listOf("08:15", "08:00"), model.state.value.presentation!!.rows.map { it.departureTime })
        assertEquals(JourneyResultsFilter.FASTEST, model.state.value.filter)
        model.selectFilter(JourneyResultsFilter.DIRECT)
        assertEquals(listOf("08:00"), model.state.value.presentation!!.rows.map { it.departureTime })
    }

    @Test fun `the same station twice is rejected with the route message`() {
        val model = model()
        assertFalse(model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.alfa, JourneyFixtures.monday, LocalTime.NOON))
        assertEquals(UiMessage(UiMessageKey.ROUTE_STATIONS_MUST_DIFFER), model.state.value.validationMessage)
        assertNull(model.state.value.result)
    }

    @Test fun `a newer search replaces an older one that answers late`() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : JourneyProvider by offline {
            override suspend fun searchJourneys(request: JourneySearchRequest) = if (request.departureAt.hour == 6) {
                gate.await()
                offline.searchJourneys(request)
            } else offline.searchJourneys(request)
        }
        val model = model(listOf(slow))
        model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(6, 0))
        assertTrue(model.state.value.loading)
        model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(8, 10))
        gate.complete(Unit)
        val state = model.awaitIdle()
        assertEquals(LocalTime.of(8, 10), state.query?.departure?.toLocalTime())
        assertEquals(listOf("08:15"), state.presentation!!.rows.map { it.departureTime })
    }

    @Test fun `reset forgets the search`() {
        val model = model()
        model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(7, 55))
        model.awaitIdle()
        model.reset()
        assertEquals(JourneySearchUiState(), model.state.value)
    }

    // --- The live times of the legs follow the trains while the results stay open (audit2 android-state-1) ---

    private val zone = JourneyFixtures.zone
    @Volatile private var clock: Instant = JourneyFixtures.monday.atTime(8, 0).atZone(zone).toInstant()
    /** Minutes of delay of train 2 (Alfa 08:15 → Beta 08:45); train 3 leaves Beta at 08:55. Null: the source fails. */
    @Volatile private var firstTrainDelay: Int? = 0
    private val lookups = CopyOnWriteArrayList<String>()

    private fun liveRun(number: String, delay: Int, vararg stops: Pair<String, String>): TrainService {
        fun expected(scheduled: String) = LocalTime.parse(scheduled).plusMinutes(delay.toLong()).toString()
        return TrainService(
            id = "live:$number", number = number, operator = RailOperator.OTHER, trainCategory = TrainCategory.REGIONALE,
            origin = stops.first().first, destination = stops.last().first, scheduledDeparture = stops.first().second,
            expectedDeparture = expected(stops.first().second), platform = null, delayMinutes = delay,
            status = if (delay > 0) ServiceStatus.DELAYED else ServiceStatus.ON_TIME, operationalStatusVerified = true,
            scheduledDepartureDateTime = JourneyFixtures.monday.atTime(LocalTime.parse(stops.first().second)),
            stops = stops.map { (station, scheduled) -> TrainStop(station, scheduled, expected(scheduled), null, delay, completed = false, operationalStatusVerified = true) }
        )
    }

    private val liveSource = object : TrainNumberSearchProvider {
        override val descriptor = ProviderDescriptor(
            SourceId("live-test"), "live-test", setOf(ProviderCapability.TRAIN_RUN_REALTIME), CoverageDescriptor(description = "test"),
            AccessMode.OPEN_DIRECT, RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofSeconds(30)), 1
        )
        override suspend fun searchByNumber(query: TrainNumberQuery): ProviderResult<List<TrainNumberMatch>> {
            lookups += query.number
            val delay = firstTrainDelay
            val run = when {
                delay == null -> null
                query.number == "2" -> liveRun("2", delay, "Alfa" to "08:15", "Beta" to "08:45")
                query.number == "3" -> liveRun("3", 0, "Beta" to "08:55", "Delta" to "09:40")
                else -> null
            }
            val matches = listOfNotNull(run).map { TrainNumberMatch.create(it, "Test", DataConfidence.STATIC_ONLY, clock.toEpochMilli(), clock.plusSeconds(60).toEpochMilli()) }
            return ProviderResult.Success(matches, ProviderResponseMeta(SourceId("live-test"), clock))
        }
    }

    private fun liveModel() = main.track(JourneySearchViewModel(
        JourneySearchService(listOf(offline)),
        JourneyLiveLookup(TrainNumberSearchService(listOf(liveSource)), now = { clock }, zone = zone),
        now = { clock }
    ))

    private fun JourneySearchViewModel.awaitChange(expected: TransferRiskLevel?) = runBlocking {
        withTimeout(10_000) {
            state.first { state -> state.presentation?.rows?.firstOrNull { it.changes == 1 }?.transfers?.singleOrNull()?.risk == expected }
        }
    }

    /** The search or lookup has ended (its coroutine finishes on a background thread in these tests). */
    private fun JourneySearchViewModel.awaitNotBusy() = runBlocking { withTimeout(10_000) { while (isBusy) kotlinx.coroutines.delay(5) } }

    private fun JourneySearchViewModel.searchWithChange(): JourneySearchUiState {
        assertTrue(searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(7, 55)))
        return awaitChange(TransferRiskLevel.OK).also { awaitNotBusy() }
    }

    @Test fun `a refresh repeats the live lookup and the change follows the delay`() {
        val model = liveModel()
        assertEquals(2, model.searchWithChange().liveLegs.size)
        // Forty seconds later the first train is twenty minutes late: it reaches Beta after the second one has left.
        firstTrainDelay = 20
        clock = clock.plusSeconds(40)
        lookups.clear()
        model.refreshLive(force = true)
        val state = model.awaitChange(TransferRiskLevel.MISSED)
        assertEquals(setOf("2", "3"), lookups.toSet())
        assertTrue(state.liveLegs.values.any { it.expectedArrival == JourneyFixtures.monday.atTime(9, 5).atZone(zone).toInstant() })
        model.awaitNotBusy()
    }

    @Test fun `the periodic refresh looks the trains up once a minute at most`() {
        val model = liveModel()
        model.searchWithChange()
        firstTrainDelay = 20
        lookups.clear()
        clock = clock.plusSeconds(30)
        model.refreshLive()
        assertTrue(lookups.isEmpty())
        assertEquals(TransferRiskLevel.OK, model.state.value.presentation!!.rows.first { it.changes == 1 }.transfers.single().risk)
        clock = clock.plusSeconds(30)
        model.refreshLive()
        model.awaitChange(TransferRiskLevel.MISSED)
        assertEquals(setOf("2", "3"), lookups.toSet())
        model.awaitNotBusy()
    }

    @Test fun `live times that cannot be confirmed are dropped after a few minutes`() {
        val model = liveModel()
        model.searchWithChange()
        // The source stops answering: the last answer is kept for a while, never shown as live for good.
        firstTrainDelay = null
        clock = clock.plusSeconds(120)
        lookups.clear()
        model.refreshLive(force = true)
        model.awaitNotBusy()
        assertEquals(setOf("2", "3"), lookups.toSet())
        assertEquals(TransferRiskLevel.OK, model.state.value.presentation!!.rows.first { it.changes == 1 }.transfers.single().risk)
        clock = clock.plus(JourneyLiveRefresh.MAX_AGE)
        model.refreshLive()
        // Dropped at once, before the lookup answers.
        assertTrue(model.state.value.liveLegs.isEmpty())
        assertEquals(TransferRiskLevel.UNKNOWN, model.state.value.presentation!!.rows.first { it.changes == 1 }.transfers.single().risk)
        // The lookup that refresh started ends before the test does.
        model.awaitNotBusy()
    }

    @Test fun `the journey on screen is looked up first and a new search forgets the earlier live legs`() {
        assertEquals(listOf("b", "a", "c"), JourneyLiveRefresh.focusedFirst(listOf("a", "b", "c"), "b") { it })
        assertEquals(listOf("a", "b", "c"), JourneyLiveRefresh.focusedFirst(listOf("a", "b", "c"), null) { it })
        assertEquals(listOf("a", "b", "c"), JourneyLiveRefresh.focusedFirst(listOf("a", "b", "c"), "z") { it })
        val start = Instant.parse("2026-09-07T06:00:00Z")
        assertTrue(JourneyLiveRefresh.isDue(null, start))
        assertFalse(JourneyLiveRefresh.isDue(start, start.plusSeconds(59)))
        assertTrue(JourneyLiveRefresh.isDue(start, start.plusSeconds(60)))
        val legs = mapOf("old" to JourneyLegLive(expectedArrival = start), "new" to JourneyLegLive(expectedArrival = start), "unknown" to JourneyLegLive(cancelled = true))
        val fetched = mapOf("old" to start, "new" to start.plusSeconds(200))
        assertEquals(setOf("old", "new"), JourneyLiveRefresh.current(legs, fetched, start.plus(JourneyLiveRefresh.MAX_AGE)).keys)
        assertEquals(setOf("new"), JourneyLiveRefresh.current(legs, fetched, start.plus(JourneyLiveRefresh.MAX_AGE).plusSeconds(1)).keys)
        val model = liveModel()
        model.searchWithChange()
        model.reset()
        model.refreshLive(force = true)
        assertEquals(JourneySearchUiState(), model.state.value)
    }
}
