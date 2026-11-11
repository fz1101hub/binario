package it.binario.app.data.live

import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class SourceFailureKind { NETWORK, TIMEOUT, HTTP, PARSE, CIRCUIT_OPEN }

internal data class SourceFailure(
    val kind: SourceFailureKind,
    val message: String,
    val causeClass: String? = null,
    val httpStatus: Int? = null,
    /** For [SourceFailureKind.CIRCUIT_OPEN]: when the source will be contacted again. */
    val pausedUntil: Instant? = null
)

/** Outcome of a cached public-source read. A failure never throws to the caller. */
internal sealed interface SourceRead<out T> {
    data class Fresh<T>(val value: T, val fetchedAt: Instant, val expiresAt: Instant, val fromCache: Boolean) : SourceRead<T>
    /** The last good value, older than its TTL, kept because the refresh failed (stale-while-revalidate). */
    data class Stale<T>(val value: T, val fetchedAt: Instant, val failure: SourceFailure) : SourceRead<T>
    data class Failed(val failure: SourceFailure) : SourceRead<Nothing>
}

/**
 * Per-source circuit breaker: after [failureThreshold] consecutive failures the source is not contacted for
 * an open period that doubles up to [maxOpenDuration]; one trial request is let through when it expires.
 * A `Retry-After` the source sent with its failure (429, 503) opens it at once for that long, at most [maxRetryAfter].
 * When [pauseLabel] is set, every pause is published to [SourcePauses] so the data status can say until when.
 */
internal class SourceCircuitBreaker(
    private val clock: Clock,
    private val failureThreshold: Int = 3,
    private val baseOpenDuration: Duration = Duration.ofMinutes(1),
    private val maxOpenDuration: Duration = Duration.ofMinutes(15),
    private val maxRetryAfter: Duration = Duration.ofHours(1),
    private val pauseLabel: String? = null
) {
    private var consecutiveFailures = 0
    private var openings = 0
    private var openUntil: Instant? = null

    @Synchronized
    fun allowRequest(): Boolean {
        val until = openUntil ?: return true
        if (clock.instant().isBefore(until)) return false
        openUntil = null
        // Half-open: one more failure re-opens the circuit immediately.
        consecutiveFailures = failureThreshold - 1
        return true
    }

    /**
     * A success closes the circuit, except while it is open: no request is let through then, so that success belongs
     * to a request sent before the source refused, and a refusal is not cancelled by it (audit fix-android-4).
     */
    @Synchronized
    fun recordSuccess() {
        if (openUntil?.let { clock.instant().isBefore(it) } == true) return
        consecutiveFailures = 0
        openings = 0
        openUntil = null
        pauseLabel?.let(SourcePauses::resume)
    }

    @Synchronized
    fun recordFailure(retryAfter: Duration? = null) {
        consecutiveFailures += 1
        val now = clock.instant()
        val askedPause = retryAfter?.takeIf { !it.isNegative && !it.isZero }?.coerceAtMost(maxRetryAfter)
        if (askedPause != null) {
            openUntil = maxOf(openUntil ?: now, now.plus(askedPause))
            openings += 1
            consecutiveFailures = 0
        } else if (consecutiveFailures >= failureThreshold) {
            val factor = 1L shl openings.coerceAtMost(6)
            val duration = baseOpenDuration.multipliedBy(factor).coerceAtMost(maxOpenDuration)
            openUntil = now.plus(duration)
            openings += 1
            consecutiveFailures = 0
        }
        openUntil?.takeIf { now.isBefore(it) }?.let { until -> pauseLabel?.let { SourcePauses.pause(it, until) } }
    }

    @Synchronized
    fun isOpen(): Boolean = openUntil?.let { clock.instant().isBefore(it) } ?: false

    /** When the open circuit lets a request through again; null while closed. */
    @Synchronized
    fun pausedUntil(): Instant? = openUntil?.takeIf { clock.instant().isBefore(it) }

    private fun Duration.coerceAtMost(maximum: Duration): Duration = if (this > maximum) maximum else this
}

/**
 * Bounded cache for one public source: request coalescing (one in-flight load per key), TTL, stale values kept
 * for [maxStale] after a failed refresh, one limited retry for transient network errors and a circuit breaker.
 * Loads run on [Dispatchers.IO], never on the caller's thread, and finish even when the caller stops waiting.
 * Parse errors and HTTP statuses are not retried.
 *
 * With [breakerPerHost] the keys are URLs and each host has its own breaker (one failing GBFS operator does not
 * pause the others); otherwise [breaker] covers the whole source. A failure without a stale value is answered from
 * memory for [failureTtl], so a source that is down is not asked again by every search.
 */
internal class ResilientSourceCache<T>(
    private val ttl: Duration,
    private val maxStale: Duration,
    private val clock: Clock,
    private val maxEntries: Int = 32,
    private val breaker: SourceCircuitBreaker = SourceCircuitBreaker(clock),
    private val retryDelayMillis: Long = 750,
    private val breakerPerHost: Boolean = false,
    private val failureTtl: Duration = Duration.ZERO
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "Il TTL deve essere positivo" }
        require(!maxStale.isNegative) { "La durata stale non può essere negativa" }
        require(maxEntries > 0) { "La cache deve contenere almeno una voce" }
        require(!failureTtl.isNegative) { "La durata dei fallimenti non può essere negativa" }
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = LinkedHashMap<String, Entry<T>>()
    private val failures = LinkedHashMap<String, Pair<SourceFailure, Instant>>()
    private val inFlight = HashMap<String, Deferred<SourceRead<T>>>()
    private val hostBreakers = LinkedHashMap<String, SourceCircuitBreaker>()

    suspend fun get(key: String, load: suspend () -> T): SourceRead<T> {
        val pending = mutex.withLock {
            val now = clock.instant()
            entries[key]?.takeIf { now.isBefore(it.expiresAt) }?.let {
                return SourceRead.Fresh(it.value, it.fetchedAt, it.expiresAt, fromCache = true)
            }
            failures[key]?.let { (failure, until) ->
                if (now.isBefore(until)) return SourceRead.Failed(failure) else failures.remove(key)
            }
            inFlight[key] ?: scope.async { refresh(key, load) }.also { inFlight[key] = it }
        }
        return pending.await()
    }

    fun circuitOpen(): Boolean = breaker.isOpen() || synchronized(hostBreakers) { hostBreakers.values.any { it.isOpen() } }

    private fun breakerFor(key: String): SourceCircuitBreaker {
        if (!breakerPerHost) return breaker
        val host = runCatching { java.net.URI(key).host?.lowercase(java.util.Locale.ROOT) }.getOrNull() ?: return breaker
        return synchronized(hostBreakers) {
            hostBreakers.getOrPut(host) { SourceCircuitBreaker(clock) }.also {
                while (hostBreakers.size > maxEntries) hostBreakers.remove(hostBreakers.keys.first())
            }
        }
    }

    private suspend fun refresh(key: String, load: suspend () -> T): SourceRead<T> {
        val circuit = breakerFor(key)
        try {
            if (!circuit.allowRequest()) {
                return staleOrFailed(key, SourceFailure(SourceFailureKind.CIRCUIT_OPEN, "Fonte sospesa dopo errori ripetuti",
                    pausedUntil = circuit.pausedUntil()))
            }
            var failure: SourceFailure? = null
            var retryAfter: Duration? = null
            for (attempt in 0 until MAX_ATTEMPTS) {
                try {
                    val value = load()
                    circuit.recordSuccess()
                    val now = clock.instant()
                    val entry = Entry(value, now, now.plus(ttl))
                    mutex.withLock {
                        failures.remove(key)
                        entries.remove(key)
                        entries[key] = entry
                        while (entries.size > maxEntries) entries.remove(entries.keys.first())
                    }
                    return SourceRead.Fresh(value, now, entry.expiresAt, fromCache = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: PublicSourcePausedException) {
                    // The host is paused after a refusal: nothing was asked, so nothing is retried or counted.
                    return staleOrFailed(key, SourceFailure(SourceFailureKind.CIRCUIT_OPEN, error.message.orEmpty(), error::class.java.name))
                } catch (error: PublicSourceHttpException) {
                    failure = SourceFailure(SourceFailureKind.HTTP, error.message.orEmpty(), error::class.java.name, error.status)
                    retryAfter = error.retryAfter
                    break
                } catch (error: SocketTimeoutException) {
                    failure = SourceFailure(SourceFailureKind.TIMEOUT, error.message.orEmpty(), error::class.java.name)
                    break
                } catch (error: IOException) {
                    failure = SourceFailure(SourceFailureKind.NETWORK, error.message.orEmpty(), error::class.java.name)
                    if (attempt + 1 < MAX_ATTEMPTS && retryDelayMillis >= 0) delay(retryDelayMillis)
                } catch (error: Exception) {
                    // Malformed or unexpected input: a typed failure, never an exception for the UI.
                    failure = SourceFailure(SourceFailureKind.PARSE, error.message.orEmpty().take(200), error::class.java.name)
                    break
                }
            }
            circuit.recordFailure(retryAfter)
            val result = staleOrFailed(key, failure ?: SourceFailure(SourceFailureKind.NETWORK, "Fonte non raggiungibile"))
            if (result is SourceRead.Failed && !failureTtl.isZero) mutex.withLock {
                failures.remove(key)
                failures[key] = result.failure to clock.instant().plus(failureTtl)
                while (failures.size > maxEntries) failures.remove(failures.keys.first())
            }
            return result
        } finally {
            mutex.withLock { inFlight.remove(key) }
        }
    }

    private suspend fun staleOrFailed(key: String, failure: SourceFailure): SourceRead<T> = mutex.withLock {
        val now = clock.instant()
        val entry = entries[key]
        if (entry != null && !now.isAfter(entry.expiresAt.plus(maxStale))) {
            SourceRead.Stale(entry.value, entry.fetchedAt, failure)
        } else {
            SourceRead.Failed(failure)
        }
    }

    private data class Entry<T>(val value: T, val fetchedAt: Instant, val expiresAt: Instant)

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}

/** Maps a cached read to the provider contract: stale values are a `Partial` with [ProviderWarning.STALE_SOURCE]. */
internal fun <T, R> SourceRead<T>.toProviderResult(
    sourceId: SourceId,
    unavailableMessage: String,
    unreadableMessage: String,
    map: (value: T, fetchedAt: Instant) -> R
): ProviderResult<R> = when (this) {
    is SourceRead.Fresh -> ProviderResult.Success(
        map(value, fetchedAt),
        ProviderResponseMeta(sourceId, fetchedAt, observedAt = fetchedAt, expiresAt = expiresAt, fromCache = fromCache)
    )
    is SourceRead.Stale -> ProviderResult.Partial(
        map(value, fetchedAt),
        setOf(ProviderWarning.STALE_SOURCE),
        ProviderResponseMeta(sourceId, fetchedAt, observedAt = fetchedAt, fromCache = true)
    )
    is SourceRead.Failed -> ProviderResult.Failure(failure.toProviderError(unavailableMessage, unreadableMessage))
}

internal fun SourceFailure.toProviderError(unavailableMessage: String, unreadableMessage: String): ProviderError = when (kind) {
    SourceFailureKind.PARSE -> ProviderError(ProviderErrorKind.PARSE_ERROR, unreadableMessage, causeClass)
    SourceFailureKind.TIMEOUT -> ProviderError(ProviderErrorKind.TIMEOUT, unavailableMessage, causeClass)
    SourceFailureKind.CIRCUIT_OPEN -> ProviderError(ProviderErrorKind.UPSTREAM_UNAVAILABLE, unavailableMessage, causeClass)
    SourceFailureKind.HTTP -> ProviderError(
        when (httpStatus) {
            404 -> ProviderErrorKind.NOT_FOUND
            429 -> ProviderErrorKind.RATE_LIMITED
            else -> ProviderErrorKind.UPSTREAM_UNAVAILABLE
        },
        unavailableMessage,
        causeClass
    )
    SourceFailureKind.NETWORK -> ProviderError(ProviderErrorKind.NETWORK, unavailableMessage, causeClass)
}
