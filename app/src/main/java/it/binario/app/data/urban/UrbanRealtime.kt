package it.binario.app.data.urban

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.RailTime
import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.BoundedGtfsZipScanner
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.GtfsCompactIndexLimits
import it.binario.app.data.live.ResilientSourceCache
import it.binario.app.data.live.SourceRead
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.RailProvider
import it.binario.app.data.provider.RatePolicy
import java.io.InputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * One open GTFS-Realtime Trip Updates feed of a city network, joined to the city pack whose trip and stop ids it
 * uses ([staticSourceId]). [personalOnly] feeds (licence not stated or not verifiable, see SOURCE_POLICY.md) exist
 * only in personal builds, like their packs.
 */
data class UrbanRealtimeFeed(
    val sourceId: SourceId,
    val staticSourceId: SourceId,
    /** Publisher shown as the source of the live times ("Roma Servizi per la Mobilità"). */
    val displayName: String,
    val tripUpdatesUrl: String,
    val licence: String,
    val personalOnly: Boolean,
    val zone: ZoneId = RailTime.defaultZone
) {
    init {
        require(tripUpdatesUrl.startsWith("https://")) { "Il feed realtime deve usare HTTPS" }
    }
}

/** The city realtime feeds verified from CI (see SOURCE_POLICY.md, «Tempo reale di metro e tram»). */
object UrbanRealtimeFeeds {
    /**
     * Roma Servizi per la Mobilità, Trip Updates of the ATAC network: buses and trams with AVM, no metro (CI runs
     * 41564222536 and 41583916012); the same trip_id and stop_id as the static GTFS the `roma-mobilita-gtfs` pack is
     * built from. Personal builds only, like the pack.
     */
    val ROMA = UrbanRealtimeFeed(
        sourceId = SourceId("roma-mobilita-gtfs-rt"),
        staticSourceId = SourceId("roma-mobilita-gtfs"),
        displayName = "Roma Servizi per la Mobilità",
        tripUpdatesUrl = "https://romamobilita.it/sites/default/files/rome_rtgtfs_trip_updates_feed.pb",
        licence = "CC BY-SA su dati.comune.roma.it, CC BY 4.0 su dati.gov.it, nessuna sulla pagina ufficiale",
        personalOnly = true
    )

    /**
     * ACTV automobilistico, Trip Updates of the Mestre trams T1 and T2 (and the buses, dropped): trip_id and stop_id of
     * the `actv_aut` GTFS the `venezia-actv-gtfs` pack is built from. Licence not stated (ACTV data catalogue, art. 53
     * CAD), like the pack: personal builds only.
     */
    val ACTV = UrbanRealtimeFeed(
        sourceId = SourceId("venezia-actv-gtfs-rt"),
        staticSourceId = SourceId("venezia-actv-gtfs"),
        displayName = "ACTV",
        tripUpdatesUrl = "https://tpl.actv.it/aut/GTFSRT/tripUpdates",
        licence = "Non dichiarata (catalogo dei dati ACTV, art. 53 CAD)",
        personalOnly = true
    )

    val all: List<UrbanRealtimeFeed> = listOf(ROMA, ACTV)

    /** The feeds of this build whose city pack is in [packs]. */
    fun forBuild(personalSources: Boolean, packs: List<EmbeddedGtfsPack>): List<UrbanRealtimeFeed> =
        all.filter { feed -> (personalSources || !feed.personalOnly) && packs.any { it.sourceId == feed.staticSourceId } }

    /**
     * The city feed behind the live times of a board and its latest observation, for "Tempo reale <fonte>, aggiornato
     * alle <ora>"; null when no row carries a city feed's time or status.
     */
    fun observation(entries: List<it.binario.app.data.core.StationBoardEntry>): Pair<String, Instant>? {
        val byId = all.associateBy { it.sourceId }
        val sources = entries.flatMap { listOfNotNull(it.expectedTime?.source, it.status?.source) }
            .filter { it.sourceId in byId && it.observedAt != null }
        val latest = sources.maxByOrNull { it.observedAt!! } ?: return null
        return byId.getValue(latest.sourceId).displayName to latest.observedAt!!
    }
}

/** One stop of a realtime trip: absolute expected times when the feed publishes them, else the delays. */
data class UrbanStopTimeRealtime(
    val stopId: String?,
    val stopSequence: Int?,
    val arrival: Instant? = null,
    val departure: Instant? = null,
    val arrivalDelaySeconds: Int? = null,
    val departureDelaySeconds: Int? = null,
    /** SKIPPED: the vehicle does not stop here. */
    val skipped: Boolean = false
)

data class UrbanTripRealtime(
    val tripId: String,
    val routeId: String?,
    /** GTFS service day of the trip (start_date); null when the feed omits it. */
    val startDate: LocalDate?,
    val cancelled: Boolean,
    val stops: List<UrbanStopTimeRealtime>,
    val observedAt: Instant
)

/** What a board row or a journey leg shows of a trip at one stop. */
data class UrbanExpectedTime(val expected: ZonedDateTime?, val cancelled: Boolean)

/** The Trip Updates of one feed kept for the city pack's routes, by trip id. */
data class UrbanRealtimeSnapshot(
    val feed: UrbanRealtimeFeed,
    /** Feed header timestamp, else the fetch time. */
    val observedAt: Instant,
    val fetchedAt: Instant,
    val trips: Map<String, List<UrbanTripRealtime>>
) {
    /**
     * The update of [tripId] on [serviceDate]: the one with that start_date, or, when the feed publishes none, only
     * for the service day of the observation (never today's update on tomorrow's run).
     */
    fun trip(tripId: String, serviceDate: LocalDate): UrbanTripRealtime? {
        val candidates = trips[tripId] ?: return null
        candidates.firstOrNull { it.startDate == serviceDate }?.let { return it }
        return candidates.firstOrNull { it.startDate == null && observedAt.atZone(feed.zone).toLocalDate() == serviceDate }
    }

    /** True while the observation is recent enough to show as live (five minutes, at most one in the future). */
    fun isFresh(now: Instant): Boolean =
        !observedAt.isBefore(now.minus(MAX_AGE)) && !observedAt.isAfter(now.plusSeconds(60))

    companion object {
        val MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}

/**
 * The expected time of this trip at [stopId] for the event scheduled at [scheduled] (a departure when [departure],
 * else an arrival). Only the stop's own update counts: a stop without one stays scheduled, never guessed from a
 * neighbour. A cancelled trip or a skipped stop is cancelled; an expected time more than three hours from the
 * scheduled one is refused as a mismatched trip.
 */
fun UrbanTripRealtime.expectedAt(stopId: String, scheduled: ZonedDateTime, departure: Boolean): UrbanExpectedTime? {
    if (cancelled) return UrbanExpectedTime(null, cancelled = true)
    val candidates = stops.filter { it.stopId == stopId }
    if (candidates.isEmpty()) return null
    val stop = candidates.minByOrNull { candidate ->
        val time = candidate.eventTime(departure) ?: return@minByOrNull Long.MAX_VALUE
        Duration.between(scheduled.toInstant(), time).abs().seconds
    } ?: return null
    if (stop.skipped) return UrbanExpectedTime(null, cancelled = true)
    val expected = stop.eventTime(departure)?.atZone(scheduled.zone)
        ?: stop.eventDelay(departure)?.let { scheduled.plusSeconds(it.toLong()) }
        ?: return null
    if (Duration.between(scheduled, expected).abs() > MAX_SHIFT) return null
    return UrbanExpectedTime(expected, cancelled = false)
}

private val MAX_SHIFT: Duration = Duration.ofHours(3)

private fun UrbanStopTimeRealtime.eventTime(departure: Boolean): Instant? =
    if (departure) this.departure ?: arrival else arrival ?: this.departure

private fun UrbanStopTimeRealtime.eventDelay(departure: Boolean): Int? =
    if (departure) departureDelaySeconds ?: arrivalDelaySeconds else arrivalDelaySeconds ?: departureDelaySeconds

/**
 * GTFS-Realtime Trip Updates to [UrbanRealtimeSnapshot] with the existing protobuf bindings. Only trips of the city
 * pack's routes are kept ([keepRoute]; a trip without route_id is kept, its trip_id still has to match the pack):
 * the Rome feed also carries every bus. Added, unscheduled and duplicated trips have no static run to join and are
 * dropped, and so are NO_DATA stops.
 */
object UrbanGtfsRealtimeDecoder {
    const val MAX_TRIPS = 20_000

    fun decode(
        payload: ByteArray,
        feed: UrbanRealtimeFeed,
        fetchedAt: Instant,
        keepRoute: (String) -> Boolean = { true }
    ): UrbanRealtimeSnapshot {
        val message = GtfsRealtime.FeedMessage.parseFrom(payload)
        require(message.hasHeader() && message.header.gtfsRealtimeVersion.isNotBlank()) { "FeedHeader mancante" }
        val observedAt = message.header.timestamp.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: fetchedAt
        val trips = HashMap<String, MutableList<UrbanTripRealtime>>()
        var kept = 0
        for (entity in message.entityList) {
            if (!entity.hasTripUpdate() || entity.isDeleted) continue
            val update = entity.tripUpdate
            val descriptor = update.trip
            val tripId = descriptor.tripId.takeIf(String::isNotBlank) ?: continue
            val routeId = descriptor.routeId.takeIf(String::isNotBlank)
            if (routeId != null && !keepRoute(routeId)) continue
            val relationship = descriptor.scheduleRelationship
            val cancelled = relationship == GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED
            if (!cancelled && relationship != GtfsRealtime.TripDescriptor.ScheduleRelationship.SCHEDULED) continue
            val dateText = descriptor.startDate.takeIf(String::isNotBlank)
            val startDate = dateText?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }
            // A start_date that is not a date cannot be tied to a service day.
            if (dateText != null && startDate == null) continue
            val stops = update.stopTimeUpdateList.mapNotNull { stop ->
                val stopRelationship = stop.scheduleRelationship
                if (stopRelationship == GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA) return@mapNotNull null
                val stopId = stop.stopId.takeIf(String::isNotBlank)
                val sequence = stop.stopSequence.takeIf { stop.hasStopSequence() }
                if (stopId == null && sequence == null) return@mapNotNull null
                UrbanStopTimeRealtime(
                    stopId = stopId,
                    stopSequence = sequence,
                    arrival = stop.takeIf { it.hasArrival() && it.arrival.hasTime() && it.arrival.time > 0 }?.arrival?.time?.let(Instant::ofEpochSecond),
                    departure = stop.takeIf { it.hasDeparture() && it.departure.hasTime() && it.departure.time > 0 }?.departure?.time?.let(Instant::ofEpochSecond),
                    arrivalDelaySeconds = stop.takeIf { it.hasArrival() && it.arrival.hasDelay() }?.arrival?.delay,
                    departureDelaySeconds = stop.takeIf { it.hasDeparture() && it.departure.hasDelay() }?.departure?.delay,
                    skipped = stopRelationship == GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED
                )
            }
            if (!cancelled && stops.isEmpty()) continue
            val tripObservedAt = update.timestamp.takeIf { it > 0 }?.let(Instant::ofEpochSecond) ?: observedAt
            trips.getOrPut(tripId) { ArrayList(1) } += UrbanTripRealtime(tripId, routeId, startDate, cancelled, stops, tripObservedAt)
            if (++kept >= MAX_TRIPS) break
        }
        return UrbanRealtimeSnapshot(feed, observedAt, fetchedAt, trips)
    }
}

/** Reads the bytes of an HTTPS feed; throws an IOException for network and HTTP failures. */
fun interface UrbanRealtimeFetcher {
    suspend fun fetch(url: String): ByteArray
}

/**
 * Trip Updates of one [UrbanRealtimeFeed], fetched only when a board or a journey of its city asks for them: a cache
 * of 30 seconds (one request per half minute at most, coalesced), stale values for two minutes after a failed
 * refresh and the shared circuit breaker of the public sources.
 */
class UrbanGtfsRealtimeAdapter(
    val feed: UrbanRealtimeFeed,
    private val fetcher: UrbanRealtimeFetcher = HttpsBytesFetcher(),
    private val clock: Clock = Clock.systemUTC(),
    cacheTtl: Duration = Duration.ofSeconds(30),
    /** route_id values of the city pack, read lazily; null keeps every route. */
    private val packRoutes: (suspend () -> Set<String>?)? = null
) : RailProvider {
    init {
        require(cacheTtl >= Duration.ofSeconds(30)) { "Il realtime urbano va chiesto al più ogni 30 secondi" }
    }

    override val descriptor = descriptor(feed, cacheTtl)

    private val cache = ResilientSourceCache<UrbanRealtimeSnapshot>(ttl = cacheTtl, maxStale = Duration.ofMinutes(2), clock = clock, maxEntries = 1)

    /** The latest snapshot, or null when the feed is unavailable; a stale value is returned flagged by its age. */
    suspend fun snapshot(): UrbanRealtimeSnapshot? = when (val read = cache.get(feed.sourceId.value) { load() }) {
        is SourceRead.Fresh -> read.value
        is SourceRead.Stale -> read.value
        is SourceRead.Failed -> null
    }

    private suspend fun load(): UrbanRealtimeSnapshot {
        val routes = packRoutes?.invoke()
        val payload = fetcher.fetch(feed.tripUpdatesUrl)
        return UrbanGtfsRealtimeDecoder.decode(payload, feed, clock.instant()) { routeId -> routes == null || routeId in routes }
    }

    companion object {
        fun descriptor(feed: UrbanRealtimeFeed, ttl: Duration = Duration.ofSeconds(30)) = ProviderDescriptor(
            id = feed.sourceId,
            displayName = "${feed.displayName} — GTFS-Realtime",
            capabilities = setOf(ProviderCapability.TRAIN_RUN_REALTIME),
            coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Metro e tram del pack ${feed.staticSourceId.value}"),
            accessMode = AccessMode.OPEN_DIRECT,
            ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = ttl),
            schemaVersion = 1
        )
    }
}

/** The route_id values of a city pack (routes.txt is a few lines), to keep only its trips from a citywide feed. */
internal object UrbanPackRoutes {
    fun read(stream: InputStream): Set<String> {
        val ids = linkedSetOf<String>()
        stream.use { input ->
            BoundedGtfsZipScanner(GtfsCompactIndexLimits())
                .scan(input, setOf("routes.txt")) { _, rows ->
                    val index = rows.header.indexOf("route_id")
                    if (index >= 0) rows.forEachRow(MAX_ROUTES) { row -> row.getOrNull(index)?.trim()?.takeIf(String::isNotEmpty)?.let(ids::add) }
                }
        }
        return ids
    }

    private const val MAX_ROUTES = 5_000
}
