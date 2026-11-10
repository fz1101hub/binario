package it.binario.app.data.repository

import it.binario.app.data.*
import it.binario.app.data.core.EnrichedRealtimeStop
import it.binario.app.data.core.GtfsRealtimeFreshness
import it.binario.app.data.planned.BoundedSingleFlightCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Common search/widget/worker boundary for feeds joined to their exact static timetable. */
class GtfsRealtimeEnrichedTrainDataSource(
    private val plannedSource: TrainDataSource,
    private val realtime: RealtimeEnrichmentService,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = RailTime.defaultZone,
    /** [loadRoute] waits at most this long for the optional realtime, then answers with the offline result. */
    private val realtimeDeadlineMillis: Long = DEFAULT_DEADLINE_MILLIS,
    /** [observeRoute] has already shown the offline result, so it may wait longer for the realtime update. */
    private val observedRealtimeDeadlineMillis: Long = OBSERVED_DEADLINE_MILLIS
) : TrainDataSource {
    /**
     * Realtime collected for one set of trips, shared by concurrent searches. A collection outlives a caller that
     * stopped waiting at its deadline, so the feeds and the timetable index it warmed are ready for the next refresh.
     */
    private val collected = BoundedSingleFlightCache<RealtimeEnrichmentSnapshot>(16, 15_000L, clock::millis)

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now(clock))

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        return enrich(plannedSource.loadRoute(route, direction, query), query)
    }

    /** The offline result at once, then the same result with the trip updates once they arrive (or the deadline passes). */
    override fun observeRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): Flow<DataResult> = flow {
        val planned = plannedSource.loadRoute(route, direction, query)
        emit(planned)
        if (wantsRealtime(planned, query)) emit(enrich(planned, query, deadlineMillis = observedRealtimeDeadlineMillis))
    }

    private fun wantsRealtime(result: DataResult, query: JourneyQuery): Boolean =
        result is DataResult.Success && query.serviceDate == clock.instant().atZone(zone).toLocalDate() &&
            result.snapshot.services.any { it.gtfsIdentity != null }

    suspend fun enrich(
        result: DataResult,
        query: JourneyQuery,
        useLatestStopForService: Boolean = false,
        deadlineMillis: Long = realtimeDeadlineMillis
    ): DataResult {
        if (result !is DataResult.Success || query.serviceDate != clock.instant().atZone(zone).toLocalDate()) return result
        val identities = result.snapshot.services.mapNotNull { it.gtfsIdentity }
        if (identities.isEmpty()) return result
        val key = identities.joinToString("|") { "${it.sourceId}:${it.tripId}:${it.serviceDate}" }
        // Optional data: past the deadline the offline result is the answer, never a blank screen.
        val snapshot = try {
            withTimeoutOrNull(deadlineMillis) { collected.getOrLoad(key) { realtime.collectFor(identities, zone) }.value }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return result
        return withContext(Dispatchers.Default) {
            val updates = snapshot.stops.filter(::fresh)
            val used = mutableListOf<EnrichedRealtimeStop>()
            val services = result.snapshot.services.map { service ->
                val identity = service.gtfsIdentity ?: return@map service
                if (identity.stops.size != service.stops.size) return@map service
                val matches = updates.filter {
                    it.scheduledStop.sourceId.value == identity.sourceId &&
                        it.scheduledStop.sourceTripId == identity.tripId &&
                        it.scheduledStop.serviceDate == identity.serviceDate &&
                        (it.update.serviceDate == null || it.update.serviceDate == identity.serviceDate) &&
                        // Without start_date, never apply today's observation to tomorrow's train.
                        (it.update.serviceDate != null || it.scheduledStop.scheduledAt.toLocalDate() == query.serviceDate)
                }
                val perStop = identity.stops.map { stop ->
                    matches.filter { it.scheduledStop.sourceStopId == stop.stopId && it.scheduledStop.stopSequence == stop.sequence }
                        .maxByOrNull { it.update.observedAt }
                }
                val timed = perStop.map { update -> update?.takeIf { delay(it) != null } }
                if (timed.all { it == null }) return@map service
                used += timed.filterNotNull()
                val stops = service.stops.mapIndexed { index, stop ->
                    val live = timed[index] ?: return@mapIndexed if (!service.operationalStatusVerified && stop.operationalStatusVerified == null) {
                        stop.copy(operationalStatusVerified = false)
                    } else stop
                    val minutes = delay(live)!!
                    // The planned alighting stop carries the arrival, while the trip update there may be the departure
                    // event after the dwell: the expected arrival is the arrival plus the delay.
                    val alighting = index > 0 && index == service.stops.lastIndex
                    val expected = if (alighting) shifted(stop.scheduled, minutes) ?: expected(live) else expected(live)
                    stop.copy(expected = expected, delayMinutes = minutes, operationalStatusVerified = true)
                }
                val originTiming = timed.firstOrNull()
                val departure = if (useLatestStopForService) {
                    timed.filterNotNull().filter { !it.scheduledStop.scheduledAt.toInstant().isAfter(clock.instant()) }
                        .maxByOrNull { it.scheduledStop.scheduledAt } ?: timed.firstOrNull { it != null }
                } else originTiming
                val departureDelay = departure?.let(::delay)
                service.copy(
                    stops = stops,
                    expectedDeparture = originTiming?.let(::expected) ?: service.expectedDeparture,
                    delayMinutes = departureDelay ?: service.delayMinutes,
                    officialDelayMinutes = if (departure != null) {
                        departure.update.officialDelay?.value?.toMinutes()?.toInt()
                    } else service.officialDelayMinutes,
                    status = if (departureDelay != null) {
                        if (departureDelay > 0) ServiceStatus.DELAYED else ServiceStatus.ON_TIME
                    } else service.status,
                    operationalStatusVerified = service.operationalStatusVerified || departure != null,
                    operationalSourceIds = service.operationalSourceIds + timed.filterNotNull().map { it.update.source.sourceId.value },
                    operationalObservedAtEpochMillis = (timed.filterNotNull().map { it.update.observedAt.toEpochMilli() } +
                        listOfNotNull(service.operationalObservedAtEpochMillis)).maxOrNull()
                )
            }
            if (used.isEmpty()) return@withContext result
            val sources = used.map { it.update.source.sourceId.value }.distinct()
            val allVerified = services.all { it.operationalStatusVerified }
            val anyVerified = services.any { it.operationalStatusVerified }
            val sourceLabels = sources.map { when (it) {
                "alto-adige-sta-realtime" -> "STA Alto Adige"
                "sncf-gtfs-rt" -> "SNCF"
                else -> "fonte ferroviaria"
            } }.distinct()
            val notice = result.snapshot.notice?.replace("Ritardi, binari e soppressioni richiedono fonti realtime.", "")?.trim()?.takeIf(String::isNotBlank)
            DataResult.Success(result.snapshot.copy(
                services = services,
                providerName = result.snapshot.providerName + " + " + sourceLabels.joinToString(" / "),
                sourceObservedAtEpochMillis = used.maxOf { it.update.observedAt.toEpochMilli() },
                expiresAtEpochMillis = minOf(result.snapshot.expiresAtEpochMillis,
                    used.minOf { (it.update.source.expiresAt ?: it.update.observedAt.plusSeconds(300)).toEpochMilli() }),
                freshness = Freshness.FRESH,
                confidence = if (anyVerified && result.snapshot.confidence == DataConfidence.STATIC_ONLY) DataConfidence.OPEN_DATA else result.snapshot.confidence,
                qualityWarnings = if (anyVerified) result.snapshot.qualityWarnings - SnapshotWarning.STATIC_ONLY else result.snapshot.qualityWarnings,
                notice = listOfNotNull(notice, "Ritardi aggiornati da ${sourceLabels.joinToString()}.",
                    if (allVerified) null else "Le corse senza aggiornamenti riportano soltanto l'orario programmato.").joinToString(" ")
            ))
        }
    }

    companion object {
        const val DEFAULT_DEADLINE_MILLIS = 3_000L
        const val OBSERVED_DEADLINE_MILLIS = 20_000L
    }

    private fun fresh(stop: EnrichedRealtimeStop): Boolean {
        val now = clock.instant()
        return !stop.update.observedAt.isBefore(now.minus(GtfsRealtimeFreshness.OBSERVATION_WINDOW)) &&
            !stop.update.observedAt.isAfter(now.plusSeconds(60)) &&
            stop.update.source.expiresAt?.isAfter(now) != false
    }

    private fun delay(stop: EnrichedRealtimeStop): Int? =
        (stop.update.officialDelay?.value ?: stop.update.predictedAt?.value?.let {
            Duration.between(stop.scheduledStop.scheduledAt, it)
        })?.toMinutes()?.toInt()

    /** [scheduled] ("HH:mm") moved by [minutes]; null when it is not a time ("—"). */
    private fun shifted(scheduled: String, minutes: Int): String? = runCatching {
        java.time.LocalTime.parse(scheduled.trim()).plusMinutes(minutes.toLong())
            .format(DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT))
    }.getOrNull()

    private fun expected(stop: EnrichedRealtimeStop): String =
        (stop.update.predictedAt?.value ?: stop.scheduledStop.scheduledAt.plusMinutes(delay(stop)!!.toLong()))
            .format(DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT))
}
