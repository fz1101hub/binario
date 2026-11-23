package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.GtfsRealtimeFreshness
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
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
import it.binario.app.data.urban.HttpsBytesFetcher
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface TrenitaliaFranceRealtimeFetcher {
    suspend fun fetch(): ByteArray
}

/**
 * The Trenitalia France Trip Updates through [HttpsBytesFetcher], as the SNCF feed of the same host: HTTPS, a redirect
 * only to the same host, the app's own User-Agent, a total time limit and a byte cap (the whole feed is about 10 KB for
 * some fifty trains, November 2026).
 */
internal class HttpTrenitaliaFranceRealtimeFetcher(
    private val http: HttpsBytesFetcher = HttpsBytesFetcher(maxBytes = 1024 * 1024, readTimeoutMillis = 15_000, totalTimeoutMillis = 25_000)
) : TrenitaliaFranceRealtimeFetcher {
    override suspend fun fetch(): ByteArray = http.fetch(FEED_URL)

    companion object {
        const val FEED_URL = "https://proxy.transport.data.gouv.fr/resource/trenitalia-gtfs-rt"
    }
}

/**
 * Trip Updates of Trenitalia France (Frecciarossa Parigi–Lione, Parigi–Marsiglia, Parigi–Milano; open data, Licence
 * Ouverte 2.0), joined by trip_id to the `trenitalia-france-gtfs` pack only. The pack is a candidate until it is
 * bundled: without it no search has a service of that pack, so this feed is never asked (and the registry keeps the
 * provider off, see KnownProviders). A refusal of the host pauses it at once, together with the SNCF feed
 * ([TransportDataGouvRequests]); other failures are remembered by [RealtimeFeedGuard] as for SNCF.
 */
class TrenitaliaFranceGtfsRealtimeAdapter(
    private val fetcher: TrenitaliaFranceRealtimeFetcher = HttpTrenitaliaFranceRealtimeFetcher(),
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofMinutes(2),
    requests: TransportDataGouvRequests = TransportDataGouvRequests(clock)
) : TripUpdateProvider {
    override val descriptor = DESCRIPTOR

    private val refusals = requests.refusals
    private val mutex = Mutex()
    private var cached: Cached? = null
    private val guard = RealtimeFeedGuard(clock, "Trenitalia France")

    override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> = mutex.withLock {
        val now = clock.instant()
        cached?.takeIf { now.isBefore(it.expiresAt) }?.let { cache ->
            return@withLock ProviderResult.Success(cache.updates, meta(now, cache.observedAt, cache.expiresAt, true, cache.fingerprint))
        }
        guard.blocked()?.let { return@withLock it }
        try {
            val payload = refusals.call { fetcher.fetch() }
            val (observed, updates) = withContext(Dispatchers.Default) {
                val feed = GtfsRealtime.FeedMessage.parseFrom(payload)
                val observed = feed.header.timestamp.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: now
                observed to parseTripUpdates(feed, observed)
            }
            val expires = now.plus(cacheTtl)
            val fingerprint = fingerprint(payload)
            cached = Cached(updates, observed, expires, fingerprint)
            guard.succeeded()
            ProviderResult.Success(updates, meta(now, observed, expires, false, fingerprint))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            guard.failed(ProviderError(ProviderErrorKind.NETWORK, "Aggiornamenti corsa Trenitalia France non raggiungibili.", error::class.java.name), error)
        } catch (error: Exception) {
            guard.failed(ProviderError(ProviderErrorKind.PARSE_ERROR, "Aggiornamenti corsa Trenitalia France non leggibili.", error::class.java.name), error)
        }
    }

    internal fun parseTripUpdates(feed: GtfsRealtime.FeedMessage, observedAt: Instant): List<RealtimeStopUpdate> =
        gtfsRealtimeStopUpdates(feed, observedAt, STATIC_PACK.value) { nativeId, observed -> sourceRef(nativeId, observed) }

    private fun meta(fetched: Instant, observed: Instant, expires: Instant, fromCache: Boolean, fingerprint: String) =
        ProviderResponseMeta(SOURCE_ID, fetched, observedAt = observed, expiresAt = expires, fromCache = fromCache, responseFingerprint = fingerprint)

    private fun sourceRef(nativeId: String, observed: Instant) = SourceRef(
        sourceId = SOURCE_ID,
        nativeId = nativeId,
        observedAt = observed,
        fetchedAt = clock.instant(),
        expiresAt = observed.plus(GtfsRealtimeFreshness.OBSERVATION_WINDOW),
        confidence = Confidence.OPEN_DATA,
        licenseId = "Licence Ouverte / Etalab 2.0",
        traceId = "trenitalia-france:${fingerprint(nativeId.toByteArray()).take(16)}"
    )

    private fun fingerprint(payload: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private data class Cached(val updates: List<RealtimeStopUpdate>, val observedAt: Instant, val expiresAt: Instant, val fingerprint: String)

    companion object {
        val SOURCE_ID = SourceId("trenitalia-france-gtfs-rt")
        /** The only pack its trip_ids belong to. */
        val STATIC_PACK = SourceId("trenitalia-france-gtfs")
        val DESCRIPTOR = ProviderDescriptor(
            id = SOURCE_ID,
            displayName = "Trenitalia France — GTFS-Realtime",
            capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME),
            coverage = CoverageDescriptor(countryCodes = setOf("FR", "IT"), description = "Aggiornamenti corsa dei Frecciarossa Trenitalia France del pack omonimo"),
            accessMode = AccessMode.OPEN_DIRECT,
            ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = Duration.ofMinutes(2)),
            schemaVersion = 1
        )
    }
}
