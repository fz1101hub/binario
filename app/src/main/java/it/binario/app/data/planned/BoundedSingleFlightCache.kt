package it.binario.app.data.planned

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TimedCacheValue<T>(
    val value: T,
    val fetchedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
)

/** Bounded FIFO cache that coalesces concurrent misses for the same key. */
internal class BoundedSingleFlightCache<T>(
    private val maxEntries: Int,
    private val ttlMillis: Long,
    private val clockMillis: () -> Long
) {
    init {
        require(maxEntries > 0) { "La cache deve contenere almeno una voce" }
        require(ttlMillis > 0) { "La TTL della cache deve essere positiva" }
    }

    private val mutex = Mutex()
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val values = linkedMapOf<String, TimedCacheValue<T>>()
    private val inFlight = mutableMapOf<String, Flight<T>>()

    suspend fun getOrLoad(
        key: String,
        expireFromLoadStart: Boolean = false,
        cancelWhenUnused: Boolean = false,
        loader: suspend () -> T
    ): TimedCacheValue<T> {
        val lookup = mutex.withLock {
            val now = clockMillis()
            values.entries.removeAll { now >= it.value.expiresAtEpochMillis }
            values[key]?.let { return@withLock Lookup.Ready(it) }
            inFlight[key]?.let {
                it.waiters += 1
                it.cancelWhenUnused = it.cancelWhenUnused && cancelWhenUnused
                return@withLock Lookup.Pending(it, owner = false)
            }
            lateinit var flight: Flight<T>
            val worker = workerScope.async(start = CoroutineStart.LAZY) {
                try {
                    val startedAt = clockMillis()
                    val value = loader()
                    val fetchedAt = clockMillis()
                    val timed = TimedCacheValue(value, fetchedAt, (if (expireFromLoadStart) startedAt else fetchedAt) + ttlMillis)
                    mutex.withLock {
                        if (inFlight[key] === flight) {
                            values[key] = timed
                            while (values.size > maxEntries) values.remove(values.keys.first())
                            inFlight.remove(key)
                        }
                    }
                    timed
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        mutex.withLock { if (inFlight[key] === flight) inFlight.remove(key) }
                    }
                    throw error
                }
            }
            flight = Flight(worker, waiters = 1, cancelWhenUnused = cancelWhenUnused)
            inFlight[key] = flight
            Lookup.Pending(flight, owner = true)
        }
        if (lookup is Lookup.Ready) return lookup.value
        lookup as Lookup.Pending
        try {
            if (lookup.owner) lookup.flight.deferred.start()
            return lookup.flight.deferred.await()
        } finally {
            withContext(NonCancellable) {
                val abandoned = mutex.withLock {
                    val flight = lookup.flight
                    flight.waiters -= 1
                    if (flight.waiters == 0 && flight.cancelWhenUnused && !flight.deferred.isCompleted) {
                        if (inFlight[key] === flight) inFlight.remove(key)
                        flight.deferred
                    } else null
                }
                abandoned?.cancel()
            }
        }
    }

    suspend fun sizeForTest(): Int = mutex.withLock { values.size }

    private class Flight<T>(
        val deferred: Deferred<TimedCacheValue<T>>,
        var waiters: Int,
        var cancelWhenUnused: Boolean
    )

    private sealed interface Lookup<T> {
        data class Ready<T>(val value: TimedCacheValue<T>) : Lookup<T>
        data class Pending<T>(
            val flight: Flight<T>,
            val owner: Boolean
        ) : Lookup<T>
    }
}
