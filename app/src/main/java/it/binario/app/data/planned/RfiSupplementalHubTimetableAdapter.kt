package it.binario.app.data.planned

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailTime
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.TrainDataSource
import it.binario.app.data.isNamedBy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * Estensione conservativa del quadro orario RFI per hub il cui Id PRM è stato
 * verificato separatamente. Riusa integralmente il parser del datasource RFI
 * principale: qui vivono soltanto mapping e orchestrazione aggiuntiva.
 */
class RfiSupplementalHubTimetableAdapter(
    private val fetcher: RfiProgrammedTimetableFetcher = HttpRfiProgrammedTimetableFetcher(),
    private val clock: Clock = Clock.system(RailTime.defaultZone),
    private val cacheTtlMillis: Long = CACHE_TTL_MILLIS,
    private val cacheMaxEntries: Int = 8,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parsingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onParsing: (() -> Unit)? = null
) : TrainDataSource {
    private val parser = RfiProgrammedTimetableAdapter(
        fetcher = RfiProgrammedTimetableFetcher { "" },
        clock = clock
    )
    private val cache = BoundedSingleFlightCache<RfiProgrammedTimetableAdapter.ParsedTimetable>(
        maxEntries = cacheMaxEntries,
        ttlMillis = cacheTtlMillis,
        clockMillis = clock::millis
    )

    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult {
        val now = clock.instant().atZone(clock.zone)
        return loadRoute(
            route,
            direction,
            JourneyQuery(now.toLocalDate(), now.toLocalTime().withSecond(0).withNano(0))
        )
    }

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val origin = route.origin(direction)
        val destination = route.destination(direction)
        val timetableId = SupplementalRfiProgrammedStationIds.timetableId(origin)
            ?: return DataResult.Failure(unmappedDirectionMessage(origin, destination))
        val cacheKey = "${route.id}:${direction.name}:$timetableId"
        val cached = try {
            cache.getOrLoad(cacheKey) {
                val url = "https://prm.rfi.it/qo_prm/QO_Partenze_SiPMR.aspx?Id=$timetableId&lin=it&dalle=00.00&alle=23.59&ora=00.00"
                val html = try {
                    withContext(ioDispatcher) { fetcher.fetch(url) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw SupplementalLoadFailure(
                        "Il quadro orario RFI supplementare non è raggiungibile: ${error.message.orEmpty()}.",
                        error
                    )
                }
                try {
                    withContext(parsingDispatcher) {
                        onParsing?.invoke()
                        parser.parseTimetable(html, origin, route, direction)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw SupplementalLoadFailure(
                        "Il quadro orario RFI supplementare non è leggibile: ${error.message.orEmpty()}.",
                        error
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SupplementalLoadFailure) {
            return DataResult.Failure(error.message.orEmpty())
        }
        val parsed = cached.value

        parsed.validity?.let { validity ->
            if (query.serviceDate !in validity.start..validity.end) {
                return DataResult.Failure(
                    "Il quadro orario RFI disponibile è valido dal ${validity.start} al ${validity.end}, non per ${query.serviceDate}."
                )
            }
        }

        val services = withContext(parsingDispatcher) {
            val requestedMinutes = query.departureTime.hour * 60 + query.departureTime.minute
            parsed.services.asSequence()
                .filter { ItalianRailServiceCalendar.runsOnDate(it.periodicity, query.serviceDate) }
                .filter { timeMinutes(it.service.scheduledDeparture) >= requestedMinutes }
                .filter { parsedService ->
                    parsedService.service.stops.drop(1).any { destination.isNamedBy(it.station) } ||
                        destination.isNamedBy(parsedService.service.destination)
                }
                .map { parsedService ->
                    val targetIndex = parsedService.service.stops.indexOfFirst { destination.isNamedBy(it.station) }
                    parsedService.service.copy(
                        stops = if (targetIndex >= 1) parsedService.service.stops.take(targetIndex + 1) else parsedService.service.stops,
                        scheduledDepartureDateTime = LocalDateTime.of(
                            query.serviceDate,
                            parseTime(parsedService.service.scheduledDeparture) ?: query.departureTime
                        )
                    )
                }
                .sortedBy { it.scheduledDepartureDateTime }
                .take(24)
                .toList()
        }

        if (services.isEmpty()) {
            return DataResult.Failure(
                "RFI non mostra corse programmate dirette da ${origin.name} verso ${destination.name} dopo ${query.departureTime}."
            )
        }

        return DataResult.Success(
            RouteSnapshot(
                route = route,
                direction = direction,
                services = services,
                providerName = "RFI — Quadro orario programmato",
                retrievedAtEpochMillis = cached.fetchedAtEpochMillis,
                expiresAtEpochMillis = cached.expiresAtEpochMillis,
                freshness = Freshness.AGING,
                sourceObservedAtEpochMillis = cached.fetchedAtEpochMillis,
                confidence = DataConfidence.INFRASTRUCTURE_PRIMARY,
                notice = "Orario programmato ufficiale RFI; hub supplementare verificato, con stato operativo riconciliato separatamente."
            )
        )
    }


    private fun parseTime(value: String): LocalTime? = runCatching {
        val parts = value.split(':')
        LocalTime.of(parts[0].toInt(), parts[1].toInt())
    }.getOrNull()

    private fun timeMinutes(value: String): Int = parseTime(value)?.let { it.hour * 60 + it.minute } ?: Int.MAX_VALUE

    private fun unmappedDirectionMessage(origin: Station, destination: Station): String =
        if (SupplementalRfiProgrammedStationIds.timetableId(destination) != null) {
            "Il quadro RFI supplementare copre le partenze dall’hub ${destination.name}, non la direzione che parte da ${origin.name}."
        } else {
            "Nessun hub RFI supplementare mappato per ${origin.name}."
        }

    private class SupplementalLoadFailure(message: String, cause: Throwable) : Exception(message, cause)

    internal suspend fun cachedTimetableCountForTest(): Int = cache.sizeForTest()

    private companion object {
        const val CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L
    }
}

internal object SupplementalRfiProgrammedStationIds {
    private val byName = mapOf(
        key("Reggio Calabria Centrale") to 116,
        key("Reggio di Calabria Centrale") to 116,
        key("Ancona") to 408,
        key("Ancona (Tutte le stazioni)") to 408,
        key("Palermo Centrale") to 1878,
        key("Catania Centrale") to 959,
        key("Lecce") to 1436
    )

    private val byRailwayCode = mapOf(
        "S11781" to 116, // Reggio di Calabria Centrale
        "S07113" to 408, // Ancona
        "S12002" to 1878, // Palermo Centrale
        "S12332" to 959, // Catania Centrale
        "S11145" to 1436 // Lecce
    )

    fun timetableId(station: Station): Int? =
        station.railwayCode?.let(byRailwayCode::get)
            ?: (listOf(station.name) + station.aliases).firstNotNullOfOrNull { byName[key(it)] }

    private fun key(value: String): String = value
        .uppercase(Locale.ROOT)
        .replace("S. ", "SAN ")
        .replace(Regex("[^A-Z0-9]"), "")
}
