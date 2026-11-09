package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.GtfsRealtimeFreshness
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.RealtimeStopUpdate
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import it.binario.app.data.urban.HttpsBytesFetcher
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

fun interface SncfRealtimeFetcher {
    suspend fun fetch(feed: SncfRealtimeFeed): ByteArray
}

enum class SncfRealtimeFeed(val url: String) {
    TRIP_UPDATES("https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates")
}

/**
 * The national feeds through [HttpsBytesFetcher]: HTTPS on the same host, a byte cap (the national Trip Updates are a
 * few MB), a total time limit and a request that stops when the caller is cancelled.
 */
internal class HttpSncfRealtimeFetcher(
    private val http: HttpsBytesFetcher = HttpsBytesFetcher(maxBytes = 16 * 1024 * 1024, readTimeoutMillis = 15_000, totalTimeoutMillis = 25_000)
) : SncfRealtimeFetcher {
    override suspend fun fetch(feed: SncfRealtimeFeed): ByteArray = http.fetch(feed.url)
}

/**
 * Adapter open-data SNCF per i Trip Updates GTFS-Realtime, abbinati al GTFS statico del pack SNCF. Gli avvisi (Service
 * Alerts) non sono letti: nessuna schermata li usava (pulizia dell'audit A1 F13, novembre 2026).
 */
class SncfGtfsRealtimeAdapter(
    private val fetcher: SncfRealtimeFetcher = HttpSncfRealtimeFetcher(),
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofMinutes(2)
) : TripUpdateProvider {
    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "SNCF Open Data — GTFS-Realtime",
        capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME),
        coverage = CoverageDescriptor(countryCodes = setOf("FR", "IT"), description = "Aggiornamenti corsa SNCF, incluse corse internazionali quando presenti nel feed"),
        accessMode = AccessMode.OPEN_DIRECT,
        ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = cacheTtl),
        schemaVersion = 1
    )

    private val tripUpdatesMutex = Mutex()

    private var cachedTripUpdates: CachedTripUpdates? = null
    private val tripUpdatesGuard = RealtimeFeedGuard(clock, "SNCF Open Data")

    override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> = tripUpdatesMutex.withLock {
        val now = clock.instant()
        cachedTripUpdates?.takeIf { now.isBefore(it.expiresAt) }?.let { cache ->
            return@withLock ProviderResult.Success(cache.updates, meta(now, cache.observedAt, cache.expiresAt, true, cache.fingerprint))
        }
        tripUpdatesGuard.blocked()?.let { return@withLock it }
        try {
            val payload = fetcher.fetch(SncfRealtimeFeed.TRIP_UPDATES)
            // The national feed is decoded off the caller's thread.
            val (observed, updates) = withContext(Dispatchers.Default) {
                val feed = GtfsRealtime.FeedMessage.parseFrom(payload)
                val observed = feed.header.timestamp.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: now
                observed to parseTripUpdates(feed, observed)
            }
            val expires = now.plus(cacheTtl)
            val fingerprint = fingerprint(payload)
            cachedTripUpdates = CachedTripUpdates(updates, observed, expires, fingerprint)
            tripUpdatesGuard.succeeded()
            ProviderResult.Success(updates, meta(now, observed, expires, false, fingerprint))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            tripUpdatesGuard.failed(ProviderError(ProviderErrorKind.NETWORK, "Aggiornamenti corsa SNCF non raggiungibili.", error::class.java.name), error)
        } catch (error: Exception) {
            tripUpdatesGuard.failed(ProviderError(ProviderErrorKind.PARSE_ERROR, "Aggiornamenti corsa SNCF non leggibili.", error::class.java.name), error)
        }
    }

    internal fun parseTripUpdates(feed: GtfsRealtime.FeedMessage, observedAt: Instant): List<RealtimeStopUpdate> = feed.entityList.flatMap { entity ->
        if (!entity.hasTripUpdate()) return@flatMap emptyList()
        val update = entity.tripUpdate
        val tripId = update.trip.tripId.takeIf(String::isNotBlank) ?: return@flatMap emptyList()
        if (update.trip.scheduleRelationship != GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED) return@flatMap emptyList()
        val dateString = update.trip.startDate
        val serviceDate = dateString.takeIf(String::isNotBlank)?.let {
            runCatching { java.time.LocalDate.parse(it, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        }
        if (dateString.isNotBlank() && serviceDate == null) return@flatMap emptyList()
        val nativeEntity = entity.id.takeIf(String::isNotBlank) ?: tripId
        val tripObservedAt = update.timestamp.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: observedAt
        val source = sourceRef(nativeEntity, tripObservedAt, GtfsRealtimeFreshness.OBSERVATION_WINDOW)
        update.stopTimeUpdateList.mapNotNull { stop ->
            if (stop.scheduleRelationship != GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SCHEDULED) return@mapNotNull null
            val stopId = stop.stopId.takeIf(String::isNotBlank)
            val sequence = stop.stopSequence.takeIf { stop.hasStopSequence() }
            if (stopId == null && sequence == null) return@mapNotNull null
            val event = when {
                stop.hasDeparture() -> stop.departure
                stop.hasArrival() -> stop.arrival
                else -> null
            }
            val valueSource = source.copy(nativeId = "$nativeEntity:${stopId ?: sequence}")
            val delay = event?.takeIf { it.hasDelay() }?.delay
            val epoch = event?.takeIf { it.hasTime() }?.time
            RealtimeStopUpdate(
                runId = TrainRunId("sncf-gtfs:$tripId"),
                sourceTripId = tripId,
                sourceStopId = stopId,
                stopSequence = sequence,
                predictedAt = epoch?.let { Instant.ofEpochSecond(it).atZone(ZoneId.of("Europe/Paris")) }?.let { SourcedValue(it, valueSource) },
                officialDelay = delay?.let { SourcedValue(Duration.ofSeconds(it.toLong()), valueSource) },
                observedAt = tripObservedAt,
                source = source,
                serviceDate = serviceDate
            )
        }
    }

    private fun meta(fetched: Instant, observed: Instant, expires: Instant, fromCache: Boolean, fingerprint: String) =
        ProviderResponseMeta(SOURCE_ID, fetched, observedAt = observed, expiresAt = expires, fromCache = fromCache, responseFingerprint = fingerprint)

    private fun sourceRef(nativeId: String, observed: Instant, validity: Duration = cacheTtl) = SourceRef(
        sourceId = SOURCE_ID,
        nativeId = nativeId,
        observedAt = observed,
        fetchedAt = clock.instant(),
        expiresAt = observed.plus(validity),
        confidence = Confidence.OPEN_DATA,
        licenseId = "Licence Ouverte / Etalab",
        traceId = "sncf:${fingerprint(nativeId.toByteArray()).take(16)}"
    )

    private fun fingerprint(payload: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private data class CachedTripUpdates(val updates: List<RealtimeStopUpdate>, val observedAt: Instant, val expiresAt: Instant, val fingerprint: String)

    companion object {
        val SOURCE_ID = SourceId("sncf-gtfs-rt")
    }
}
