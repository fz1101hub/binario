package it.binario.app.data.planned

import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderError
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Official planned station board backed by RFI's public "Quadro orario".
 *
 * It intentionally exposes only scheduled departures. Expected times, live
 * platform and operational status remain null and can be overlaid by higher
 * authority realtime providers in StationBoardAggregateService.
 */
class RfiProgrammedStationBoardAdapter(
    private val fetcher: RfiProgrammedTimetableFetcher = HttpRfiProgrammedTimetableFetcher(),
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.of("Europe/Rome"),
    private val cacheTtl: Duration = Duration.ofHours(6)
) : StationBoardProvider {
    override val descriptor = ProviderDescriptor(
        id = SOURCE_ID,
        displayName = "RFI — Quadro orario programmato",
        capabilities = setOf(ProviderCapability.STATION_BOARD),
        coverage = CoverageDescriptor(
            countryCodes = setOf("IT"),
            description = "Partenze programmate ufficiali per gli hub RFI con Id PRM verificato."
        ),
        accessMode = AccessMode.OPEN_DIRECT,
        ratePolicy = RatePolicy(
            minimumRequestInterval = Duration.ofSeconds(2),
            defaultTimeToLive = cacheTtl
        ),
        schemaVersion = 1
    )

    private val parser = RfiProgrammedTimetableAdapter(
        fetcher = RfiProgrammedTimetableFetcher { "" },
        clock = clock
    )
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedTimetable>()

    override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
        if (request.kind != BoardKind.DEPARTURES) {
            return ProviderResult.Failure(
                ProviderError(
                    ProviderErrorKind.NOT_SUPPORTED,
                    "Il quadro orario programmato RFI importato espone soltanto le partenze."
                )
            )
        }

        val stationName = request.stationName?.trim().orEmpty()
        if (stationName.isBlank()) {
            return ProviderResult.Failure(
                ProviderError(
                    ProviderErrorKind.INVALID_REQUEST,
                    "Il quadro orario RFI richiede il nome della stazione."
                )
            )
        }

        val station = Station(
            id = request.stationId.value,
            name = stationName,
            city = stationName,
            railwayCode = request.railwayCode,
            aliases = request.stationAliases
        )
        val timetableId = RfiProgrammedStationIds.timetableId(station)
            ?: SupplementalRfiProgrammedStationIds.timetableId(station)
            ?: return ProviderResult.Failure(
                ProviderError(
                    ProviderErrorKind.NOT_SUPPORTED,
                    "Il quadro orario RFI non è ancora mappato per $stationName."
                )
            )

        val start = request.from.atZone(zone)
        val end = request.to.atZone(zone)
        val dates = generateSequence(start.toLocalDate()) { previous -> previous.plusDays(1) }
            .takeWhile { date -> !date.isAfter(end.toLocalDate()) }
            .toList()

        val parsed = when (val loaded = timetable(station, timetableId)) {
            is TimetableLoad.Success -> loaded.value
            is TimetableLoad.Failure -> {
                return ProviderResult.Failure(
                    ProviderError(loaded.kind, loaded.message, loaded.causeClass)
                )
            }
        }

        parsed.validity?.let { validity ->
            if (dates.none { it in validity.start..validity.end }) {
                return ProviderResult.Failure(
                    ProviderError(
                        ProviderErrorKind.NOT_FOUND,
                        "Il quadro orario RFI disponibile è valido dal ${validity.start} al ${validity.end}."
                    )
                )
            }
        }

        val entries = dates.asSequence()
            .filter { date -> parsed.validity?.let { date in it.start..it.end } ?: true }
            .flatMap { date ->
                parsed.services.asSequence()
                    .filter { parser.runsOnDate(it.periodicity, date) }
                    .mapNotNull { parsedService ->
                        val scheduledTime = parseTime(parsedService.service.scheduledDeparture) ?: return@mapNotNull null
                        val scheduled = ZonedDateTime.of(date, scheduledTime, zone)
                        if (scheduled.toInstant().isBefore(request.from) || scheduled.toInstant().isAfter(request.to)) {
                            return@mapNotNull null
                        }
                        val service = parsedService.service
                        StationBoardEntry(
                            runId = TrainRunId(
                                "rfi-programmed:${request.stationId.value}:$date:${service.number}:${service.scheduledDeparture}"
                            ),
                            scheduledTime = scheduled,
                            destinationOrOrigin = StationId("rfi-destination:${normalizeId(service.destination)}"),
                            destinationOrOriginLabel = service.destination,
                            publicNumber = service.number
                        )
                    }
            }
            .distinctBy { "${it.publicNumber}:${it.scheduledTime.toInstant()}:${it.destinationOrOrigin.value}" }
            .sortedBy { it.scheduledTime.toInstant() }
            .toList()

        val fetchedAt = clock.instant()
        val source = SourceRef(
            sourceId = SOURCE_ID,
            observedAt = null,
            fetchedAt = fetchedAt,
            expiresAt = fetchedAt.plus(cacheTtl),
            confidence = Confidence.INFRASTRUCTURE_PRIMARY,
            traceId = "rfi-programmed-board-${request.stationId.value}-${fetchedAt.toEpochMilli()}"
        )
        val board = StationBoard(
            stationId = request.stationId,
            kind = request.kind,
            from = request.from,
            to = request.to,
            entries = entries,
            snapshot = SnapshotMeta(
                schemaVersion = 1,
                generatedAt = fetchedAt,
                validUntil = fetchedAt.plus(cacheTtl),
                freshness = Freshness.AGING,
                completeness = Completeness.STATIC_ONLY,
                contributingSources = setOf(source)
            )
        )

        return ProviderResult.Success(
            value = board,
            meta = ProviderResponseMeta(
                providerId = SOURCE_ID,
                fetchedAt = fetchedAt,
                expiresAt = fetchedAt.plus(cacheTtl),
                completeness = Completeness.STATIC_ONLY
            )
        )
    }

    private suspend fun timetable(
        station: Station,
        timetableId: Int
    ): TimetableLoad {
        val key = station.id
        val now = clock.instant()
        val cached = mutex.withLock {
            cache[key]?.takeIf { now.isBefore(it.expiresAt) }?.value
        }
        if (cached != null) return TimetableLoad.Success(cached)

        val url = "https://prm.rfi.it/qo_prm/QO_Partenze_SiPMR.aspx?Id=$timetableId&lin=it&dalle=00.00&alle=23.59&ora=00.00"
        val html = try {
            fetcher.fetch(url)
        } catch (error: IOException) {
            return TimetableLoad.Failure(
                ProviderErrorKind.NETWORK,
                "Il quadro orario programmato RFI non è raggiungibile.",
                error::class.java.name
            )
        } catch (error: Exception) {
            return TimetableLoad.Failure(
                ProviderErrorKind.UPSTREAM_UNAVAILABLE,
                "Il quadro orario programmato RFI non è disponibile.",
                error::class.java.name
            )
        }

        val syntheticRoute = SavedRoute(
            id = "station-board-${station.id}",
            name = station.name,
            outwardOrigin = station,
            outwardDestination = Station("station-board-destination", "Destinazione", "")
        )
        val parsed = try {
            parser.parseTimetable(html, station, syntheticRoute, Direction.OUTBOUND)
        } catch (error: Exception) {
            return TimetableLoad.Failure(
                ProviderErrorKind.PARSE_ERROR,
                "Il quadro orario programmato RFI non è leggibile.",
                error::class.java.name
            )
        }

        mutex.withLock {
            cache[key] = CachedTimetable(parsed, now.plus(cacheTtl))
        }
        return TimetableLoad.Success(parsed)
    }

    private fun parseTime(value: String): LocalTime? = runCatching {
        val parts = value.split(':')
        LocalTime.of(parts[0].toInt(), parts[1].toInt())
    }.getOrNull()

    private fun normalizeId(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "unknown" }

    private sealed interface TimetableLoad {
        data class Success(val value: RfiProgrammedTimetableAdapter.ParsedTimetable) : TimetableLoad
        data class Failure(
            val kind: ProviderErrorKind,
            val message: String,
            val causeClass: String? = null
        ) : TimetableLoad
    }

    private data class CachedTimetable(
        val value: RfiProgrammedTimetableAdapter.ParsedTimetable,
        val expiresAt: Instant
    )

    private companion object {
        val SOURCE_ID = SourceId("rfi-programmed")
    }
}
