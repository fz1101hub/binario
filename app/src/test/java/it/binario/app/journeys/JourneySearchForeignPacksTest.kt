package it.binario.app.journeys

import it.binario.app.data.Station
import it.binario.app.data.gtfs.ForeignPackDownloadResult
import it.binario.app.data.gtfs.ForeignPackFixtures
import it.binario.app.data.gtfs.ForeignPackOffer
import it.binario.app.data.gtfs.ForeignPackOffers
import it.binario.app.data.gtfs.ForeignPackStatus
import it.binario.app.data.journey.JourneyFixtures
import it.binario.app.data.journey.JourneyFixtures.Trip
import it.binario.app.data.provider.JourneyProvider
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.repository.JourneySearchService
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The journeys ask for a full foreign pack after a search and search again when the files change (design §3.2–3.3). */
class JourneySearchForeignPacksTest {
    @get:Rule val main = ViewModelMainDispatcherRule()

    private val milano = Station("curated:S01700", "Milano Centrale", "Milano Centrale", railwayCode = "S01700")
    private val bern = Station("curated:NC000041", "Bern", "Bern")
    private val pins = ForeignPackFixtures.pins(
        ForeignPackFixtures.packJson("ch-otd-gtfs", "CH", ForeignPackFixtures.archive("ch-otd-gtfs"),
            served = listOf("NC000041", "NC000063", "A", "D"), zone = listOf("NC000063"))
    )
    private val generation = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val offers = ForeignPackOffers(
        pins, generation, { false }, { _, _ -> ForeignPackStatus.BORDER_ONLY }, { _, _ -> ForeignPackDownloadResult.Installed }, {}, scope,
        { LocalDate.of(2026, 11, 30) }
    )
    private val offline = JourneyFixtures.provider(mapOf(JourneyFixtures.pack() to JourneyFixtures.feed(listOf(
        Trip("D1", "1", listOf("A" to "08:00", "D" to "10:00"))
    ))))

    @After fun stop() = scope.cancel()

    private fun counting(calls: AtomicInteger) = object : JourneyProvider by offline {
        override suspend fun searchJourneys(request: JourneySearchRequest) = offline.searchJourneys(request).also { calls.incrementAndGet() }
    }

    private fun JourneySearchViewModel.awaitIdle() = runBlocking {
        withTimeout(10_000) { state.first { !it.loading && it.result != null } }
    }

    @Test
    fun `a search that finds nothing for an end outside the zone offers the full pack`() {
        val model = main.track(JourneySearchViewModel(JourneySearchService(listOf(offline)), foreignPacks = offers))
        model.searchJourneys(milano, bern, JourneyFixtures.monday, LocalTime.of(7, 55))

        val offer = model.awaitIdle().foreignPackOffer!!

        assertEquals(ForeignPackOffer.Style.DIALOG, offer.style)
        assertEquals(listOf("ch-otd-gtfs"), offer.packs.map { it.sourceId.value })
    }

    @Test
    fun `options between two ends outside the zone of the same pack give the banner, a new search forgets it`() {
        val model = main.track(JourneySearchViewModel(JourneySearchService(listOf(offline)), foreignPacks = offers))
        model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(7, 55))
        assertEquals(ForeignPackOffer.Style.BANNER, model.awaitIdle().foreignPackOffer?.style)

        // Neither end is served by the pack: nothing to ask.
        model.searchJourneys(JourneyFixtures.beta, JourneyFixtures.gamma, JourneyFixtures.monday, LocalTime.of(7, 55))
        assertNull(model.state.value.foreignPackOffer)
        assertNull(model.awaitIdle().foreignPackOffer)
    }

    @Test
    fun `an install or a delete searches the shown journeys again on the new files`() {
        val before = AtomicInteger()
        val after = AtomicInteger()
        var current = JourneySearchService(listOf(counting(before)))
        val model = main.track(JourneySearchViewModel(current, foreignPacks = offers, currentService = { current }))
        model.searchJourneys(JourneyFixtures.alfa, JourneyFixtures.delta, JourneyFixtures.monday, LocalTime.of(7, 55))
        model.awaitIdle()
        assertEquals(1, before.get())

        current = JourneySearchService(listOf(counting(after)))
        generation.value = 1L

        runBlocking { withTimeout(10_000) { while (after.get() == 0) kotlinx.coroutines.delay(10) } }
        model.awaitIdle()
        assertEquals(1, before.get())
        assertEquals(1, after.get())
        assertEquals(LocalTime.of(7, 55), model.state.value.query?.departure?.toLocalTime())
    }

    @Test
    fun `without the foreign packs nothing is offered`() {
        val model = main.track(JourneySearchViewModel(JourneySearchService(listOf(offline))))
        model.searchJourneys(milano, bern, JourneyFixtures.monday, LocalTime.of(7, 55))
        assertNull(model.awaitIdle().foreignPackOffer)
    }
}
