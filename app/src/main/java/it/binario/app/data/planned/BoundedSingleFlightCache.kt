package it.binario.app.data.planned

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    private val inFlight = mutableMapOf<String, Deferred<TimedCacheValue<T>>>()

    suspend fun getOrLoad(key: String, expireFromLoadStart: Boolean = false, loader: suspend () -> T): TimedCacheValue<T> {
        val lookup = mutex.withLock {
            val now = clockMillis()
            values.entries.removeAll { now >= it.value.expiresAtEpochMillis }
            values[key]?.let { return@withLock Lookup.Ready(it) }
            inFlight[key]?.let { return@withLock Lookup.Pending(it, owner = false) }
            val worker = workerScope.async(start = CoroutineStart.LAZY) {
                try {
                    val startedAt = clockMillis()
                    val value = loader()
                    val fetchedAt = clockMillis()
                    val timed = TimedCacheValue(value, fetchedAt, (if (expireFromLoadStart) startedAt else fetchedAt) + ttlMillis)
                    mutex.withLock {
                        values[key] = timed
                        while (values.size > maxEntries) values.remove(values.keys.first())
                        inFlight.remove(key)
                    }
                    timed
                } catch (error: Throwable) {
                    mutex.withLock { inFlight.remove(key) }
                    throw error
                }
            }
            inFlight[key] = worker
            Lookup.Pending(worker, owner = true)
        }
        if (lookup is Lookup.Ready) return lookup.value
        lookup as Lookup.Pending
        if (lookup.owner) lookup.deferred.start()
        return lookup.deferred.await()
    }

    suspend fun sizeForTest(): Int = mutex.withLock { values.size }

    private sealed interface Lookup<T> {
        data class Ready<T>(val value: TimedCacheValue<T>) : Lookup<T>
        data class Pending<T>(
            val deferred: Deferred<TimedCacheValue<T>>,
            val owner: Boolean
        ) : Lookup<T>
    }
}
