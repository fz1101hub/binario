package it.binario.app.data.live

import it.binario.app.data.core.AlertId
import it.binario.app.data.core.AlertSeverity
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.GeoPoint
import it.binario.app.data.core.LocalizedText
import it.binario.app.data.core.PositionKind
import it.binario.app.data.core.ServiceAlert
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.core.VehiclePosition
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.AlertRequest
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.VehiclePositionProvider
import it.binario.app.data.provider.TripUpdateProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

fun interface StaRealtimeFetcher {
    suspend fun fetch(feed: StaRealtimeFeed): String
}

enum class StaRealtimeFeed(val path: String) {
    TRIP_UPDATES("trip-updates"),
    VEHICLE_POSITIONS("vehicle-positions"),
    SERVICE_ALERTS("service-alerts")
}

internal class HttpStaRealtimeFetcher : StaRealtimeFetcher {
    override suspend fun fetch(feed: StaRealtimeFeed): String = withContext(Dispatchers.IO) {
        val url = URL("${StaRealtimeAdapter.REALTIME_ROOT}/${feed.path}")
        val connection = url.openConnection() as? HttpURLConnection
            ?: throw IOException("STA realtime non usa una connessione HTTP")
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Binario/0.15 (personal rail information client)")
        try {
            if (connection.responseCode !in 200..299) throw IOException("STA realtime ha risposto HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/** Adapter ufficiale Open Data Hub/STA per alert e posizioni GTFS-Realtime. */
class StaRealtimeAdapter(
    private val fetcher: StaRealtimeFetcher = HttpStaRealtimeFetcher(),
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofSeconds(90)
) : AlertProvider, VehiclePositionProvider, TripUpdateProvider {
    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "Open Data Hub Alto Adige — GTFS-Realtime",
        capabilities = setOf(ProviderCapability.SERVICE_ALERTS, ProviderCapability.VEHICLE_POSITIONS, ProviderCapability.TRAIN_RUN_REALTIME),
        coverage = CoverageDescriptor(countryCodes = setOf("IT", "AT"), description = "Servizi STA altoatesini e transfrontalieri presenti nel feed"),
        accessMode = AccessMode.OPEN_DIRECT,
        ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = cacheTtl, supportsConditionalRequests = false),
        schemaVersion = 1
    )

    private var alertCache: CachedAlerts? = null
    private var positionCache: CachedPositions? = null
    private var tripUpdateCache: CachedTripUpdates? = null

    override suspend fun getAlerts(request: AlertRequest): ProviderResult<List<ServiceAlert>> {
        val now = clock.instant()
        alertCache?.takeIf { now.isBefore(it.expiresAt) }?.let { cached ->
            return ProviderResult.Success(cached.alerts, meta(now, cached.observedAt, cached.expiresAt, fromCache = true))
        }
        return try {
            val json = fetcher.fetch(StaRealtimeFeed.SERVICE_ALERTS)
            val parsed = parseAlerts(json, now)
            val filtered = if (request.stationIds.isEmpty()) parsed else parsed.filter { alert -> alert.affectedStations.any(request.stationIds::contains) }
            val expiresAt = now.plus(cacheTtl)
            alertCache = CachedAlerts(parsed, observedAt(json, now), expiresAt)
            ProviderResult.Success(filtered, meta(now, observedAt(json, now), expiresAt, fingerprint(json)))
        } catch (error: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Realtime Alto Adige non raggiungibile.", error::class.java.name))
        } catch (error: Exception) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Realtime Alto Adige non leggibile.", error::class.java.name))
        }
    }

    override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> {
        val now = clock.instant()
        tripUpdateCache?.takeIf { now.isBefore(it.expiresAt) }?.let { cached ->
            return ProviderResult.Success(cached.updates, meta(now, cached.observedAt, cached.expiresAt, fromCache = true))
        }
        return try {
            val json = fetcher.fetch(StaRealtimeFeed.TRIP_UPDATES)
            val observed = observedAt(json, now)
            val updates = parseTripUpdates(json, observed)
            val expiresAt = now.plus(cacheTtl)
            tripUpdateCache = CachedTripUpdates(updates, observed, expiresAt)
            ProviderResult.Success(updates, meta(now, observed, expiresAt, fingerprint(json)))
        } catch (error: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Trip Updates Alto Adige non raggiungibili.", error::class.java.name))
        } catch (error: Exception) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Trip Updates Alto Adige non leggibili.", error::class.java.name))
        }
    }

    override suspend fun getVehiclePositions(): ProviderResult<List<VehiclePosition>> {
        val now = clock.instant()
        positionCache?.takeIf { now.isBefore(it.expiresAt) }?.let { cached ->
            return ProviderResult.Success(cached.positions, meta(now, cached.observedAt, cached.expiresAt, fromCache = true))
        }
        return try {
            val json = fetcher.fetch(StaRealtimeFeed.VEHICLE_POSITIONS)
            val observed = observedAt(json, now)
            val positions = parsePositions(json, observed)
            val expiresAt = now.plus(cacheTtl)
            positionCache = CachedPositions(positions, observed, expiresAt)
            ProviderResult.Success(positions, meta(now, observed, expiresAt, fingerprint(json)))
        } catch (error: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Posizioni Alto Adige non raggiungibili.", error::class.java.name))
        } catch (error: Exception) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Posizioni Alto Adige non leggibili.", error::class.java.name))
        }
    }

    internal fun parseAlerts(json: String, fetchedAt: Instant): List<ServiceAlert> {
        val root = JSONObject(json)
        val observed = observedAt(root, fetchedAt)
        return root.arrayOrEmpty("entity").mapObjects { entity ->
            val alert = entity.optJSONObject("alert") ?: return@mapObjects null
            val id = entity.optString("id").takeIf(String::isNotBlank) ?: return@mapObjects null
            val title = translations(alert.optJSONObject("header_text")).firstOrNull { it.languageTag == "it" }
                ?: translations(alert.optJSONObject("header_text")).firstOrNull()
                ?: LocalizedText("Avviso di circolazione")
            val description = translations(alert.optJSONObject("description_text")).firstOrNull { it.languageTag == "it" }
                ?: translations(alert.optJSONObject("description_text")).firstOrNull()
            val stations = alert.arrayOrEmpty("informed_entity").mapObjects { informed ->
                informed.optString("stop_id").takeIf(String::isNotBlank)?.let { StationId("sta:$it") }
            }.toSet()
            ServiceAlert(
                id = AlertId("sta:$id"),
                title = title,
                description = description,
                severity = severity(alert.optString("effect"), alert.optString("severity_level")),
                validFrom = activeBoundary(alert, "start"),
                validUntil = activeBoundary(alert, "end"),
                affectedStations = stations,
                source = sourceRef(id, observed)
            )
        }
    }

    internal fun parseTripUpdates(json: String, fallbackObservedAt: Instant): List<RealtimeStopUpdate> {
        val root = JSONObject(json)
        val observed = observedAt(root, fallbackObservedAt)
        return root.arrayOrEmpty("entity").mapObjects { entity ->
            val update = entity.optJSONObject("trip_update") ?: return@mapObjects null
            val tripId = update.optJSONObject("trip")?.optString("trip_id").orEmpty()
            if (tripId.isBlank()) return@mapObjects null
            val trip = update.getJSONObject("trip")
            if (trip.optString("schedule_relationship", "SCHEDULED") !in setOf("SCHEDULED", "0")) return@mapObjects null
            val dateString = trip.optString("start_date")
            val serviceDate = dateString.takeIf(String::isNotBlank)?.let {
                runCatching { java.time.LocalDate.parse(it, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            }
            if (dateString.isNotBlank() && serviceDate == null) return@mapObjects null
            val nativeEntity = entity.optString("id", tripId)
            val tripObservedAt = update.optLong("timestamp", 0L).takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: observed
            val source = sourceRef(nativeEntity, tripObservedAt)
            update.arrayOrEmpty("stop_time_update").mapObjects { stop ->
                if (stop.optString("schedule_relationship", "SCHEDULED") !in setOf("SCHEDULED", "0")) return@mapObjects null
                val stopId = stop.optString("stop_id").takeIf(String::isNotBlank)
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

    internal fun parsePositions(json: String, fallbackObservedAt: Instant): List<VehiclePosition> {
        val root = JSONObject(json)
        return root.arrayOrEmpty("entity").mapObjects { entity ->
            val vehicle = entity.optJSONObject("vehicle") ?: return@mapObjects null
            val position = vehicle.optJSONObject("position") ?: return@mapObjects null
            val tripId = vehicle.optJSONObject("trip")?.optString("trip_id").orEmpty()
            val latitude = position.optDouble("latitude", Double.NaN)
            val longitude = position.optDouble("longitude", Double.NaN)
            if (tripId.isBlank() || !latitude.isFinite() || !longitude.isFinite()) return@mapObjects null
            val observed = vehicle.optLong("timestamp", 0L).takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: fallbackObservedAt
            VehiclePosition(
                runId = TrainRunId("sta:$tripId"),
                point = GeoPoint(latitude, longitude),
                observedAt = observed,
                kind = PositionKind.GPS,
                source = sourceRef(entity.optString("id", tripId), observed)
            )
        }
    }

    private fun meta(fetched: Instant, observed: Instant, expires: Instant, fingerprint: String? = null, fromCache: Boolean = false) =
        ProviderResponseMeta(SOURCE_ID, fetched, observedAt = observed, expiresAt = expires, fromCache = fromCache, responseFingerprint = fingerprint)

    private fun observedAt(json: String, fallback: Instant): Instant = observedAt(JSONObject(json), fallback)
    private fun observedAt(root: JSONObject, fallback: Instant): Instant = root.optJSONObject("header")?.optLong("timestamp", 0L)?.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: fallback
    private fun activeBoundary(alert: JSONObject, boundary: String): Instant? = alert.arrayOrEmpty("active_period").optJSONObject(0)?.optLong(boundary, 0L)?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)
    private fun severity(effect: String, level: String): AlertSeverity = when {
        effect in setOf("NO_SERVICE", "STOP_MOVED") -> AlertSeverity.SEVERE
        level.equals("WARNING", ignoreCase = true) || effect.isNotBlank() -> AlertSeverity.WARNING
        else -> AlertSeverity.INFO
    }
    private fun sourceRef(nativeId: String, observed: Instant) = SourceRef(SOURCE_ID, nativeId, observed, clock.instant(), observed.plus(cacheTtl), Confidence.OPEN_DATA, licenseId = "CC0-1.0", traceId = "sta:${fingerprint(nativeId).take(16)}")
    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun translations(value: JSONObject?): List<LocalizedText> = value?.arrayOrEmpty("translation")?.mapObjects { translation ->
        translation.optString("text").takeIf(String::isNotBlank)?.let { LocalizedText(it, translation.optString("language", "it")) }
    }.orEmpty()
    private fun JSONObject.arrayOrEmpty(name: String): JSONArray = optJSONArray(name) ?: JSONArray()
    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T?): List<T> = buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(transform)?.let(::add)
        }
    }

    private data class CachedAlerts(val alerts: List<ServiceAlert>, val observedAt: Instant, val expiresAt: Instant)
    private data class CachedPositions(val positions: List<VehiclePosition>, val observedAt: Instant, val expiresAt: Instant)
    private data class CachedTripUpdates(val updates: List<RealtimeStopUpdate>, val observedAt: Instant, val expiresAt: Instant)

    companion object {
        val SOURCE_ID = SourceId("alto-adige-sta-realtime")
        const val REALTIME_ROOT = "https://gtfs.api.opendatahub.com/v1/realtime/sta-time-tables"
    }
}
