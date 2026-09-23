package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.core.AlertId
import it.binario.app.data.core.AlertSeverity
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.LocalizedText
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.ServiceAlert
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
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
import java.io.IOException
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SwissRealtimeFeed(val url: String) {
    TRIP_UPDATES("https://api.opentransportdata.swiss/la/gtfs-rt"),
    SERVICE_ALERTS("https://api.opentransportdata.swiss/la/gtfs-sa")
}

fun interface SwissRealtimeFetcher {
    suspend fun fetch(feed: SwissRealtimeFeed): ByteArray
}

/**
 * HTTP transport deliberately requires a token supplied by the caller. The
 * token is never read from BuildConfig/resources and therefore cannot be
 * accidentally embedded in a release APK.
 */
class AuthorizedSwissRealtimeFetcher(
    private val bearerToken: String
) : SwissRealtimeFetcher {
    init {
        require(bearerToken.isNotBlank()) { "API key svizzera mancante" }
    }

    override suspend fun fetch(feed: SwissRealtimeFeed): ByteArray = withContext(Dispatchers.IO) {
        val connection = URL(feed.url).openConnection() as? HttpURLConnection
            ?: throw IOException("Open Data Platform Mobility Switzerland non usa una connessione HTTP")
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 25_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        connection.setRequestProperty("User-Agent", "Binario/0.20 (authorized Swiss GTFS-RT client)")
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("OpenTransportData CH ha risposto HTTP $status")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Decoder GTFS-RT per SBB/TILO/BLS/RhB/FART/SSIF e gli altri operatori
 * presenti nel feed nazionale svizzero. Non viene istanziato dalla policy
 * locale finché non è disponibile una API key autorizzata.
 */
class SwissGtfsRealtimeAdapter(
    private val fetcher: SwissRealtimeFetcher,
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofSeconds(30)
) : TripUpdateProvider, AlertProvider {
    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "Open Data Platform Mobility Switzerland — GTFS-Realtime",
        capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME, ProviderCapability.SERVICE_ALERTS),
        coverage = CoverageDescriptor(
            countryCodes = setOf("CH", "IT"),
            description = "SBB, TILO, BLS, RhB, FART/SSIF e servizi transfrontalieri quando presenti nel feed"
        ),
        accessMode = AccessMode.AGREEMENT_REQUIRED,
        ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = cacheTtl),
        schemaVersion = 1
    )

    private var cachedTrips: CachedTrips? = null
    private var cachedAlerts: CachedAlerts? = null

    override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> {
        val now = clock.instant()
        cachedTrips?.takeIf { now.isBefore(it.expiresAt) }?.let { cache ->
            return ProviderResult.Success(cache.items, meta(now, cache.observedAt, cache.expiresAt, true, cache.fingerprint))
        }
        return try {
            val payload = decodeContentEncoding(fetcher.fetch(SwissRealtimeFeed.TRIP_UPDATES))
            val feed = GtfsRealtime.FeedMessage.parseFrom(payload)
            val observed = feed.header.timestamp.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: now
            val items = parseTripUpdates(feed, observed)
            val expires = now.plus(cacheTtl)
            val fingerprint = fingerprint(payload)
            cachedTrips = CachedTrips(items, observed, expires, fingerprint)
            ProviderResult.Success(items, meta(now, observed, expires, false, fingerprint))
        } catch (error: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Realtime svizzero non raggiungibile.", error::class.java.name))
        } catch (error: Exception) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Realtime svizzero non leggibile.", error::class.java.name))
        }
    }

    override suspend fun getAlerts(request: AlertRequest): ProviderResult<List<ServiceAlert>> {
        val now = clock.instant()
        cachedAlerts?.takeIf { now.isBefore(it.expiresAt) }?.let { cache ->
            return ProviderResult.Success(filter(cache.items, request), meta(now, cache.observedAt, cache.expiresAt, true, cache.fingerprint))
        }
        return try {
            val payload = decodeContentEncoding(fetcher.fetch(SwissRealtimeFeed.SERVICE_ALERTS))
            val feed = GtfsRealtime.FeedMessage.parseFrom(payload)
            val observed = feed.header.timestamp.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: now
            val items = parseAlerts(feed, observed)
            val expires = now.plus(cacheTtl)
            val fingerprint = fingerprint(payload)
            cachedAlerts = CachedAlerts(items, observed, expires, fingerprint)
            ProviderResult.Success(filter(items, request), meta(now, observed, expires, false, fingerprint))
        } catch (error: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NETWORK, "Avvisi svizzeri non raggiungibili.", error::class.java.name))
        } catch (error: Exception) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.PARSE_ERROR, "Avvisi svizzeri non leggibili.", error::class.java.name))
        }
    }

    internal fun parseTripUpdates(
        feed: GtfsRealtime.FeedMessage,
        observedAt: Instant
    ): List<RealtimeStopUpdate> = feed.entityList.flatMap { entity ->
        if (!entity.hasTripUpdate()) return@flatMap emptyList()
        val update = entity.tripUpdate
        val tripId = update.trip.tripId.takeIf(String::isNotBlank) ?: return@flatMap emptyList()
        val entityId = entity.id.takeIf(String::isNotBlank) ?: tripId
        val source = sourceRef(entityId, observedAt)
        update.stopTimeUpdateList.mapNotNull { stop ->
            val stopId = stop.stopId.takeIf(String::isNotBlank)
            val sequence = stop.stopSequence.takeIf { stop.hasStopSequence() }
            if (stopId == null && sequence == null) return@mapNotNull null
            val event = when {
                stop.hasDeparture() -> stop.departure
                stop.hasArrival() -> stop.arrival
                else -> null
            }
            val scopedSource = source.copy(nativeId = "$entityId:${stopId ?: sequence}")
            val delay = event?.takeIf { it.hasDelay() }?.delay
            val epoch = event?.takeIf { it.hasTime() }?.time
            RealtimeStopUpdate(
                runId = TrainRunId("ch-gtfs:$tripId"),
                sourceTripId = tripId,
                sourceStopId = stopId,
                stopSequence = sequence,
                predictedAt = epoch?.let { Instant.ofEpochSecond(it).atZone(ZoneId.of("Europe/Zurich")) }
                    ?.let { SourcedValue(it, scopedSource) },
                officialDelay = delay?.let { SourcedValue(Duration.ofSeconds(it.toLong()), scopedSource) },
                observedAt = observedAt,
                source = source
            )
        }
    }

    internal fun parseAlerts(
        feed: GtfsRealtime.FeedMessage,
        observedAt: Instant
    ): List<ServiceAlert> = feed.entityList.mapNotNull { entity ->
        if (!entity.hasAlert()) return@mapNotNull null
        val alert = entity.alert
        val id = entity.id.takeIf(String::isNotBlank) ?: return@mapNotNull null
        ServiceAlert(
            id = AlertId("ch:$id"),
            title = localized(alert.headerText) ?: LocalizedText("Avviso rete svizzera", "it"),
            description = localized(alert.descriptionText),
            severity = severity(alert.effect),
            validFrom = alert.activePeriodList.firstOrNull()?.start?.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
            validUntil = alert.activePeriodList.firstOrNull()?.end?.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
            affectedStations = alert.informedEntityList.mapNotNull {
                it.stopId.takeIf(String::isNotBlank)?.let { stop -> StationId("ch-gtfs:$stop") }
            }.toSet(),
            affectedRuns = alert.informedEntityList.mapNotNull {
                it.trip.tripId.takeIf(String::isNotBlank)?.let { trip -> TrainRunId("ch-gtfs:$trip") }
            }.toSet(),
            affectedOperators = alert.informedEntityList.mapNotNull {
                it.agencyId.takeIf(String::isNotBlank)?.let(::OperatorId)
            }.toSet(),
            source = sourceRef(id, observedAt)
        )
    }

    private fun localized(value: GtfsRealtime.TranslatedString): LocalizedText? =
        value.translationList.firstOrNull { it.text.isNotBlank() }?.let {
            LocalizedText(it.text, it.language.takeIf(String::isNotBlank) ?: "de")
        }

    private fun severity(effect: GtfsRealtime.Alert.Effect): AlertSeverity = when (effect) {
        GtfsRealtime.Alert.Effect.NO_SERVICE,
        GtfsRealtime.Alert.Effect.STOP_MOVED -> AlertSeverity.SEVERE
        GtfsRealtime.Alert.Effect.REDUCED_SERVICE,
        GtfsRealtime.Alert.Effect.SIGNIFICANT_DELAYS,
        GtfsRealtime.Alert.Effect.DETOUR -> AlertSeverity.WARNING
        else -> AlertSeverity.INFO
    }

    private fun filter(items: List<ServiceAlert>, request: AlertRequest): List<ServiceAlert> = items.filter { alert ->
        (request.stationIds.isEmpty() || alert.affectedStations.any(request.stationIds::contains)) &&
            (request.operatorIds.isEmpty() || alert.affectedOperators.any(request.operatorIds::contains)) &&
            (request.runIds.isEmpty() || alert.affectedRuns.any(request.runIds::contains))
    }

    private fun sourceRef(nativeId: String, observedAt: Instant) = SourceRef(
        sourceId = SOURCE_ID,
        nativeId = nativeId,
        observedAt = observedAt,
        fetchedAt = clock.instant(),
        expiresAt = observedAt.plus(cacheTtl),
        confidence = Confidence.OPEN_DATA,
        licenseId = "opentransportdata.swiss",
        traceId = "ch:${fingerprint(nativeId.toByteArray()).take(16)}"
    )

    private fun meta(fetched: Instant, observed: Instant, expires: Instant, fromCache: Boolean, fingerprint: String) =
        ProviderResponseMeta(SOURCE_ID, fetched, observedAt = observed, expiresAt = expires, fromCache = fromCache, responseFingerprint = fingerprint)

    private fun fingerprint(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { "%02x".format(it) }

    /** Fetchers expose raw bytes; recognize gzip magic because test and gateway transports may hide HTTP headers. */
    private fun decodeContentEncoding(payload: ByteArray): ByteArray =
        if (payload.size >= 2 && payload[0] == GZIP_MAGIC_1 && payload[1] == GZIP_MAGIC_2) {
            GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
        } else {
            payload
        }

    private data class CachedTrips(
        val items: List<RealtimeStopUpdate>,
        val observedAt: Instant,
        val expiresAt: Instant,
        val fingerprint: String
    )

    private data class CachedAlerts(
        val items: List<ServiceAlert>,
        val observedAt: Instant,
        val expiresAt: Instant,
        val fingerprint: String
    )

    companion object {
        val SOURCE_ID = SourceId("ch-otd-gtfs-rt")
        private const val GZIP_MAGIC_1: Byte = 0x1f
        private const val GZIP_MAGIC_2: Byte = 0x8b.toByte()
    }
}
