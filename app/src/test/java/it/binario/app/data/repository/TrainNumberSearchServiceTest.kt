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
    @Test fun `incremental source retains early matches even if later enrichment times out`() = runBlocking {
        val base = provider("incremental") { result() }
        val source = object : TrainNumberSearchProvider by base {
            override fun observeByNumber(query: TrainNumberQuery) = flow {
                emit(result(match("early")))
                awaitCancellation()
            }
        }
        val updates = withTimeout(3_000) { TrainNumberSearchService(listOf(source), providerTimeoutMillis = 500).search(query).toList() }
        assertEquals("early", updates.first().matches.single().id)
        assertEquals("early", updates.last().matches.single().id)
        assertTrue(updates.last().complete)
        assertEquals(setOf("incremental"), updates.last().unavailableSources)
    }

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

    @Test fun `digits only find the OeBB categories without matching station codes`() {
        // ÖBB pack trip_short_name values (Nightjet Roma/Milano–Wien/München is NJ 40235/40295).
        listOf("NJ 40235" to "40235", "IR 2063" to "2063", "REX 1505" to "1505", "S 2590" to "2590", "CJX 9109" to "9109",
            "D 1130" to "1130", "CAT 9056" to "9056", "ECE 51" to "51", "IRE 3233" to "3233", "ES 9501" to "9501")
            .forEach { (published, digits) -> assertTrue(published, TrainNumberQuery(digits, date).matches(published)) }
        // Trenitalia pack codes are not train numbers; neither are unknown prefixes or substrings.
        assertFalse(TrainNumberQuery("545", date).matches("VE545"))
        assertFalse(TrainNumberQuery("246", date).matches("TO246"))
        assertFalse(TrainNumberQuery("4023", date).matches("NJ 40235"))
        assertFalse(TrainNumberQuery("40235", date).matches("NJ 402350"))
        // The prefixed query still finds the train.
        assertTrue(TrainNumberQuery("NJ40235", date).matches("NJ 40235"))
    }
    @Test fun `a published line and number label is searched by its run number`() {
        // A favourite saved from a timetable row keeps the published label (Trenord "RE_11 - 2153").
        listOf("RE_11 - 2153" to "2153", "R1 - 10101" to "10101", "S5 - 811A" to "811A", "S10 - 25107" to "25107").forEach { (label, run) ->
            val labelled = TrainNumberQuery(label, date)
            assertTrue(label, labelled.valid)
            assertEquals(label, run, labelled.normalized)
            assertTrue(label, labelled.matches(label))
            assertTrue(label, labelled.matches(run))
            assertTrue(label, TrainNumberQuery(run, date).matches(label))
        }
        assertEquals("2153", TrainNumberQuery("RE_11 - 2153", date).numeric)
        assertEquals("10101", TrainNumberQuery("R1 - 10101", date).numeric)
        assertNull(TrainNumberQuery("S5 - 811A", date).numeric)
        // What the search field keeps of the label (no underscore) is the same train; the line is never part of the number.
        assertEquals("2153", TrainNumberQuery("RE11 - 2153", date).numeric)
        assertFalse(TrainNumberQuery("RE_11 - 2153", date).matches("112153"))
        assertFalse(TrainNumberQuery("RE_11 - 2153", date).matches("RE_11 - 21530"))
        assertFalse(TrainNumberQuery("R1 - 10101", date).matches("110101"))
        // A label still being typed, or without a run number, is not a train.
        assertEquals("2153", TrainNumberQuery("2153 - ", date).normalized)
        assertFalse(TrainNumberQuery("RE_11 - ", date).valid)
    }
    @Test fun `a favourite saved with its published label finds the live run and the timetable row`() = runBlocking {
        val live = match("live", live = true)
        val timetable = match("timetable").let { it.copy(service = it.service.copy(number = "RE_11 - 23263")) }
        val source = TrainNumberSearchService(listOf(provider("a") { result(live, timetable) }))
        val update = source.search(TrainNumberQuery("RE_11 - 23263", date)).last()
        assertEquals(setOf("live", "timetable"), update.matches.map { it.id }.toSet())
        assertTrue(update.unavailableSources.isEmpty())
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
