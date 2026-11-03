package it.binario.app.journeys

import it.binario.app.data.journey.JourneyFixtures
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.provider.TravelMode
import it.binario.app.data.repository.JourneySearchQuery
import it.binario.app.data.repository.JourneySearchService
import it.binario.app.data.repository.JourneySearchStatus
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The travel preferences on their way from the settings to the offline planner and back to the results. */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneySearchPreferencesTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val routes = "route_id,agency_id,route_short_name,route_long_name,route_type\nFR,T,FR,Frecciarossa,2\nR,T,REG,Regionale,2\n"
    private val offline = JourneyFixtures.provider(mapOf(JourneyFixtures.pack() to JourneyFixtures.feed(listOf(
        Trip("F1", "9501", listOf("A" to "08:00", "D" to "08:40"), route = "FR"),
        Trip("R1", "2001", listOf("A" to "08:05", "D" to "09:30"), route = "R")
    ), routes = routes)))

    @Test fun `a change of the travel preferences searches the shown journeys again`() {
        val preferences = MutableStateFlow(JourneyPreferences())
        val model = JourneySearchViewModel(JourneySearchService(listOf(offline)), preferences = preferences)
        model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(7, 55))
        val first = runBlocking { withTimeout(10_000) { model.state.first { !it.loading && it.result != null } } }
        assertEquals(listOf("08:00", "08:05"), first.presentation!!.rows.map { it.departureTime })
        assertEquals(JourneyPreferences(), first.query!!.preferences)
        preferences.value = JourneyPreferences(avoidedModes = setOf(TravelMode.HIGH_SPEED))
        val second = runBlocking {
            withTimeout(10_000) { model.state.first { !it.loading && it.query?.preferences?.avoidedModes?.isNotEmpty() == true && it.result != null } }
        }
        assertEquals(listOf("08:05"), second.presentation!!.rows.map { it.departureTime })
    }

    @Test fun `an accessible only search without wheelchair data says so`() = runBlocking {
        val service = JourneySearchService(listOf(offline))
        val query = JourneySearchQuery(
            JourneyFixtures.alfa, JourneyFixtures.delta, LocalDateTime.of(JourneyFixtures.monday, LocalTime.of(7, 30)),
            preferences = JourneyPreferences(wheelchairOnly = true)
        )
        val result = service.search(query)
        assertEquals(JourneySearchStatus.NO_SOLUTION, result.status)
        assertTrue(result.accessibilityDataUnavailable)
        assertTrue(JourneyResultsPresenter.present(result).accessibilityDataUnavailable)
        // Without the filter the same search finds its journeys and says nothing about accessibility.
        val plain = service.search(query.copy(preferences = null))
        assertEquals(JourneySearchStatus.FOUND, plain.status)
        assertFalse(plain.accessibilityDataUnavailable)
    }
}
