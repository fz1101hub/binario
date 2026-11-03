package it.binario.app.data.journey

import it.binario.app.data.Station
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsRouteAdapter
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.provider.JourneySearchRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Hand-built GTFS packs for the journey planner tests. Times are "HH:MM" (GTFS, 24:00+ allowed). */
internal object JourneyFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Rome")
    /** A Monday. */
    val monday: LocalDate = LocalDate.of(2026, 9, 7)

    val alfa = Station("curated:A", "Alfa", "Alfa", railwayCode = "S90001")
    val beta = Station("curated:B", "Beta", "Beta", railwayCode = "S90002")
    val gamma = Station("curated:C", "Gamma", "Gamma", railwayCode = "S90003")
    val delta = Station("curated:D", "Delta", "Delta", railwayCode = "S90004")
    val epsilon = Station("curated:E", "Epsilon", "Epsilon", railwayCode = "S90005")
    val zeta = Station("curated:Z", "Zeta", "Zeta", railwayCode = "S90006")
    val catalogue = listOf(alfa, beta, gamma, delta, epsilon, zeta)

    data class Trip(
        val id: String,
        val number: String,
        val stops: List<Pair<String, String>>,
        val service: String = "ALL",
        val route: String = "R",
        val noPickup: Set<String> = emptySet(),
        val noDropOff: Set<String> = emptySet(),
        /** GTFS block_id (blank: none). */
        val block: String = "",
        /** GTFS wheelchair_accessible as published ("1", "2", "0" or blank). */
        val wheelchair: String = ""
    )

    /** Stop ids are the station names' initials; [stopNames] can override them (platforms, other spellings). */
    fun feed(
        trips: List<Trip>,
        stopNames: Map<String, String> = mapOf("A" to "Alfa", "B" to "Beta", "C" to "Gamma", "D" to "Delta", "E" to "Epsilon", "Z" to "Zeta"),
        parents: Map<String, String> = emptyMap(),
        /** wheelchair_boarding per stop id as published (blank when missing). */
        stopWheelchair: Map<String, String> = emptyMap(),
        calendar: String = "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
            "ALL,1,1,1,1,1,1,1,20260101,20261231\nWEEKDAY,1,1,1,1,1,0,0,20260101,20261231\n",
        calendarDates: String = "service_id,date,exception_type\n",
        routes: String = "route_id,agency_id,route_short_name,route_long_name,route_type\nR,T,RE11,Linea di prova,2\nBUS,T,B1,Bus sostitutivo,3\n"
    ): ByteArray {
        val stopTimes = buildString {
            append("trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n")
            trips.forEach { trip ->
                trip.stops.forEachIndexed { index, (stop, time) ->
                    val pickup = if (stop in trip.noPickup) "1" else "0"
                    val dropOff = if (stop in trip.noDropOff) "1" else "0"
                    append("${trip.id},$time:00,$time:00,$stop,${index + 1},$pickup,$dropOff\n")
                }
            }
        }
        val content = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nT,Ferrovie di Prova,https://example.test,Europe/Rome\n",
            "stops.txt" to "stop_id,stop_name,parent_station,wheelchair_boarding\n" +
                stopNames.entries.joinToString("") { (id, name) -> "$id,$name,${parents[id].orEmpty()},${stopWheelchair[id].orEmpty()}\n" },
            "routes.txt" to routes,
            "trips.txt" to "route_id,service_id,trip_id,trip_short_name,block_id,wheelchair_accessible\n" +
                trips.joinToString("") { "${it.route},${it.service},${it.id},${it.number},${it.block},${it.wheelchair}\n" },
            "stop_times.txt" to stopTimes,
            "calendar.txt" to calendar,
            "calendar_dates.txt" to calendarDates
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            content.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun provider(
        feeds: Map<EmbeddedGtfsPack, ByteArray>,
        settings: GtfsJourneyPlannerSettings = GtfsJourneyPlannerSettings(largeHubRailwayCodes = emptySet()),
        stations: List<Station> = catalogue
    ): EmbeddedGtfsJourneyProvider {
        val byPath = feeds.mapKeys { it.key.assetPath }
        val packs = feeds.keys.toList()
        return EmbeddedGtfsJourneyProvider(
            adapter = EmbeddedGtfsRouteAdapter(openAsset = { ByteArrayInputStream(byPath.getValue(it)) }, packs = packs, indexCacheCapacity = 1),
            packs = packs,
            loadStations = { stations },
            settings = settings,
            clock = Clock.fixed(Instant.parse("2026-09-01T06:00:00Z"), zone)
        )
    }

    fun pack(name: String = "lombardia-trenord-gtfs", lastServiceDate: LocalDate? = null) =
        EmbeddedGtfsPack(SourceId(name), "gtfs/$name.zip", lastServiceDate)

    fun request(
        from: Station,
        to: Station,
        date: LocalDate = monday,
        time: String = "07:30",
        maxTransfers: Int = 2,
        maxResults: Int = 6,
        preferences: JourneyPreferences? = null
    ) =
        JourneySearchRequest(
            origin = StationId(from.id),
            destination = StationId(to.id),
            departureAt = ZonedDateTime.of(date, LocalTime.parse(time), zone),
            maxTransfers = maxTransfers,
            maxResults = maxResults,
            preferences = preferences
        )
}
