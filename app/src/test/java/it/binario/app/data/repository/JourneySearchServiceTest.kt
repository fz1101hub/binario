package it.binario.app.data.repository

import it.binario.app.data.Station
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.DataWarning
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.JourneyId
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.JourneyProvider
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.RatePolicy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneySearchServiceTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-11-02T08:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val origin = Station("curated:S01825", "Lodi", "Lodi", railwayCode = "S01825", aliases = listOf("LODI"))
    private val destination = Station("curated:S01520", "Lecco", "Lecco", railwayCode = "S01520")
    private val query = JourneySearchQuery(origin, destination, LocalDateTime.of(2026, 11, 10, 8, 0))

    private class FakeProvider(
        id: String,
        private val answer: suspend (JourneySearchRequest) -> ProviderResult<List<JourneyOption>>
    ) : JourneyProvider {
        val requests = mutableListOf<JourneySearchRequest>()
        override val descriptor = ProviderDescriptor(
            id = SourceId(id), displayName = "Fonte $id", capabilities = setOf(ProviderCapability.JOURNEY_SEARCH),
            coverage = CoverageDescriptor(description = "test"), accessMode = AccessMode.OPEN_DIRECT,
            ratePolicy = RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofMinutes(1)), schemaVersion = 1
        )

        override suspend fun searchJourneys(request: JourneySearchRequest): ProviderResult<List<JourneyOption>> {
            requests += request
            return answer(request)
        }
    }

    private fun option(staticOnly: Boolean = true): JourneyOption {
        val departure = LocalDateTime.of(2026, 11, 10, 8, 9).atZone(zone)
        return JourneyOption(
            id = JourneyId("j1"), origin = StationId(origin.id), destination = StationId(destination.id),
            departure = departure, arrival = departure.plusMinutes(105),
            legs = listOf(JourneyLeg(LegMode.TRAIN, origin = StationId(origin.id), destination = StationId(destination.id),
                scheduledDeparture = departure, scheduledArrival = departure.plusMinutes(105))),
            snapshot = SnapshotMeta(1, now, freshness = Freshness.AGING,
                completeness = if (staticOnly) Completeness.STATIC_ONLY else Completeness.COMPLETE, contributingSources = emptySet(),
                warnings = if (staticOnly) setOf(DataWarning.STATIC_ONLY) else emptySet())
        )
    }

    private fun meta(id: String) = ProviderResponseMeta(SourceId(id), now)
    private fun success(id: String, vararg options: JourneyOption) = ProviderResult.Success(options.toList(), meta(id))

    @Test fun `the first provider with options answers and the request carries the stations and the local time`() = runBlocking {
        val first = FakeProvider("first") { success("first", option()) }
        val second = FakeProvider("second") { success("second", option()) }
        val result = JourneySearchService(listOf(first, second), clock = clock).search(query)
        assertEquals(JourneySearchStatus.FOUND, result.status)
        assertEquals(listOf(SourceId("first")), result.consultedSources)
        assertTrue(second.requests.isEmpty())
        val sourced = result.options.single()
        assertEquals(SourceId("first"), sourced.sourceId)
        assertEquals("Fonte first", sourced.sourceName)
        assertTrue(sourced.scheduledOnly)
        val request = first.requests.single()
        assertEquals(StationId("curated:S01825"), request.origin)
        assertEquals(LocalDateTime.of(2026, 11, 10, 8, 0).atZone(zone), request.departureAt)
        assertEquals("Lodi", request.originEndpoint?.name)
        assertEquals(listOf("LODI"), request.originEndpoint?.aliases)
        assertEquals("S01520", request.destinationEndpoint?.railwayCode)
        assertEquals(2, request.maxTransfers)
        assertEquals(6, request.maxResults)
    }

    @Test fun `a failing, slow, throwing or empty provider falls back to the next one`() = runBlocking {
        val failing = FakeProvider("failing") { ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "down")) }
        val slow = FakeProvider("slow") { awaitCancellation() }
        val throwing = FakeProvider("throwing") { throw IllegalStateException("bug") }
        val empty = FakeProvider("empty") { success("empty") }
        val offline = FakeProvider("offline") { success("offline", option(staticOnly = false)) }
        val result = JourneySearchService(listOf(failing, slow, throwing, empty, offline), providerTimeoutMillis = 50, clock = clock).search(query)
        assertEquals(JourneySearchStatus.FOUND, result.status)
        assertEquals(listOf("failing", "slow", "throwing", "empty", "offline"), result.consultedSources.map { it.value })
        assertEquals(SourceId("offline"), result.options.single().sourceId)
        assertFalse(result.options.single().scheduledOnly)
    }

    @Test fun `without options the status says why, the most specific reason first`() = runBlocking {
        val notCovered = FakeProvider("a") { ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_SUPPORTED, "no")) }
        val expired = FakeProvider("b") { ProviderResult.Partial(emptyList(), setOf(ProviderWarning.STALE_SOURCE), meta("b")) }
        val empty = FakeProvider("c") { success("c") }
        val failing = FakeProvider("d") { ProviderResult.Failure(ProviderError(ProviderErrorKind.TIMEOUT, "slow")) }
        suspend fun status(vararg providers: JourneyProvider) = JourneySearchService(providers.toList(), clock = clock).search(query).status
        assertEquals(JourneySearchStatus.NO_SOLUTION, status(notCovered, expired, empty, failing))
        assertEquals(JourneySearchStatus.SCHEDULE_EXPIRED, status(notCovered, expired, failing))
        assertEquals(JourneySearchStatus.NOT_COVERED, status(notCovered, failing))
        assertEquals(JourneySearchStatus.UNAVAILABLE, status(failing))
        assertEquals(JourneySearchStatus.UNAVAILABLE, status())
    }

    @Test fun `incomplete coverage is passed on with the options`() = runBlocking {
        val partial = FakeProvider("p") { ProviderResult.Partial(listOf(option()), setOf(ProviderWarning.INCOMPLETE_COVERAGE), meta("p")) }
        val result = JourneySearchService(listOf(partial), clock = clock).search(query)
        assertEquals(JourneySearchStatus.FOUND, result.status)
        assertTrue(result.incompleteCoverage)
    }

    @Test fun `the same station twice is never sent to a provider`() = runBlocking {
        val provider = FakeProvider("p") { success("p", option()) }
        val result = JourneySearchService(listOf(provider), clock = clock).search(query.copy(destination = origin))
        assertEquals(JourneySearchStatus.UNAVAILABLE, result.status)
        assertTrue(provider.requests.isEmpty())
    }
}
