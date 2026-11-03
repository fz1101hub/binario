package it.binario.app.journeys

import it.binario.app.data.RailTime
import it.binario.app.data.TrainService
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.LegMode
import it.binario.app.data.repository.SourcedJourneyOption
import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.data.repository.TrainNumberSearchService
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which legs of a results list get a live lookup: train legs with a public number of options with changes, running
 * now or soon ([LOOKBACK] before, [LOOKAHEAD] after now), at most [MAX_TRAINS] distinct trains. Everything else keeps
 * the scheduled times ("solo orario programmato").
 */
object JourneyLiveCandidates {
    val LOOKBACK: Duration = Duration.ofHours(3)
    val LOOKAHEAD: Duration = Duration.ofHours(3)
    const val MAX_TRAINS = 8

    data class Train(val number: String, val serviceDate: LocalDate)

    fun legs(options: List<SourcedJourneyOption>, now: Instant): List<JourneyLeg> = options.asSequence()
        .filter { it.option.transfers > 0 }
        .flatMap { it.option.legs.asSequence() }
        .filter { leg ->
            leg.mode == LegMode.TRAIN && !leg.publicNumber.isNullOrBlank() &&
                !leg.scheduledArrival.toInstant().isBefore(now.minus(LOOKBACK)) &&
                !leg.scheduledDeparture.toInstant().isAfter(now.plus(LOOKAHEAD))
        }
        .toList()

    fun trains(legs: List<JourneyLeg>, zone: ZoneId = RailTime.defaultZone): List<Train> = legs
        .map { Train(it.publicNumber.orEmpty().trim(), it.scheduledDeparture.withZoneSameInstant(zone).toLocalDate()) }
        .distinct()
        .take(MAX_TRAINS)

    /**
     * Runs of one number further apart than this at the boarding stop are two runs: the number search also returns
     * yesterday's run of a night train, still running today, which is 24 hours from the leg.
     */
    val SAME_RUN_TOLERANCE: Duration = Duration.ofHours(2)

    /**
     * The live data of [leg] among the runs found for its number, or null when none is its run or none is live. The run
     * is one that stops at both ends in order and, when its date is known, is scheduled at the boarding stop near the
     * leg's departure (today's run, not yesterday's still on its way); of those, one with verified live data first,
     * then the nearest in time. A single run whose stops cannot be matched is taken only when it starts on the leg's
     * service day or the day after.
     */
    fun liveFor(leg: JourneyLeg, services: List<TrainService>, zone: ZoneId = RailTime.defaultZone): JourneyLegLive? {
        val from = leg.originLabel ?: leg.origin.value
        val to = leg.destinationLabel ?: leg.destination.value
        val departure = leg.scheduledDeparture.toInstant()
        class Candidate(val service: TrainService, val board: Int, val offset: Duration?) {
            val otherRun: Boolean get() = offset != null && offset.abs() > SAME_RUN_TOLERANCE
        }
        fun candidate(service: TrainService): Candidate {
            val board = LiveStopTimes.stopNamed(service.stops, from)
            val offset = if (board < 0) null else boardingTime(service, board, zone)?.let { Duration.between(departure, it) }
            return Candidate(service, board, offset)
        }
        val runs = services.map(::candidate).filter { it.board >= 0 && LiveStopTimes.stopNamed(it.service.stops, to) > it.board && !it.otherRun }
        val run = runs.minWithOrNull(
            compareByDescending<Candidate> { LiveStopTimes.isVerified(it.service, it.service.stops.getOrNull(it.board)) }
                .thenBy { it.offset == null }
                .thenBy { it.offset?.abs() ?: Duration.ZERO }
        )?.service ?: services.singleOrNull()?.takeIf { single ->
            !candidate(single).otherRun && startsOnServiceDay(single, leg)
        } ?: return null
        return LiveStopTimes.live(run, from, to, departure, leg.scheduledArrival.toInstant(), zone)
    }

    /**
     * The scheduled time of [service] at its stop [board]: the stop's clock time on the run's start date, or the day
     * after for a stop past the midnight the run crosses. Null when the run has no date or the stop no readable time.
     */
    private fun boardingTime(service: TrainService, board: Int, zone: ZoneId): Instant? {
        val start = service.scheduledDepartureDateTime ?: return null
        val time = LiveStopTimes.parse(service.stops[board].scheduled) ?: return null
        var at = LocalDateTime.of(start.toLocalDate(), time)
        if (at.isBefore(start)) at = at.plusDays(1)
        return at.atZone(zone).toInstant()
    }

    /** The run starts on the leg's service day (or just after its midnight); true when either date is unknown. */
    private fun startsOnServiceDay(service: TrainService, leg: JourneyLeg): Boolean {
        val start = service.scheduledDepartureDateTime?.toLocalDate() ?: return true
        val day = leg.serviceDate ?: return true
        return !start.isBefore(day) && !start.isAfter(day.plusDays(1))
    }
}

/**
 * Live data for the legs of journeys with changes, through the existing train-number lookup (which already merges
 * the operator sources and the GTFS-Realtime of the packs that have it). Never throws: a failed lookup leaves the
 * leg without live data.
 */
class JourneyLiveLookup(
    private val search: TrainNumberSearchService,
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = RailTime.defaultZone,
    private val trainTimeoutMillis: Long = 25_000
) {
    suspend fun lookup(options: List<SourcedJourneyOption>): Map<String, JourneyLegLive> {
        val legs = JourneyLiveCandidates.legs(options, now())
        if (legs.isEmpty()) return emptyMap()
        val trains = JourneyLiveCandidates.trains(legs, zone)
        val found = coroutineScope {
            trains.chunked(PARALLEL).flatMap { chunk ->
                chunk.map { train -> async { train to services(train) } }.awaitAll()
            }
        }.toMap()
        return legs.mapNotNull { leg ->
            val train = JourneyLiveCandidates.Train(leg.publicNumber.orEmpty().trim(), leg.scheduledDeparture.withZoneSameInstant(zone).toLocalDate())
            val services = found[train] ?: return@mapNotNull null
            JourneyLiveCandidates.liveFor(leg, services, zone)?.let { TransferRiskCalculator.legKey(leg) to it }
        }.toMap()
    }

    private suspend fun services(train: JourneyLiveCandidates.Train): List<TrainService> = try {
        withTimeoutOrNull(trainTimeoutMillis) {
            search.search(TrainNumberQuery(train.number, train.serviceDate)).lastOrNull()?.matches?.map { it.service }
        }.orEmpty()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val PARALLEL = 3
    }
}
