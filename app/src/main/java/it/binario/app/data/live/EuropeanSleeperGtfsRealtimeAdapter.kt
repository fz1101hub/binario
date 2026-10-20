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
import java.net.HttpURLConnection
import java.net.URL
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface EuropeanSleeperRealtimeFetcher {
    suspend fun fetch(): ByteArray
}

internal class HttpEuropeanSleeperRealtimeFetcher : EuropeanSleeperRealtimeFetcher {
    override suspend fun fetch(): ByteArray = withContext(Dispatchers.IO) {
        val connection = URL(FEED_URL).openConnection() as? HttpURLConnection
            ?: throw IOException("European Sleeper realtime non usa HTTP")
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/x-protobuf, application/octet-stream")
        connection.setRequestProperty("User-Agent", "Binario/0.20 personal best-effort rail client")
        try {
            if (connection.responseCode !in 200..299) throw IOException("European Sleeper realtime HTTP ${connection.responseCode}")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

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

    override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> {
        val now = clock.instant()
        if (now.atZone(ZoneId.of("Europe/Rome")).toLocalDate() < launchDate) {
            return ProviderResult.Success(emptyList(), ProviderResponseMeta(SOURCE_ID, now, observedAt = now, expiresAt = now.plus(ttl)))
        }
        return try {
            val feed = GtfsRealtime.FeedMessage.parseFrom(fetcher.fetch())
            val observed = feed.header.timestamp.takeIf { it > 0 }?.let(java.time.Instant::ofEpochSecond) ?: now
            ProviderResult.Success(
                parse(feed, observed),
                ProviderResponseMeta(SOURCE_ID, now, observedAt = observed, expiresAt = now.plus(ttl))
            )
        } catch (error: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Realtime European Sleeper non raggiungibile.", error::class.java.name))
        } catch (error: Exception) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Realtime European Sleeper non leggibile.", error::class.java.name))
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
                        ?.let(java.time.Instant::ofEpochSecond)
                        ?.atZone(ZoneId.of("Europe/Brussels"))
                        ?.let { SourcedValue(it, source) },
                    officialDelay = event?.takeIf { it.hasDelay() }?.delay
                        ?.let { SourcedValue(Duration.ofSeconds(it.toLong()), source) },
                    observedAt = observedAt,
                    source = source
                )
            }
        }

    companion object {
        val SOURCE_ID = SourceId("european-sleeper-gtfs-rt")
    }
}
