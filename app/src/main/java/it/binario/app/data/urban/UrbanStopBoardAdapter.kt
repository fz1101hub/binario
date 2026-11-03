package it.binario.app.data.urban

import it.binario.app.data.RailTime
import it.binario.app.data.StationSearchNormalizer
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.DataWarning
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.gtfs.GtfsCompactIndexLimitException
import it.binario.app.data.gtfs.GtfsDayTimetable
import it.binario.app.data.gtfs.GtfsStationCodeIndex
import it.binario.app.data.gtfs.GtfsStopDirectory
import it.binario.app.data.gtfs.StopNameCase
import it.binario.app.data.journey.urbanLineName
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.ProviderWarning
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.data.provider.boardWindowKeeps
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A board for the stops of one city network only; the station board service asks it instead of the railway boards. */
interface StopScopedBoardProvider : StationBoardProvider {
    fun servesStation(stationId: StationId): Boolean
}

/**
 * Departures and arrivals of a metro, tram or funicular stop (`gtfs:<city pack>:<stop>`) from the embedded city pack,
 * with the expected times and cancellations of the network's open GTFS-Realtime feed when it has one ([realtime]).
 * The stop's platforms are the pack stops with the same name within [SIBLING_METERS] (or the same parent station).
 * Offline for the timetable; the realtime feed is read only when such a board is opened, through its 30-second cache.
 * A row of a trip without its own update keeps the scheduled time; a stale or missing feed leaves every row scheduled
 * and flags the board with [ProviderWarning.REALTIME_UNAVAILABLE].
 */
class UrbanStopBoardAdapter(
    private val pack: EmbeddedGtfsPack,
    private val adapter: EmbeddedGtfsRouteAdapter,
    private val realtime: UrbanGtfsRealtimeAdapter? = null,
    networkName: String = pack.sourceId.value,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = RailTime.defaultZone,
    private val window: Duration = Duration.ofMinutes(90),
    private val maxEntries: Int = 40
) : StopScopedBoardProvider {
    init {
        require(pack.isUrban) { "Tabellone urbano solo per i pack di metro e tram" }
    }

    override val descriptor = ProviderDescriptor(
        id = SourceId("${pack.sourceId.value}-board"),
        displayName = networkName,
        capabilities = setOf(ProviderCapability.STATION_BOARD),
        coverage = CoverageDescriptor(countryCodes = setOf("IT"), description = "Fermate del pack ${pack.sourceId.value}"),
        accessMode = AccessMode.OPEN_DIRECT,
        ratePolicy = RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = Duration.ofMinutes(1)),
        schemaVersion = 1
    )

    private val prefix = "gtfs:${pack.sourceId.value}:"
    private val directoryMutex = Mutex()
    @Volatile private var directory: GtfsStopDirectory? = null

    override fun servesStation(stationId: StationId): Boolean = stationId.value.startsWith(prefix) && stationId.value.length > prefix.length

    override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
        if (!servesStation(request.stationId)) {
            return ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_SUPPORTED, "Fermata di un'altra rete."))
        }
        return try {
            board(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: GtfsCompactIndexLimitException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.INVALID_DATA, "Orario della rete non leggibile."))
        } catch (_: IOException) {
            ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_FOUND, "Orario della rete non disponibile in questa build."))
        }
    }

    private suspend fun board(request: StationBoardRequest): ProviderResult<StationBoard> {
        val stopId = request.stationId.value.removePrefix(prefix)
        val stops = stopDirectory()
        val platforms = UrbanStopPlatforms.of(stops, stopId)
        if (platforms.isEmpty()) return ProviderResult.Failure(ProviderError(ProviderErrorKind.NOT_FOUND, "Fermata non trovata."))
        val now = clock.instant()
        val from = maxOf(request.from, now.minus(Duration.ofMinutes(2)))
        val to = minOf(request.to, from.plus(window))
        val departures = request.kind == BoardKind.DEPARTURES
        // With a realtime feed, runs scheduled a little earlier are read too: a late tram stays until its expected time.
        val lookFrom = request.lateSince?.takeIf { realtime != null }?.let { maxOf(it, now.minus(LATE_LOOKBACK)) }?.coerceAtMost(from) ?: from
        val scheduled = scheduledRows(platforms, lookFrom, to, departures)
        val snapshot = realtime?.takeIf { scheduled.isNotEmpty() }?.snapshot()
        val live = snapshot?.takeIf { it.isFresh(now) }
        val entries = scheduled.map { row -> entry(row, live, departures) }
            .filter { entry -> boardWindowKeeps(from, to, lookFrom, entry.scheduledTime.toInstant(), entry.expectedTime?.value?.toInstant()) }
            .sortedBy { (it.expectedTime?.value ?: it.scheduledTime).toInstant() }
            .take(maxEntries)
        val staticSource = SourceRef(
            sourceId = pack.sourceId,
            fetchedAt = now,
            expiresAt = now.plus(Duration.ofHours(12)),
            confidence = Confidence.OPEN_DATA,
            traceId = "urban-board:${pack.sourceId.value}"
        )
        val sources = setOfNotNull(staticSource, live?.let { realtimeSource(it, it.feed.sourceId.value) })
        val warnings = buildSet {
            if (realtime != null && live == null) add(ProviderWarning.REALTIME_UNAVAILABLE)
        }
        val board = StationBoard(
            stationId = request.stationId,
            kind = request.kind,
            from = from,
            to = to,
            entries = entries,
            snapshot = SnapshotMeta(
                schemaVersion = 1,
                generatedAt = now,
                validUntil = now.plus(if (live != null) Duration.ofSeconds(30) else Duration.ofMinutes(5)),
                freshness = if (live != null) Freshness.FRESH else Freshness.AGING,
                completeness = if (live != null) Completeness.COMPLETE else Completeness.STATIC_ONLY,
                contributingSources = sources,
                warnings = if (live != null) emptySet() else setOf(DataWarning.STATIC_ONLY, DataWarning.REALTIME_UNAVAILABLE)
            )
        )
        val meta = ProviderResponseMeta(descriptor.id, now, observedAt = live?.observedAt ?: now, expiresAt = board.snapshot.validUntil)
        return if (warnings.isEmpty()) ProviderResult.Success(board, meta) else ProviderResult.Partial(board, warnings, meta)
    }

    private suspend fun stopDirectory(): GtfsStopDirectory {
        directory?.let { return it }
        return directoryMutex.withLock { directory ?: adapter.stopDirectory(pack).also { directory = it } }
    }

    /**
     * The scheduled calls at [platforms] between [from] and [to] of the service days that can run then (today and,
     * for runs after midnight, yesterday): connections leaving one of them for departures, arriving for arrivals.
     */
    private suspend fun scheduledRows(platforms: Set<String>, from: Instant, to: Instant, departures: Boolean): List<ScheduledCall> {
        val today = from.atZone(zone).toLocalDate()
        return listOf(today.minusDays(1), today).flatMap { date ->
            val origin = date.atTime(12, 0).atZone(zone).minusHours(12)
            val fromMinute = Duration.between(origin, from.atZone(zone)).toMinutes().toInt()
            val toMinute = Duration.between(origin, to.atZone(zone)).toMinutes().toInt()
            if (toMinute < 0 || fromMinute > MAX_GTFS_MINUTE) return@flatMap emptyList()
            // Earlier and later connections too, so that a run's first and last stop are known.
            val day = adapter.dayTimetable(pack, date, (fromMinute - SPAN_MINUTES).coerceAtLeast(0), toMinute + SPAN_MINUTES)
            calls(day, platforms, fromMinute, toMinute, departures, origin)
        }.distinctBy { "${it.tripId}|${it.serviceDate}|${it.stopId}|${it.scheduled.toEpochSecond()}" }
    }

    private fun calls(
        day: GtfsDayTimetable,
        platforms: Set<String>,
        fromMinute: Int,
        toMinute: Int,
        departures: Boolean,
        origin: ZonedDateTime
    ): List<ScheduledCall> {
        val rows = ArrayList<ScheduledCall>()
        day.trips.forEach { trip ->
            if (trip.connectionCount == 0) return@forEach
            val first = trip.firstConnection
            val last = first + trip.connectionCount - 1
            for (connection in first..last) {
                val stop = if (departures) day.departureStop[connection] else day.arrivalStop[connection]
                if (day.stopIds[stop] !in platforms) continue
                val minute = if (departures) day.departureMinute[connection] else day.arrivalMinute[connection]
                if (minute !in fromMinute..toMinute) continue
                if (departures && !day.pickupAllowed(connection)) continue
                if (!departures && !day.dropOffAllowed(connection)) continue
                // Departures show where the run ends, arrivals where it started.
                val endStop = if (departures) day.arrivalStop[last] else day.departureStop[first]
                rows += ScheduledCall(
                    tripId = trip.tripId,
                    serviceDate = day.serviceDate,
                    stopId = day.stopIds[stop],
                    scheduled = origin.plusMinutes(minute.toLong()),
                    endpointId = day.stopIds[endStop],
                    endpointName = StopNameCase.display(day.stopNames[endStop]),
                    line = urbanLineName(trip.mode, trip.lineLabel),
                    mode = trip.mode,
                    operatorName = trip.operatorName
                )
            }
        }
        return rows
    }

    private fun entry(row: ScheduledCall, live: UrbanRealtimeSnapshot?, departures: Boolean): StationBoardEntry {
        val update = live?.trip(row.tripId, row.serviceDate)
        val expected = update?.expectedAt(row.stopId, row.scheduled, departures)
        val source = live?.let { realtimeSource(it, "${row.tripId}:${row.stopId}", update?.observedAt) }
        return StationBoardEntry(
            runId = TrainRunId("${pack.sourceId.value}:${row.tripId}:${row.serviceDate}"),
            scheduledTime = row.scheduled,
            expectedTime = expected?.expected?.takeIf { !expected.cancelled }?.let { value -> source?.let { SourcedValue(value, it) } },
            destinationOrOrigin = StationId(prefix + row.endpointId),
            destinationOrOriginLabel = row.endpointName,
            operatorId = row.operatorName?.let(::OperatorId),
            status = when {
                expected?.cancelled == true -> source?.let { SourcedValue(RunStatus.CANCELLED, it) }
                expected?.expected != null -> source?.let {
                    SourcedValue(if (expected.expected.isAfter(row.scheduled.plusSeconds(59))) RunStatus.DELAYED else RunStatus.ON_TIME, it)
                }
                else -> null
            },
            nativeRunIds = mapOf(pack.sourceId to row.tripId),
            lineLabel = row.line,
            serviceKind = ServiceKind.URBAN
        )
    }

    private fun realtimeSource(snapshot: UrbanRealtimeSnapshot, nativeId: String, observedAt: Instant? = null): SourceRef {
        val observed = observedAt ?: snapshot.observedAt
        return SourceRef(
            sourceId = snapshot.feed.sourceId,
            nativeId = nativeId,
            observedAt = observed,
            fetchedAt = snapshot.fetchedAt,
            expiresAt = snapshot.observedAt.plus(UrbanRealtimeSnapshot.MAX_AGE),
            confidence = Confidence.OPEN_DATA,
            licenseId = snapshot.feed.licence,
            traceId = "urban-rt:${snapshot.feed.sourceId.value}"
        )
    }

    private data class ScheduledCall(
        val tripId: String,
        val serviceDate: LocalDate,
        val stopId: String,
        val scheduled: ZonedDateTime,
        val endpointId: String,
        val endpointName: String,
        val line: String?,
        val mode: LegMode,
        val operatorName: String?
    )

    private companion object {
        /** Minutes read before and after the window to see a run's ends: longer than any city line. */
        const val SPAN_MINUTES = 180
        const val MAX_GTFS_MINUTE = 48 * 60
        /** City runs are rarely this late; earlier rows are not read even when the board asks for a longer look-back. */
        val LATE_LOOKBACK: Duration = Duration.ofMinutes(30)
    }
}

/** The platforms of a city stop: same displayed name within [SIBLING_METERS], or the same parent station. */
object UrbanStopPlatforms {
    const val SIBLING_METERS = 400.0

    internal fun of(directory: GtfsStopDirectory, stopId: String): Set<String> {
        val index = directory.ids.indexOf(stopId)
        if (index < 0) return emptySet()
        val name = StationSearchNormalizer.normalize(StopNameCase.display(directory.names[index]))
        val parent = directory.parents[index].takeIf(String::isNotBlank)
        val latitude = directory.latitude(index)
        val longitude = directory.longitude(index)
        return directory.ids.indices.filter { other ->
            if (other == index) return@filter true
            val otherParent = directory.parents[other].takeIf(String::isNotBlank)
            if (parent != null && (otherParent == parent || directory.ids[other] == parent)) return@filter true
            if (otherParent == stopId) return@filter true
            if (StationSearchNormalizer.normalize(StopNameCase.display(directory.names[other])) != name) return@filter false
            val otherLatitude = directory.latitude(other)
            val otherLongitude = directory.longitude(other)
            latitude != null && longitude != null && otherLatitude != null && otherLongitude != null &&
                GtfsStationCodeIndex.distanceKm(latitude, longitude, otherLatitude, otherLongitude) * 1000.0 <= SIBLING_METERS
        }.mapTo(linkedSetOf()) { directory.ids[it] }
    }
}
