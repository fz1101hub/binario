package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.core.AlertId
import it.binario.app.data.core.AlertSeverity
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.LocalizedText
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.ServiceAlert
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
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
import it.binario.app.data.provider.TripUpdateProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

fun interface SncfRealtimeFetcher {
    suspend fun fetch(feed: SncfRealtimeFeed): ByteArray
}

enum class SncfRealtimeFeed(val url: String) {
    SERVICE_ALERTS("https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-service-alerts"),
    TRIP_UPDATES("https://proxy.transport.data.gouv.fr/resource/sncf-gtfs-rt-trip-updates")
}

internal class HttpSncfRealtimeFetcher : SncfRealtimeFetcher {
    override suspend fun fetch(feed: SncfRealtimeFeed): ByteArray = withContext(Dispatchers.IO) {
        val connection = URL(feed.url).openConnection() as? HttpURLConnection
            ?: throw IOException("SNCF realtime non usa una connessione HTTP")
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/x-protobuf, application/octet-stream")
        connection.setRequestProperty("User-Agent", "Binario/0.15 (personal rail information client)")
        try {
            if (connection.responseCode !in 200..299) throw IOException("SNCF realtime ha risposto HTTP ${connection.responseCode}")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

/** Adapter open-data SNCF per gli avvisi GTFS-Realtime; gli aggiornamenti corsa restano disponibili al decoder ma richiedono matching con il GTFS statico. */
class SncfGtfsRealtimeAdapter(
    private val fetcher: SncfRealtimeFetcher = HttpSncfRealtimeFetcher(),
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofMinutes(2)
) : AlertProvider, TripUpdateProvider {
    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "SNCF Open Data — GTFS-Realtime",
        capabilities = setOf(ProviderCapability.SERVICE_ALERTS, ProviderCapability.TRAIN_RUN_REALTIME),
        coverage = CoverageDescriptor(countryCodes = setOf("FR", "IT"), description = "Avvisi e aggiornamenti SNCF, incluse corse internazionali quando presenti nel feed"),
        accessMode = AccessMode.OPEN_DIRECT,
        ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = cacheTtl),
        schemaVersion = 1
    )

    private var cachedAlerts: CachedAlerts? = null
    private var cachedTripUpdates: CachedTripUpdates? = null

    override suspend fun getAlerts(request: AlertRequest): ProviderResult<List<ServiceAlert>> {
        val now = clock.instant()
        cachedAlerts?.takeIf { now.isBefore(it.expiresAt) }?.let { cache ->
            return ProviderResult.Success(filter(cache.alerts, request), meta(now, cache.observedAt, cache.expiresAt, true, cache.fingerprint))
        }
        return try {
            val payload = fetcher.fetch(SncfRealtimeFeed.SERVICE_ALERTS)
            val feed = GtfsRealtime.FeedMessage.parseFrom(payload)
            val observed = feed.header.timestamp.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: now
            val alerts = parseAlerts(feed, observed)
            val expires = now.plus(cacheTtl)
            val fingerprint = fingerprint(payload)
            cachedAlerts = CachedAlerts(alerts, observed, expires, fingerprint)
            ProviderResult.Success(filter(alerts, request), meta(now, observed, expires, false, fingerprint))
        } catch (error: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Realtime SNCF non raggiungibile.", error::class.java.name))
        } catch (error: Exception) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Realtime SNCF non leggibile.", error::class.java.name))
        }
    }

    override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> {
        val now = clock.instant()
        cachedTripUpdates?.takeIf { now.isBefore(it.expiresAt) }?.let { cache ->
            return ProviderResult.Success(cache.updates, meta(now, cache.observedAt, cache.expiresAt, true, cache.fingerprint))
        }
        return try {
            val payload = fetcher.fetch(SncfRealtimeFeed.TRIP_UPDATES)
            val feed = GtfsRealtime.FeedMessage.parseFrom(payload)
            val observed = feed.header.timestamp.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: now
            val updates = parseTripUpdates(feed, observed)
            val expires = now.plus(cacheTtl)
            val fingerprint = fingerprint(payload)
            cachedTripUpdates = CachedTripUpdates(updates, observed, expires, fingerprint)
            ProviderResult.Success(updates, meta(now, observed, expires, false, fingerprint))
        } catch (error: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Aggiornamenti corsa SNCF non raggiungibili.", error::class.java.name))
        } catch (error: Exception) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Aggiornamenti corsa SNCF non leggibili.", error::class.java.name))
        }
    }

    internal fun parseTripUpdates(feed: GtfsRealtime.FeedMessage, observedAt: Instant): List<RealtimeStopUpdate> = feed.entityList.flatMap { entity ->
        if (!entity.hasTripUpdate()) return@flatMap emptyList()
        val update = entity.tripUpdate
        val tripId = update.trip.tripId.takeIf(String::isNotBlank) ?: return@flatMap emptyList()
        val nativeEntity = entity.id.takeIf(String::isNotBlank) ?: tripId
        val source = sourceRef(nativeEntity, observedAt)
        update.stopTimeUpdateList.mapNotNull { stop ->
            val stopId = stop.stopId.takeIf(String::isNotBlank)
            val sequence = stop.stopSequence.takeIf { it > 0 }
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
                observedAt = observedAt,
                source = source
            )
        }
    }

    internal fun parseAlerts(feed: GtfsRealtime.FeedMessage, observedAt: Instant): List<ServiceAlert> = feed.entityList.mapNotNull { entity ->
        if (!entity.hasAlert()) return@mapNotNull null
        val alert = entity.alert
        val id = entity.id.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val title = localized(alert.headerText) ?: LocalizedText("Avviso SNCF", "fr")
        val description = localized(alert.descriptionText)
        val stations = alert.informedEntityList.mapNotNull { it.stopId.takeIf(String::isNotBlank)?.let { stop -> StationId("sncf-gtfs:$stop") } }.toSet()
        val runs = alert.informedEntityList.mapNotNull { it.trip.tripId.takeIf(String::isNotBlank)?.let { trip -> TrainRunId("sncf-gtfs:$trip") } }.toSet()
        val operators = alert.informedEntityList.mapNotNull { it.agencyId.takeIf(String::isNotBlank)?.let(::OperatorId) }.toSet()
        ServiceAlert(
            id = AlertId("sncf:$id"),
            title = title,
            description = description,
            severity = severity(alert.effect),
            validFrom = alert.activePeriodList.firstOrNull()?.start?.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
            validUntil = alert.activePeriodList.firstOrNull()?.end?.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
            affectedStations = stations,
            affectedRuns = runs,
            affectedOperators = operators,
            source = sourceRef(id, observedAt)
        )
    }

    private fun filter(alerts: List<ServiceAlert>, request: AlertRequest): List<ServiceAlert> = alerts.filter { alert ->
        (request.stationIds.isEmpty() || alert.affectedStations.any(request.stationIds::contains)) &&
            (request.operatorIds.isEmpty() || alert.affectedOperators.any(request.operatorIds::contains)) &&
            (request.runIds.isEmpty() || alert.affectedRuns.any(request.runIds::contains))
    }

    private fun localized(value: GtfsRealtime.TranslatedString): LocalizedText? = value.translationList.firstOrNull { it.text.isNotBlank() }?.let {
        LocalizedText(it.text, it.language.takeIf(String::isNotBlank) ?: "fr")
    }

    private fun severity(effect: GtfsRealtime.Alert.Effect): AlertSeverity = when (effect) {
        GtfsRealtime.Alert.Effect.NO_SERVICE, GtfsRealtime.Alert.Effect.STOP_MOVED -> AlertSeverity.SEVERE
        GtfsRealtime.Alert.Effect.REDUCED_SERVICE, GtfsRealtime.Alert.Effect.SIGNIFICANT_DELAYS, GtfsRealtime.Alert.Effect.DETOUR -> AlertSeverity.WARNING
        else -> AlertSeverity.INFO
    }

    private fun meta(fetched: Instant, observed: Instant, expires: Instant, fromCache: Boolean, fingerprint: String) =
        ProviderResponseMeta(SOURCE_ID, fetched, observedAt = observed, expiresAt = expires, fromCache = fromCache, responseFingerprint = fingerprint)

    private fun sourceRef(nativeId: String, observed: Instant) = SourceRef(
        sourceId = SOURCE_ID,
        nativeId = nativeId,
        observedAt = observed,
        fetchedAt = clock.instant(),
        expiresAt = observed.plus(cacheTtl),
        confidence = Confidence.OPEN_DATA,
        licenseId = "Licence Ouverte / Etalab",
        traceId = "sncf:${fingerprint(nativeId.toByteArray()).take(16)}"
    )

    private fun fingerprint(payload: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private data class CachedAlerts(val alerts: List<ServiceAlert>, val observedAt: Instant, val expiresAt: Instant, val fingerprint: String)
    private data class CachedTripUpdates(val updates: List<RealtimeStopUpdate>, val observedAt: Instant, val expiresAt: Instant, val fingerprint: String)

    companion object {
        val SOURCE_ID = SourceId("sncf-gtfs-rt")
    }
}
