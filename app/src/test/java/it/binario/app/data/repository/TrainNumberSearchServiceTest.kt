package it.binario.app.data.repository

import it.binario.app.data.*
import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class TrainNumberSearchServiceTest {
    private val date = LocalDate.of(2026, 10, 16)
    private val now = date.atStartOfDay(ZoneOffset.UTC).toInstant()
    private val query = TrainNumberQuery("23263", date)
    private fun match(id: String, live: Boolean = false) = TrainNumberMatch.create(TrainService(
        id = id, number = "23263", operator = RailOperator.OTHER, trainCategory = TrainCategory.REGIONALE,
        origin = "Origin $id", destination = "Destination $id", scheduledDeparture = "08:00", expectedDeparture = "08:00",
        platform = null, delayMinutes = 0, status = ServiceStatus.ON_TIME, stops = emptyList(),
        operationalStatusVerified = live, scheduledDepartureDateTime = date.atTime(8, 0)), "Test", DataConfidence.STATIC_ONLY, now.toEpochMilli(), now.plusSeconds(60).toEpochMilli())
    private fun provider(id: String, block: suspend () -> ProviderResult<List<TrainNumberMatch>>) = object : TrainNumberSearchProvider {
        override val descriptor = ProviderDescriptor(SourceId(id), id, setOf(ProviderCapability.TRAIN_RUN_REALTIME), CoverageDescriptor(description = "test"), AccessMode.OPEN_DIRECT, RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofSeconds(30)), 1)
        override suspend fun searchByNumber(query: TrainNumberQuery) = block()
    }
    private fun result(vararg matches: TrainNumberMatch) = ProviderResult.Success(matches.toList(), ProviderResponseMeta(SourceId("test"), now))

    @Test fun `matches exact numbers category prefixes and line labels without substring matches`() {
        assertTrue(query.matches("R 023263"))
        assertTrue(TrainNumberQuery("25107", date).matches("S10 - 25107"))
        assertTrue(TrainNumberQuery("EC151", date).matches("EC 151"))
        assertFalse(query.matches("232630"))
        assertFalse(TrainNumberQuery("2326", date).matches("23263"))
        assertFalse(TrainNumberQuery("", date).valid)
        assertFalse(TrainNumberQuery("Roma", date).valid)
        assertFalse(TrainNumberQuery("23263/", date).valid)
        assertTrue(TrainNumberQuery("RE 42", date).valid)
    }
    @Test fun `same public number can identify multiple independent runs`() = runBlocking {
        val source = TrainNumberSearchService(listOf(provider("a") { result(match("a")) }, provider("b") { result(match("b")) }))
        assertEquals(setOf("a", "b"), source.search(query).last().matches.map { it.id }.toSet())
    }
    @Test fun `fast source is published before a blocked source and cancellation propagates`() = runBlocking {
        var cancelled = false
        val started = CompletableDeferred<Unit>()
        val slow = provider("slow") { started.complete(Unit); try { awaitCancellation() } finally { cancelled = true } }
        val source = TrainNumberSearchService(listOf(provider("fast") { started.await(); result(match("fast")) }, slow))
        val update = withTimeout(2_000) { source.search(query).first() }
        assertFalse(update.complete)
        assertEquals("fast", update.matches.single().id)
        assertTrue(cancelled)
    }
    @Test fun `provider failures preserve other results and mark the unavailable source`() = runBlocking {
        val source = TrainNumberSearchService(listOf(provider("bad") { error("offline") }, provider("ok") { result(match("ok")) }))
        val update = source.search(query).last()
        assertTrue(update.complete)
        assertEquals("ok", update.matches.single().id)
        assertEquals(setOf("bad"), update.unavailableSources)
    }
    @Test fun `live enrichment replaces the static version of the same native run`() = runBlocking {
        val source = TrainNumberSearchService(listOf(provider("static") { result(match("same")) }, provider("live") { result(match("same", true)) }))
        assertTrue(source.search(query).last().matches.single().service.operationalStatusVerified)
    }
    @Test fun `unrelated results never leak from a source into the search`() = runBlocking {
        val wrong = match("wrong").let { it.copy(service = it.service.copy(number = "232630")) }
        val source = TrainNumberSearchService(listOf(provider("a") { result(wrong) }))
        assertTrue(source.search(query).last().matches.isEmpty())
    }
}
