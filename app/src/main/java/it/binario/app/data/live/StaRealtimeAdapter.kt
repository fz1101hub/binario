package it.binario.app.data.live

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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

fun interface StaRealtimeFetcher {
    suspend fun fetch(feed: StaRealtimeFeed): String
}

enum class StaRealtimeFeed(val path: String) {
    TRIP_UPDATES("trip-updates")
}

/** HTTPS only, same-host redirects, a byte cap, a total time limit and a request that stops when the caller is cancelled. */
internal class HttpStaRealtimeFetcher(
    private val http: PublicSourceFetcher = HttpPublicSourceFetcher(
        PublicSourceRequestPolicy(maxBytes = 8 * 1024 * 1024, readTimeoutMillis = 15_000, totalTimeoutMillis = 20_000)
    )
) : StaRealtimeFetcher {
    override suspend fun fetch(feed: StaRealtimeFeed): String = http.get("${StaRealtimeAdapter.REALTIME_ROOT}/${feed.path}")
}

/**
 * Adapter ufficiale Open Data Hub/STA per i Trip Updates GTFS-Realtime (ritardi delle corse del pack STA). Gli avvisi e
 * le posizioni del feed non sono letti: nessuna schermata li usava (pulizia dell'audit A1 F13, novembre 2026).
 */
class StaRealtimeAdapter(
    private val fetcher: StaRealtimeFetcher = HttpStaRealtimeFetcher(),
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofSeconds(90)
) : TripUpdateProvider {
    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "Open Data Hub Alto Adige — GTFS-Realtime",
        capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME),
        coverage = CoverageDescriptor(countryCodes = setOf("IT", "AT"), description = "Servizi STA altoatesini e transfrontalieri presenti nel feed"),
        accessMode = AccessMode.OPEN_DIRECT,
        ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = cacheTtl, supportsConditionalRequests = false),
        schemaVersion = 1
    )

    private val tripUpdateMutex = Mutex()

    private var tripUpdateCache: CachedTripUpdates? = null
    private val tripUpdateGuard = RealtimeFeedGuard(clock, "Open Data Hub Alto Adige")

    override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> = tripUpdateMutex.withLock {
        val now = clock.instant()
        tripUpdateCache?.takeIf { now.isBefore(it.expiresAt) }?.let { cached ->
            return@withLock ProviderResult.Success(cached.updates, meta(now, cached.observedAt, cached.expiresAt, fromCache = true))
        }
        tripUpdateGuard.blocked()?.let { return@withLock it }
        try {
            val json = fetcher.fetch(StaRealtimeFeed.TRIP_UPDATES)
            val (observed, updates) = withContext(Dispatchers.Default) { observedAt(json, now).let { it to parseTripUpdates(json, it) } }
            val expiresAt = now.plus(cacheTtl)
            tripUpdateCache = CachedTripUpdates(updates, observed, expiresAt)
            tripUpdateGuard.succeeded()
            ProviderResult.Success(updates, meta(now, observed, expiresAt, fingerprint(json)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            tripUpdateGuard.failed(ProviderError(ProviderErrorKind.NETWORK, "Trip Updates Alto Adige non raggiungibili.", error::class.java.name), error)
        } catch (error: Exception) {
            tripUpdateGuard.failed(ProviderError(ProviderErrorKind.PARSE_ERROR, "Trip Updates Alto Adige non leggibili.", error::class.java.name), error)
        }
    }

    internal fun parseTripUpdates(json: String, fallbackObservedAt: Instant): List<RealtimeStopUpdate> {
        val root = JSONObject(json)
        val observed = observedAt(root, fallbackObservedAt)
        return root.arrayOrEmpty("entity").mapObjects { entity ->
            val update = entity.optJSONObject("trip_update") ?: return@mapObjects null
            val tripId = update.optJSONObject("trip")?.textOr("trip_id").orEmpty()
            if (tripId.isBlank()) return@mapObjects null
            val trip = update.getJSONObject("trip")
            if (trip.textOr("schedule_relationship", "SCHEDULED") !in setOf("SCHEDULED", "0")) return@mapObjects null
            val dateString = trip.textOr("start_date")
            val serviceDate = dateString.takeIf(String::isNotBlank)?.let {
                runCatching { java.time.LocalDate.parse(it, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            }
            if (dateString.isNotBlank() && serviceDate == null) return@mapObjects null
            val nativeEntity = entity.textOr("id", tripId)
            val tripObservedAt = update.optLong("timestamp", 0L).takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: observed
            val source = sourceRef(nativeEntity, tripObservedAt, GtfsRealtimeFreshness.OBSERVATION_WINDOW)
            update.arrayOrEmpty("stop_time_update").mapObjects { stop ->
                if (stop.textOr("schedule_relationship", "SCHEDULED") !in setOf("SCHEDULED", "0")) return@mapObjects null
                val stopId = stop.textOr("stop_id").takeIf(String::isNotBlank)
                val sequence = stop.optInt("stop_sequence", -1).takeIf { it >= 0 }
                if (stopId == null && sequence == null) return@mapObjects null
                val departure = stop.optJSONObject("departure")
                val arrival = stop.optJSONObject("arrival")
                val event = departure ?: arrival
                val delay = event?.optLong("delay", Long.MIN_VALUE)?.takeIf { it != Long.MIN_VALUE }
                val epoch = event?.optLong("time", 0L)?.takeIf { it > 0 }
                val valueSource = source.copy(nativeId = "$nativeEntity:${stopId ?: sequence}")
                RealtimeStopUpdate(
                    runId = TrainRunId("sta:$tripId"),
                    sourceTripId = tripId,
                    sourceStopId = stopId,
                    stopSequence = sequence,
                    predictedAt = epoch?.let { itInstant -> SourcedValue(itInstant.let(Instant::ofEpochSecond).atZone(ZoneId.of("Europe/Rome")), valueSource) },
                    officialDelay = delay?.let { SourcedValue(Duration.ofSeconds(it), valueSource) },
                    observedAt = tripObservedAt,
                    source = source,
                    serviceDate = serviceDate
                )
            }
        }.flatten()
    }

    private fun meta(fetched: Instant, observed: Instant, expires: Instant, fingerprint: String? = null, fromCache: Boolean = false) =
        ProviderResponseMeta(SOURCE_ID, fetched, observedAt = observed, expiresAt = expires, fromCache = fromCache, responseFingerprint = fingerprint)

    private fun observedAt(json: String, fallback: Instant): Instant = observedAt(JSONObject(json), fallback)
    private fun observedAt(root: JSONObject, fallback: Instant): Instant = root.optJSONObject("header")?.optLong("timestamp", 0L)?.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: fallback
    private fun sourceRef(nativeId: String, observed: Instant, validity: Duration = cacheTtl) = SourceRef(SOURCE_ID, nativeId, observed, clock.instant(), observed.plus(validity), Confidence.OPEN_DATA, licenseId = "CC0-1.0", traceId = "sta:${fingerprint(nativeId).take(16)}")
    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun JSONObject.arrayOrEmpty(name: String): JSONArray = optJSONArray(name) ?: JSONArray()
    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T?): List<T> = buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(transform)?.let(::add)
        }
    }

    private data class CachedTripUpdates(val updates: List<RealtimeStopUpdate>, val observedAt: Instant, val expiresAt: Instant)

    companion object {
        val SOURCE_ID = SourceId("alto-adige-sta-realtime")
        const val REALTIME_ROOT = "https://gtfs.api.opendatahub.com/v1/realtime/sta-time-tables"
    }
}
