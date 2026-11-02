package it.binario.app.journeys

import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import it.binario.app.data.journey.JourneyFixtures
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.provider.JourneyProvider
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.repository.JourneySearchService
import it.binario.app.data.repository.JourneySearchStatus
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
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

@OptIn(ExperimentalCoroutinesApi::class)
class JourneySearchViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val offline = JourneyFixtures.provider(mapOf(JourneyFixtures.pack() to JourneyFixtures.feed(listOf(
        Trip("D1", "1", listOf("A" to "08:00", "D" to "10:00")),
        Trip("C1", "2", listOf("A" to "08:15", "B" to "08:45")),
        Trip("C2", "3", listOf("B" to "08:55", "D" to "09:40"))
    ))))

    private fun model(providers: List<JourneyProvider> = listOf(offline)) = JourneySearchViewModel(JourneySearchService(providers))

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
}
