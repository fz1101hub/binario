package it.binario.app.data.journey

import it.binario.app.TestAssets
import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.GtfsStationCodeIndex
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderResult
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner on the bundled packs, checked for journeys that cannot be ridden: the cases of the November 2026 audit
 * (a "stay on board" through two trains that only share the digits of their number, night trains cut by the horizon,
 * nights in a station, walks between two names of one station) and a sweep of searches between border and hub
 * stations where every option must be physically possible. A pack refresh may change the trains, never these rules.
 */
class EmbeddedGtfsJourneyPlausibilityTest {
    private val openAsset = { path: String -> TestAssets.open(path) }
    private val stations: List<Station> by lazy { EmbeddedCuratedRailStationCatalog(openAsset).load() }
    private val byId: Map<String, Station> by lazy { stations.associateBy(Station::id) }
    private val zone = ZoneId.of("Europe/Rome")
    /** A Tuesday inside the validity of every bundled pack. */
    private val tuesday = LocalDate.of(2026, 11, 10)
    private val provider by lazy { EmbeddedGtfsJourneyProvider.create(openAsset) }

    private fun station(name: String): Station = stations.firstOrNull { it.name == name } ?: stations.first { it.name.equals(name, ignoreCase = true) }

    private fun search(from: String, to: String, time: String): List<JourneyOption> = runBlocking {
        val result = provider.searchJourneys(
            JourneySearchRequest(StationId(station(from).id), StationId(station(to).id), ZonedDateTime.of(tuesday, LocalTime.parse(time), zone))
        )
        when (result) {
            is ProviderResult.Success -> result.value
            is ProviderResult.Partial -> result.value
            else -> throw AssertionError("$from → $to $time: $result")
        }
    }

    private fun JourneyOption.summary(): String = legs.joinToString(" | ") { leg ->
        "${leg.lineLabel ?: leg.mode} ${leg.publicNumber.orEmpty()} ${leg.originLabel} ${leg.scheduledDeparture.toLocalDateTime()}-" +
            "${leg.scheduledArrival.toLocalDateTime()} ${leg.destinationLabel}" +
            leg.continuations.joinToString("") { " [stay ${it.lineLabel.orEmpty()} ${it.publicNumber.orEmpty()} @${it.stationLabel}]" }
    }

    /** The waits between two vehicles of an option, a walk between them included, in minutes. */
    private fun JourneyOption.waits(): List<Long> {
        val vehicles = legs.filter { it.mode != LegMode.WALK }
        return vehicles.zipWithNext { arriving, leaving -> Duration.between(arriving.scheduledArrival, leaving.scheduledDeparture).toMinutes() }
    }

    @Test fun `Milano to Locarno and Domodossola never stay on board of the Centovallina`() {
        for ((to, time) in listOf("Locarno" to "09:30", "Domodossola" to "09:30", "Zürich HB" to "06:00")) {
            val found = search("Milano Centrale", to, time)
            assertTrue("Milano → $to $time: nothing found", found.isNotEmpty())
            for (option in found) {
                assertTrue("Milano → $to: ${option.summary()}", option.legs.none { leg -> leg.continuations.any { it.lineLabel == "72" } })
            }
        }
        // EC 41 reaches Locarno only with a change: it never leaves Milano at 10:05 to arrive at 10:20.
        val locarno = search("Milano Centrale", "Locarno", "09:30")
        assertTrue(locarno.joinToString("\n") { it.summary() }, locarno.none { option -> Duration.between(option.departure, option.arrival).toMinutes() < 60 })
    }

    @Test fun `Milano to Palermo finds the night trains at noon and the direct one in the evening`() {
        val noon = search("Milano Centrale", "Palermo Centrale", "12:00")
        assertTrue("no option at noon", noon.isNotEmpty())
        assertTrue(noon.all { it.arrival.toLocalDate() == tuesday.plusDays(1) })
        val evening = search("Milano Centrale", "Palermo Centrale", "18:00")
        val direct = evening.filter { it.transfers == 0 }
        assertEquals(evening.joinToString("\n") { it.summary() }, listOf("1963"), direct.map { it.legs.single().publicNumber })
    }

    @Test fun `no night in a station when the first train of the morning arrives soon after`() {
        val lodi = search("Lodi", "Lecco", "23:30")
        assertTrue(lodi.isNotEmpty())
        assertTrue(lodi.joinToString("\n") { it.summary() }, lodi.all { option -> option.waits().all { it <= 120 } })
        assertTrue(lodi.none { it.lastOfServiceDay })
        val domodossola = search("Domodossola", "Bolzano Bozen", "07:00")
        assertTrue(domodossola.joinToString("\n") { it.summary() }, domodossola.all { option -> option.waits().all { it <= 120 } })
        val salerno = search("Milano Centrale", "Salerno", "18:00")
        assertTrue(salerno.joinToString("\n") { it.summary() }, salerno.all { option -> option.waits().all { it <= 120 } })
    }

    @Test fun `Verona Porta Nuova is one station for every pack`() {
        val bolzano = search("Verona Porta Nuova", "Bolzano Bozen", "07:00")
        assertTrue(bolzano.isNotEmpty())
        assertTrue(bolzano.joinToString("\n") { it.summary() }, bolzano.none { option -> option.legs.any { it.mode == LegMode.WALK } })
        assertTrue(bolzano.all { option -> option.legs.first().originLabel == "Verona Porta Nuova" })
        // A change at Verona Porta Nuova keeps the large hub's ten minutes.
        for (option in search("Milano Centrale", "Bologna Centrale", "06:00") + search("Milano Centrale", "Bolzano Bozen", "06:00")) {
            val vehicles = option.legs.filter { it.mode != LegMode.WALK }
            vehicles.zipWithNext { arriving, leaving ->
                if (arriving.destinationLabel?.startsWith("Verona") == true || leaving.originLabel?.startsWith("Verona") == true) {
                    assertTrue(option.summary(), Duration.between(arriving.scheduledArrival, leaving.scheduledDeparture).toMinutes() >= 10)
                }
            }
        }
    }

    /**
     * Every option of searches between border and hub stations: times never go back, no option or leg is faster than
     * 250 km/h in a straight line, a "stay on board" note is on the way of its leg, a change at one station keeps its
     * change time and no walk joins a station to itself.
     */
    @Test fun `every option between border and hub stations can be ridden`() {
        val names = listOf(
            "Milano Centrale", "Domodossola", "Locarno", "Lugano", "Bolzano Bozen", "Brennero Brenner", "Innsbruck Hbf.",
            "Verona Porta Nuova", "Trento", "Venezia S. Lucia"
        )
        val hubs = GtfsJourneyPlannerSettings.DEFAULT_LARGE_HUB_RAILWAY_CODES
        val problems = ArrayList<String>()
        var searches = 0
        var checked = 0
        fun km(first: Station?, second: Station?): Double? {
            val a = first?.takeIf { it.latitude != null && it.longitude != null } ?: return null
            val b = second?.takeIf { it.latitude != null && it.longitude != null } ?: return null
            return GtfsStationCodeIndex.distanceKm(a.latitude!!, a.longitude!!, b.latitude!!, b.longitude!!)
        }
        fun tooFast(kilometres: Double?, minutes: Long): Boolean = kilometres != null && kilometres > 10 && kilometres / (minutes.coerceAtLeast(1) / 60.0) > 250
        for (from in names) for (to in names) {
            if (from == to) continue
            for (time in listOf("07:00", "16:00")) {
                searches += 1
                for (option in search(from, to, time)) {
                    checked += 1
                    val label = "$from → $to $time: ${option.summary()}"
                    if (tooFast(km(station(from), station(to)), Duration.between(option.departure, option.arrival).toMinutes())) problems += "too fast $label"
                    option.legs.forEach { leg ->
                        if (leg.scheduledArrival.isBefore(leg.scheduledDeparture)) problems += "leg back in time $label"
                        if (leg.mode == LegMode.WALK && (leg.origin == leg.destination || leg.originLabel == leg.destinationLabel)) problems += "walk to itself $label"
                        val minutes = Duration.between(leg.scheduledDeparture, leg.scheduledArrival).toMinutes()
                        val origin = byId[leg.origin.value]
                        val destination = byId[leg.destination.value]
                        if (leg.mode != LegMode.WALK && tooFast(km(origin, destination), minutes)) problems += "leg too fast $label"
                        // Staying on board through a station off the leg's way would take longer than the leg does.
                        for (continuation in leg.continuations) {
                            val via = byId[continuation.station.value]
                            val detour = km(origin, via)?.let { first -> km(via, destination)?.let { first + it } }
                            if (tooFast(detour, minutes)) problems += "stay on board off the way $label"
                        }
                    }
                    option.legs.zipWithNext { first, second ->
                        if (second.scheduledDeparture.isBefore(first.scheduledArrival)) problems += "change back in time $label"
                    }
                    option.legs.zipWithNext { arriving, leaving ->
                        if (arriving.mode == LegMode.WALK || leaving.mode == LegMode.WALK) return@zipWithNext
                        val at = byId[arriving.destination.value] ?: return@zipWithNext
                        if (arriving.destination != leaving.origin) return@zipWithNext
                        val needed = if (at.railwayCode in hubs) 10 else 5
                        if (Duration.between(arriving.scheduledArrival, leaving.scheduledDeparture).toMinutes() < needed) problems += "short change at ${at.name} $label"
                    }
                }
            }
        }
        println("JOURNEY_PLAUSIBILITY searches=$searches options=$checked problems=${problems.size}")
        assertTrue(problems.take(20).joinToString("\n"), problems.isEmpty())
        assertTrue(checked > searches)
    }
}
