package it.binario.app.data.planned

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.StationId
import it.binario.app.data.isNamedBy
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardRequest
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-rules/v1/rfi-programmed-v1.json, read by the iPhone too (RfiProgrammedContractTests.swift): the
 * reading of RFI's periodicity, the day a row leaves, stop names, and the trains of a route and of a hub's board read
 * from the synthetic pages of this module's tests.
 */
class RfiProgrammedContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/rfi-programmed-v1.json").readText(Charsets.UTF_8))
    private val zone = ZoneId.of("Europe/Rome")
    private val minute = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val clock = Clock.fixed(Instant.parse("2026-09-04T06:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `the periodicity is read as the cases say`() {
        val cases = rules.getJSONArray("periodicity")
        assertTrue(cases.length() > 200)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val text = case.getString("text")
            val date = case.getString("date")
            assertEquals("$text $date", case.getBoolean("runs"), ItalianRailServiceCalendar.runsOnDate(text, LocalDate.parse(date)))
        }
    }

    @Test
    fun `a row earlier than the requested time is the next day's`() {
        val cases = rules.getJSONArray("departureDays")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val expected = if (case.isNull("expected")) null else LocalDate.parse(case.getString("expected"))
            assertEquals(
                case.toString(), expected,
                ItalianRailServiceCalendar.rfiDepartureDate(case.getInt("row"), LocalDate.parse(case.getString("serviceDate")), case.getInt("requested"))
            )
        }
    }

    @Test
    fun `a published name names the station as the cases say`() {
        val cases = rules.getJSONArray("names")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("station")
            val aliases = case.getJSONArray("aliases").let { array -> List(array.length()) { array.getString(it) } }
            val station = Station(id = name, name = name, city = name.substringBefore(' '), aliases = aliases)
            assertEquals("$name ← ${case.getString("published")}", case.getBoolean("matches"), station.isNamedBy(case.getString("published")))
        }
    }

    @Test
    fun `the trains of a route are the cases'`() = runBlocking {
        val cases = rules.getJSONArray("routes")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val html = page(case.getString("page"), case.getString("periodicity"))
            val urls = mutableListOf<String>()
            val adapter = RfiProgrammedTimetableAdapter(fetcher = RfiProgrammedTimetableFetcher { url -> urls += url; html }, clock = clock)
            val origin = station(case.getJSONObject("origin"))
            val destination = station(case.getJSONObject("destination"))
            val requested = LocalDateTime.parse(case.getString("departure"))
            val result = adapter.loadRoute(
                SavedRoute("contract-$index", name, origin, destination), Direction.OUTBOUND,
                JourneyQuery(requested.toLocalDate(), requested.toLocalTime())
            )
            val trains = when (result) {
                is DataResult.Success -> result.snapshot.services.map { service ->
                    listOf(
                        service.number, service.scheduledDepartureDateTime?.format(minute).orEmpty(), service.operator.name,
                        service.trainCategory.name, service.stops.joinToString("|") { it.station }
                    )
                }
                is DataResult.Failure -> emptyList()
            }
            val expected = case.getJSONArray("expected").let { array ->
                List(array.length()) { item ->
                    val train = array.getJSONObject(item)
                    val stops = train.getJSONArray("stops").let { list -> List(list.length()) { list.getString(it) } }
                    listOf(train.getString("number"), train.getString("departure"), train.getString("operator"),
                        train.getString("category"), stops.joinToString("|"))
                }
            }
            assertEquals(name, expected, trains)
            assertTrue("$name: $urls", urls.firstOrNull()?.contains(case.getString("url")) == true)
        }
    }

    @Test
    fun `the board of a hub lists the cases' departures`() = runBlocking {
        val cases = rules.getJSONArray("boards")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val html = page(case.getString("page"), case.getString("periodicity"))
            val urls = mutableListOf<String>()
            val adapter = RfiProgrammedStationBoardAdapter(fetcher = RfiProgrammedTimetableFetcher { url -> urls += url; html }, clock = clock)
            val station = station(case.getJSONObject("station"))
            val from = LocalDateTime.parse(case.getString("from")).atZone(zone).toInstant()
            val result = adapter.getStationBoard(
                StationBoardRequest(
                    stationId = StationId(station.id),
                    stationName = station.name,
                    stationAliases = station.aliases,
                    railwayCode = station.railwayCode,
                    from = from,
                    to = from.plusSeconds(case.getLong("hours") * 3_600L),
                    kind = BoardKind.DEPARTURES
                )
            )
            val listed = (result as? ProviderResult.Success)?.value?.entries.orEmpty().map { entry ->
                listOf(entry.publicNumber.orEmpty(), entry.scheduledTime.withZoneSameInstant(zone).format(minute), entry.destinationOrOriginLabel.orEmpty())
            }
            val expected = case.getJSONArray("expected").let { array ->
                List(array.length()) { item ->
                    val train = array.getJSONObject(item)
                    listOf(train.getString("number"), train.getString("departure"), train.getString("destination"))
                }
            }
            assertEquals(name, expected, listed)
            assertTrue("$name: $urls", urls.firstOrNull()?.contains(case.getString("url")) == true)
        }
    }

    private fun page(name: String, periodicity: String): String =
        rules.getJSONObject("pages").getString(name).replace("{periodicity}", periodicity)

    private fun station(json: JSONObject): Station = Station(
        id = json.getString("id"),
        name = json.getString("name"),
        city = json.getString("name"),
        railwayCode = if (json.isNull("railwayCode")) null else json.getString("railwayCode"),
        aliases = json.getJSONArray("aliases").let { array -> List(array.length()) { array.getString(it) } }
    )
}
