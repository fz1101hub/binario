package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.core.StationId
import it.binario.app.data.provider.*
import java.time.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RealtimeCacheConcurrencyTest {
    private val initial = Instant.parse("2026-10-18T10:00:00Z")
    private val ticks = AtomicLong(initial.toEpochMilli())
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(ticks.get())
    }
    private suspend fun burst(calls: List<suspend () -> Any>) = coroutineScope {
        calls.flatMap { call -> List(12) { async(Dispatchers.Default) { call() } } }.awaitAll().forEach {
            assertTrue(it is ProviderResult.Success<*>)
        }
    }

    @Test fun `STA coalesces all three feed misses and refreshes once after expiry`() = runBlocking {
        val counts = ConcurrentHashMap<StaRealtimeFeed, AtomicInteger>()
        val adapter = StaRealtimeAdapter(StaRealtimeFetcher { feed ->
            counts.computeIfAbsent(feed) { AtomicInteger() }.incrementAndGet()
            delay(30)
            """{"header":{"timestamp":${clock.instant().epochSecond}},"entity":[]}"""
        }, clock)
        val operations: List<suspend () -> Any> = listOf({ adapter.getAlerts(AlertRequest()) }, { adapter.getVehiclePositions() }, { adapter.getTripUpdates() })
        burst(operations)
        assertEquals(setOf(1), counts.values.map { it.get() }.toSet())
        assertEquals(3, counts.size)
        ticks.addAndGet(91_000)
        burst(operations)
        assertEquals(setOf(2), counts.values.map { it.get() }.toSet())
    }

    @Test fun `SNCF coalesces alerts and trip updates and refreshes once after expiry`() = runBlocking {
        val counts = ConcurrentHashMap<SncfRealtimeFeed, AtomicInteger>()
        val adapter = SncfGtfsRealtimeAdapter(SncfRealtimeFetcher { feed ->
            counts.computeIfAbsent(feed) { AtomicInteger() }.incrementAndGet()
            delay(30)
            GtfsRealtime.FeedMessage.newBuilder().setHeader(GtfsRealtime.FeedHeader.newBuilder()
                .setGtfsRealtimeVersion("2.0").setTimestamp(clock.instant().epochSecond)).build().toByteArray()
        }, clock)
        val operations: List<suspend () -> Any> = listOf({ adapter.getAlerts(AlertRequest()) }, { adapter.getTripUpdates() })
        burst(operations)
        assertEquals(2, counts.size)
        assertEquals(setOf(1), counts.values.map { it.get() }.toSet())
        ticks.addAndGet(121_000)
        burst(operations)
        assertEquals(setOf(2), counts.values.map { it.get() }.toSet())
    }

    @Test fun `STA cached alerts still apply each requested station filter`() = runBlocking {
        var fetches = 0
        val adapter = StaRealtimeAdapter(StaRealtimeFetcher {
            fetches++
            """{"header":{"timestamp":${initial.epochSecond}},"entity":[{"id":"a","alert":{"header_text":{"translation":[{"text":"A"}]},"informed_entity":[{"stop_id":"A"}]}},{"id":"b","alert":{"header_text":{"translation":[{"text":"B"}]},"informed_entity":[{"stop_id":"B"}]}}]}"""
        }, clock)
        suspend fun ids(station: String) = (adapter.getAlerts(AlertRequest(stationIds = setOf(StationId("sta:$station")))) as ProviderResult.Success).value.map { it.id.value }
        assertEquals(listOf("sta:a"), ids("A"))
        assertEquals(listOf("sta:b"), ids("B"))
        assertTrue(ids("C").isEmpty())
        assertEquals(1, fetches)
    }

    @Test fun `STA and SNCF propagate caller cancellation and release their feed locks`() = runBlocking {
        for (sta in listOf(true, false)) {
            val started = CompletableDeferred<Unit>()
            var returned = false
            var count = 0
            val blocked: suspend () -> Unit = { count++; if (count == 1) { started.complete(Unit); awaitCancellation() } }
            val operation: suspend () -> Any = if (sta) {
                val adapter = StaRealtimeAdapter(StaRealtimeFetcher { blocked(); """{"entity":[]}""" }, clock)
                val invoke: suspend () -> Any = { adapter.getTripUpdates() }
                invoke
            } else {
                val adapter = SncfGtfsRealtimeAdapter(SncfRealtimeFetcher {
                    blocked(); GtfsRealtime.FeedMessage.newBuilder().setHeader(GtfsRealtime.FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0")).build().toByteArray()
                }, clock)
                val invoke: suspend () -> Any = { adapter.getTripUpdates() }
                invoke
            }
            val pending = launch { operation(); returned = true }
            started.await()
            pending.cancelAndJoin()
            assertFalse(returned)
            assertTrue(withTimeout(2_000) { operation() } is ProviderResult.Success<*>)
        }
    }
}
