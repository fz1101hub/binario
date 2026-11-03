package it.binario.app.journeys

import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.SourceId
import it.binario.app.data.repository.SourcedJourneyOption
import it.binario.app.data.urban.UrbanGtfsRealtimeAdapter
import it.binario.app.data.urban.UrbanRealtimeSnapshot
import it.binario.app.data.urban.expectedAt
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Live times of the metro and tram legs of journey results, from the open GTFS-Realtime feed of the leg's city network
 * ([feeds], by city pack). A leg is joined to its feed only by its exact identity: the pack, trip_id and service day
 * of its run id (`<pack>:<trip>:<day>`) and the stop_id of its boarding and alighting stop (`gtfs:<pack>:<stop>`).
 * Only legs running now or soon ([LOOKBACK], [LOOKAHEAD]) ask, one feed read per network through its 30-second cache;
 * a leg without its own update, or a feed that is stale or unavailable, keeps the scheduled times. Never throws.
 */
class UrbanJourneyLiveLookup(
    private val feeds: Map<SourceId, UrbanGtfsRealtimeAdapter>,
    private val now: () -> Instant = Instant::now
) {
    val hasFeeds: Boolean get() = feeds.isNotEmpty()

    /** True when [options] have a city leg this lookup can answer for. */
    fun wants(options: List<SourcedJourneyOption>): Boolean = candidates(options).isNotEmpty()

    suspend fun lookup(options: List<SourcedJourneyOption>): Map<String, JourneyLegLive> {
        val legs = candidates(options)
        if (legs.isEmpty()) return emptyMap()
        val snapshots = coroutineScope {
            legs.map { it.pack }.distinct().map { pack ->
                async { pack to snapshot(pack) }
            }.awaitAll()
        }.toMap()
        val instant = now()
        return legs.mapNotNull { candidate ->
            val snapshot = snapshots[candidate.pack]?.takeIf { it.isFresh(instant) } ?: return@mapNotNull null
            liveOf(candidate, snapshot)?.let { TransferRiskCalculator.legKey(candidate.leg) to it }
        }.toMap()
    }

    private suspend fun snapshot(pack: SourceId): UrbanRealtimeSnapshot? = try {
        feeds[pack]?.snapshot()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun candidates(options: List<SourcedJourneyOption>): List<Candidate> {
        val instant = now()
        return options.asSequence()
            .flatMap { it.option.legs.asSequence() }
            .filter { leg ->
                leg.mode in URBAN_MODES &&
                    !leg.scheduledArrival.toInstant().isBefore(instant.minus(LOOKBACK)) &&
                    !leg.scheduledDeparture.toInstant().isAfter(instant.plus(LOOKAHEAD))
            }
            .mapNotNull(::candidate)
            .filter { it.pack in feeds }
            .distinctBy { TransferRiskCalculator.legKey(it.leg) }
            .toList()
    }

    private data class Candidate(val leg: JourneyLeg, val pack: SourceId, val tripId: String, val serviceDate: LocalDate)

    companion object {
        val LOOKBACK: Duration = Duration.ofHours(1)
        val LOOKAHEAD: Duration = Duration.ofHours(2)
        private val URBAN_MODES = setOf(LegMode.METRO, LegMode.TRAM, LegMode.FUNICULAR)

        /** `<pack>:<trip>:<yyyy-mm-dd>`: the trip id may itself contain colons, the pack id and the day never do. */
        private fun candidate(leg: JourneyLeg): Candidate? {
            val value = leg.runId?.value ?: return null
            val pack = value.substringBefore(':', "").takeIf(String::isNotEmpty) ?: return null
            val day = value.substringAfterLast(':', "")
            val trip = value.removePrefix("$pack:").removeSuffix(":$day").takeIf { it.isNotEmpty() && it != value } ?: return null
            val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return null
            return Candidate(leg, SourceId(pack), trip, date)
        }

        /** The stop id of a city stop of [pack] (`gtfs:<pack>:<stop>`), else null. */
        private fun stopOf(id: String, pack: SourceId): String? =
            id.removePrefix("gtfs:${pack.value}:").takeIf { it != id && it.isNotEmpty() }

        private fun liveOf(candidate: Candidate, snapshot: UrbanRealtimeSnapshot): JourneyLegLive? {
            val trip = snapshot.trip(candidate.tripId, candidate.serviceDate) ?: return null
            val leg = candidate.leg
            if (trip.cancelled) return JourneyLegLive(cancelled = true, sourceLabel = snapshot.feed.displayName, observedAt = trip.observedAt)
            val departure = stopOf(leg.origin.value, candidate.pack)?.let { trip.expectedAt(it, leg.scheduledDeparture, departure = true) }
            val arrival = stopOf(leg.destination.value, candidate.pack)?.let { trip.expectedAt(it, leg.scheduledArrival, departure = false) }
            val cancelled = departure?.cancelled == true || arrival?.cancelled == true
            val live = JourneyLegLive(
                expectedDeparture = departure?.expected?.toInstant(),
                expectedArrival = arrival?.expected?.toInstant(),
                cancelled = cancelled,
                sourceLabel = snapshot.feed.displayName,
                observedAt = trip.observedAt
            )
            return live.takeIf { it.hasData }
        }
    }
}
