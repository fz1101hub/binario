package it.binario.app.data.live

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Sources of the route and board data that are paused right now, by display name: their circuit breaker is open
 * (repeated failures, or the `Retry-After` the source itself asked for). Process memory only; read by the data
 * status line ("… in pausa fino alle 10:42").
 */
object SourcePauses {
    private val pauses = ConcurrentHashMap<String, Instant>()

    internal fun pause(label: String, until: Instant) {
        pauses.merge(label, until) { old, new -> maxOf(old, new) }
    }

    internal fun resume(label: String) {
        pauses.remove(label)
    }

    /** The paused sources and when each will be asked again, earliest first; expired pauses are dropped. */
    fun active(now: Instant = Instant.now()): List<Pair<String, Instant>> {
        pauses.entries.removeIf { !now.isBefore(it.value) }
        return pauses.entries.map { it.key to it.value }.sortedBy { it.second }
    }

    internal fun clearForTest() = pauses.clear()
}

/**
 * The pause a `Retry-After` header asks for: delta-seconds or an HTTP-date. Null when absent, unreadable or not
 * in the future.
 */
internal fun retryAfterOf(header: String?, clock: Clock = Clock.systemUTC()): Duration? {
    val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toLongOrNull()?.let { seconds -> return if (seconds > 0) Duration.ofSeconds(seconds) else null }
    val date = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() ?: return null
    return Duration.between(clock.instant(), date).takeIf { !it.isNegative && !it.isZero }
}
