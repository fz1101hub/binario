package it.binario.app.data.live

import it.binario.app.data.*
import it.binario.app.data.core.*
import it.binario.app.data.planned.BoundedSingleFlightCache
import it.binario.app.data.provider.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter

/** Same train on both official boards proves the requested segment, including intermediate stops.
 * No static timetable or guessed intermediate calling points are needed.
 */
class RfiLiveRouteDataSource(
    private val provider: StationBoardProvider,
    private val clock: Clock = Clock.systemUTC()
) : RealtimeRouteDataSource {
    override val descriptor get() = provider.descriptor
    private val zone = RailTime.defaultZone
    private val cache = BoundedSingleFlightCache<ProviderResult<StationBoard>>(64, 30_000, clock::millis)

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now(clock))

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult = coroutineScope {
        if (query.serviceDate != clock.instant().atZone(zone).toLocalDate()) return@coroutineScope DataResult.Failure("RFI live disponibile solo per oggi.")
        val origin = route.origin(direction)
        val destination = route.destination(direction)
        if (!italianStation(origin) || !italianStation(destination)) return@coroutineScope DataResult.Failure("Tratta fuori dalla copertura RFI verificabile.")
        val requested = query.requestedAt.atZone(zone)
        // The monitor is current, not a historical/date-query API. Anchor its
        // clock-only rows to now so an old evening row cannot become tomorrow's train.
        val boardFrom = clock.instant().minusSeconds(2 * 3600)
        val boardTo = clock.instant().plusSeconds(8 * 3600)
        suspend fun board(station: Station, kind: BoardKind): ProviderResult<StationBoard> =
            cache.getOrLoad("${station.id}:${station.name}:${station.aliases}:$kind:${query.serviceDate}") {
                provider.getStationBoard(StationBoardRequest(StationId(station.id), station.name, station.aliases, station.railwayCode,
                    boardFrom, boardTo, kind))
            }.value
        val departuresCall = async { board(origin, BoardKind.DEPARTURES) }
        val arrivalsCall = async { board(destination, BoardKind.ARRIVALS) }
        val departures = departuresCall.await().boardOrNull()
        val arrivals = arrivalsCall.await().boardOrNull()
        if (departures == null || arrivals == null) return@coroutineScope DataResult.Failure("Tabelloni live RFI non disponibili per entrambe le stazioni.", RouteFailureReason.NetworkUnavailable)
        val used = mutableListOf<SourceRef>()
        val services = departures.entries.mapNotNull { departure ->
            val number = departure.publicNumber?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val operator = departure.operatorId ?: return@mapNotNull null
            if (departure.scheduledTime.toInstant().isBefore(boardFrom) || departure.scheduledTime.toInstant().isAfter(boardTo)) return@mapNotNull null
            if ((departure.expectedTime?.value ?: departure.scheduledTime).isBefore(requested)) return@mapNotNull null
            val candidates = arrivals.entries.filter { arrival ->
                arrival.publicNumber == number && arrival.operatorId == operator &&
                    !arrival.scheduledTime.toInstant().isBefore(boardFrom) && !arrival.scheduledTime.toInstant().isAfter(boardTo) &&
                    arrival.scheduledTime.isAfter(departure.scheduledTime) &&
                    Duration.between(departure.scheduledTime, arrival.scheduledTime) <= Duration.ofHours(24)
            }
            val arrival = candidates.singleOrNull() ?: return@mapNotNull null
            val timing = departure.expectedTime?.takeIf { fresh(it.source) }
            val status = departure.status?.takeIf { fresh(it.source) }
            val cancelled = status?.value == RunStatus.CANCELLED || arrival.status?.takeIf { fresh(it.source) }?.value == RunStatus.CANCELLED
            val verified = timing != null || status?.value in setOf(RunStatus.ON_TIME, RunStatus.DELAYED, RunStatus.CANCELLED) || cancelled
            val sources = listOfNotNull(timing?.source, status?.source, arrival.expectedTime?.takeIf { fresh(it.source) }?.source,
                arrival.status?.takeIf { fresh(it.source) }?.source)
            if (verified) used += sources
            val delay = timing?.value?.let { Duration.between(departure.scheduledTime, it).toMinutes().toInt() }
            val scheduled = departure.scheduledTime.format(HH_MM)
            val expected = timing?.value?.format(HH_MM) ?: scheduled
            val arrivalScheduled = arrival.scheduledTime.format(HH_MM)
            val arrivalTiming = arrival.expectedTime?.takeIf { fresh(it.source) }
            val arrivalExpected = arrivalTiming?.value?.format(HH_MM) ?: arrivalScheduled
            val arrivalDelay = arrivalTiming?.value?.let { Duration.between(arrival.scheduledTime, it).toMinutes().toInt() } ?: 0
            TrainService(
                id = "rfi-live:${origin.id}:${destination.id}:$number:${departure.scheduledTime.toInstant()}", number = number,
                operator = legacyOperator(operator), trainCategory = if (operator.value == "italo") TrainCategory.AV_ITALO else TrainCategory.OTHER,
                origin = origin.name, destination = destination.name,
                scheduledDeparture = scheduled, expectedDeparture = expected, platform = departure.platform?.takeIf { fresh(it.source) }?.value,
                delayMinutes = delay ?: 0, officialDelayMinutes = delay,
                status = when { cancelled -> ServiceStatus.CANCELLED; (delay ?: 0) > 0 || status?.value == RunStatus.DELAYED -> ServiceStatus.DELAYED; else -> ServiceStatus.ON_TIME },
                stops = listOf(TrainStop(origin.name, scheduled, expected, departure.platform?.value, delay ?: 0, false),
                    TrainStop(destination.name, arrivalScheduled, arrivalExpected, arrival.platform?.value, arrivalDelay, false)),
                operationalStatusVerified = verified,
                scheduledDepartureDateTime = departure.scheduledTime.withZoneSameInstant(zone).toLocalDateTime(),
                operationalSourceIds = if (verified) sources.map { it.sourceId.value }.toSet() else emptySet(),
                operationalObservedAtEpochMillis = if (verified) sources.maxOfOrNull { (it.observedAt ?: it.fetchedAt).toEpochMilli() } else null
            )
        }.distinctBy { it.id }.sortedBy { it.scheduledDepartureDateTime }.take(12)
        if (services.none { it.operationalStatusVerified }) return@coroutineScope DataResult.Failure(
            "Nessuna corsa live riconciliata nei tabelloni RFI: partenze=${departures.entries.size}, arrivi=${arrivals.entries.size}.")
        DataResult.Success(RouteSnapshot(route, direction, services, "RFI — Arrivi/Partenze live", clock.millis(),
            used.minOfOrNull { (it.expiresAt ?: clock.instant().plusSeconds(30)).toEpochMilli() } ?: clock.millis() + 30_000,
            it.binario.app.data.Freshness.FRESH, used.maxOfOrNull { (it.observedAt ?: it.fetchedAt).toEpochMilli() } ?: clock.millis(),
            DataConfidence.INFRASTRUCTURE_PRIMARY,
            notice = "Ritardi dai tabelloni RFI. Sono mostrate le fermate di partenza e arrivo richieste."))
    }

    private fun fresh(source: SourceRef): Boolean {
        val now = clock.instant()
        val observed = source.observedAt ?: source.fetchedAt
        return !observed.isBefore(now.minusSeconds(300)) && !observed.isAfter(now.plusSeconds(60)) && source.expiresAt?.isAfter(now) != false
    }
    private fun italianStation(station: Station): Boolean = station.railwayCode?.matches(Regex("S\\d{5}")) == true ||
        station.id.matches(Regex("S\\d{5}")) || RfiStationIds.placeId(station) != null
    private fun ProviderResult<StationBoard>.boardOrNull(): StationBoard? = when (this) {
        is ProviderResult.Success -> value
        is ProviderResult.Partial -> value
        else -> null
    }
    private fun legacyOperator(operator: OperatorId): RailOperator = when (operator.value) {
        "trenitalia" -> RailOperator.TRENITALIA_LONG_DISTANCE
        "trenord" -> RailOperator.TRENORD
        "italo" -> RailOperator.ITALO
        else -> RailOperator.OTHER
    }
    companion object { private val HH_MM = DateTimeFormatter.ofPattern("HH:mm") }
}
