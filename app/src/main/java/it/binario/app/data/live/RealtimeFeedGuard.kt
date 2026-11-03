package it.binario.app.data.live

import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Failure memory and circuit breaker of one GTFS-Realtime feed, used under the adapter's own feed lock (so caller
 * cancellation still reaches the request). A failed feed is answered from memory for [failureCooldown] instead of
 * being asked again by every search; repeated failures, or a `Retry-After`, pause it ([SourcePauses], under [label]).
 */
internal class RealtimeFeedGuard(
    private val clock: Clock,
    label: String,
    private val failureCooldown: Duration = Duration.ofSeconds(30)
) {
    private val breaker = SourceCircuitBreaker(clock, pauseLabel = label)
    private var lastFailure: Pair<ProviderError, Instant>? = null

    /** The answer to give without a request: the recent failure, or the open circuit. Null when the feed may be asked. */
    fun blocked(): ProviderResult.Failure? {
        val now = clock.instant()
        lastFailure?.takeIf { now.isBefore(it.second) }?.let { return ProviderResult.Failure(it.first) }
        if (!breaker.allowRequest()) {
            val until = breaker.pausedUntil()
            return ProviderResult.Failure(
                ProviderError(ProviderErrorKind.UPSTREAM_UNAVAILABLE, "Fonte realtime sospesa dopo errori ripetuti."),
                until?.let { Duration.between(now, it) }
            )
        }
        return null
    }

    fun succeeded() {
        lastFailure = null
        breaker.recordSuccess()
    }

    fun failed(error: ProviderError, cause: Throwable): ProviderResult.Failure {
        breaker.recordFailure((cause as? PublicSourceHttpException)?.retryAfter)
        lastFailure = error to clock.instant().plus(failureCooldown)
        return ProviderResult.Failure(error)
    }
}
