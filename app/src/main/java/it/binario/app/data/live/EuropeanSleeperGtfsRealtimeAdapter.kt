package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.TripUpdateProvider
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import it.binario.app.data.urban.HttpsBytesFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface EuropeanSleeperRealtimeFetcher {
    suspend fun fetch(): ByteArray
}

/** A small community feed: HTTPS on the same host, at most 4 MB, a total time limit and cancellable. */
internal class HttpEuropeanSleeperRealtimeFetcher(
    private val http: HttpsBytesFetcher = HttpsBytesFetcher(maxBytes = 4 * 1024 * 1024, readTimeoutMillis = 15_000, totalTimeoutMillis = 20_000)
) : EuropeanSleeperRealtimeFetcher {
    override suspend fun fetch(): ByteArray = http.fetch(FEED_URL)

    companion object {
        const val FEED_URL = "https://jbb.ghsq.de/gtfs/european-sleeper/TripUpdates"
    }
}

/**
 * Community GTFS-RT fallback for European Sleeper. It never outranks RFI or an
 * operator/infrastructure feed. Before the official Milan launch date it
 * deliberately returns no updates.
 */
class EuropeanSleeperGtfsRealtimeAdapter(
    private val fetcher: EuropeanSleeperRealtimeFetcher = HttpEuropeanSleeperRealtimeFetcher(),
    private val clock: Clock = Clock.systemUTC(),
    private val launchDate: LocalDate = LocalDate.of(2026, 9, 9),
    private val ttl: Duration = Duration.ofMinutes(2)
) : TripUpdateProvider {
    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "European Sleeper — GTFS-Realtime best-effort",
        capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME),
        coverage = CoverageDescriptor(
            countryCodes = setOf("BE", "NL", "DE", "CH", "IT"),
            description = "European Sleeper; fallback per il servizio Bruxelles–Milano quando presente nel feed community"
        ),
        accessMode = AccessMode.PUBLIC_BEST_EFFORT,
        ratePolicy = RatePolicy(Duration.ofMinutes(1), defaultTimeToLive = ttl),
        schemaVersion = 1
    )

    private val mutex = Mutex()
    private var cached: ProviderResult.Success<List<RealtimeStopUpdate>>? = null
    private val guard = RealtimeFeedGuard(clock, "European Sleeper")

    override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> {
        val now = clock.instant()
        if (now.atZone(ZoneId.of("Europe/Rome")).toLocalDate() < launchDate) {
            return ProviderResult.Success(emptyList(), ProviderResponseMeta(SOURCE_ID, now, observedAt = now, expiresAt = now.plus(ttl)))
        }
        return mutex.withLock {
            cached?.takeIf { it.meta.expiresAt?.isAfter(now) == true }?.let { return@withLock it.copy(meta = it.meta.copy(fromCache = true)) }
            guard.blocked()?.let { return@withLock it }
            try {
                val feed = GtfsRealtime.FeedMessage.parseFrom(fetcher.fetch())
                val observed = epochSecondOrNull(feed.header.timestamp) ?: now
                ProviderResult.Success(
                    parse(feed, observed),
                    ProviderResponseMeta(SOURCE_ID, now, observedAt = observed, expiresAt = now.plus(ttl))
                ).also { cached = it; guard.succeeded() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                guard.failed(ProviderError(ProviderErrorKind.NETWORK, "Realtime European Sleeper non raggiungibile.", error::class.java.name), error)
            } catch (error: Exception) {
                guard.failed(ProviderError(ProviderErrorKind.PARSE_ERROR, "Realtime European Sleeper non leggibile.", error::class.java.name), error)
            }
        }
    }

    internal fun parse(feed: GtfsRealtime.FeedMessage, observedAt: java.time.Instant): List<RealtimeStopUpdate> =
        feed.entityList.flatMap { entity ->
            if (!entity.hasTripUpdate()) return@flatMap emptyList()
            val trip = entity.tripUpdate
            val tripId = trip.trip.tripId.takeIf(String::isNotBlank) ?: return@flatMap emptyList()
            val source = SourceRef(
                sourceId = SOURCE_ID,
                nativeId = entity.id.takeIf(String::isNotBlank) ?: tripId,
                observedAt = observedAt,
                fetchedAt = clock.instant(),
                expiresAt = clock.instant().plus(ttl),
                // Community mirror: usable coverage, but deliberately below verified open/operator data.
                confidence = Confidence.DERIVED,
                traceId = "es:$tripId"
            )
            trip.stopTimeUpdateList.mapNotNull { stop ->
                val stopId = stop.stopId.takeIf(String::isNotBlank)
                val sequence = stop.stopSequence.takeIf { stop.hasStopSequence() }
                if (stopId == null && sequence == null) return@mapNotNull null
                val event = when {
                    stop.hasDeparture() -> stop.departure
                    stop.hasArrival() -> stop.arrival
                    else -> null
                }
                RealtimeStopUpdate(
                    runId = TrainRunId("european-sleeper:$tripId"),
                    sourceTripId = tripId,
                    sourceStopId = stopId,
                    stopSequence = sequence,
                    predictedAt = event?.takeIf { it.hasTime() }?.time
                        ?.let(::epochSecondOrNull)
                        ?.atZone(ZoneId.of("Europe/Brussels"))
                        ?.let { SourcedValue(it, source) },
                    officialDelay = event?.takeIf { it.hasDelay() }?.delay
                        ?.let { SourcedValue(Duration.ofSeconds(it.toLong()), source) },
                    observedAt = observedAt,
                    source = source
                )
            }
        }

    /**
     * POSIX seconds outside the civil calendar (zero, negative or past year 9999) are feed garbage,
     * not a time: Instant would accept far larger values whose epoch milliseconds then overflow
     * when the snapshot is assembled, taking the whole refresh down with them.
     */
    private fun epochSecondOrNull(seconds: Long): java.time.Instant? =
        seconds.takeIf { it in 1..MAX_EPOCH_SECOND }?.let(java.time.Instant::ofEpochSecond)

    companion object {
        val SOURCE_ID = SourceId("european-sleeper-gtfs-rt")
        private const val MAX_EPOCH_SECOND = 253_402_300_799L // 9999-12-31T23:59:59Z
    }
}
