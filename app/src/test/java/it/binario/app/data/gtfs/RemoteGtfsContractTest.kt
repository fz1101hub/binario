package it.binario.app.data.gtfs

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.SourceId
import it.binario.app.data.sources.InternationalSourceCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-rules/v1/remote-gtfs-v1.json, read by the iPhone (RemoteGTFSContractTests.swift) and by
 * tests/test_remote_gtfs_rules.py too: which catalogue stations a stop of the community archive names (`stationMatches`,
 * through the route search) and the operator's trains a route finds in it (`timetables`).
 */
class RemoteGtfsContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/remote-gtfs-v1.json").readText(Charsets.UTF_8))
    private val feed = RemoteGtfsFallbackDataSource.EUROPEAN_SLEEPER
    private val source = requireNotNull(InternationalSourceCatalog.byId(SourceId(feed.id)))

    @Test
    fun `the operator's trains of the archive answer the routes of the cases`() = runBlocking {
        val cases = rules.getJSONArray("timetables")
        assertTrue(cases.length() >= 4)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val stations = case.getJSONArray("stations").let { array -> List(array.length()) { station(array.getJSONObject(it)) } }
            val origin = stations.single { it.id == case.getString("origin") }
            val destination = stations.single { it.id == case.getString("destination") }
            val requested = LocalDateTime.parse(case.getString("departure"))
            val query = JourneyQuery(requested.toLocalDate(), requested.toLocalTime())
            val zip = Base64.getDecoder().decode(case.getString("zipBase64"))
            val result = adapter(zip, requested).loadRoute(SavedRoute("contract", name, origin, destination), Direction.OUTBOUND, query)
            val trains = when (result) {
                is DataResult.Success -> result.snapshot.services.map { service ->
                    val day = service.scheduledDepartureDateTime?.toLocalDate() ?: query.serviceDate
                    listOf(service.number, "${day}T${service.scheduledDeparture}", service.operatorDisplayName.orEmpty())
                }
                is DataResult.Failure -> emptyList()
            }
            val expected = case.getJSONArray("expected").let { array ->
                List(array.length()) { item ->
                    val train = array.getJSONObject(item)
                    listOf(train.getString("number"), train.getString("departure"), train.getString("operator"))
                }
            }
            assertEquals(name, expected, trains)
        }
    }

    @Test
    fun `a stop names the stations of its candidate names`() = runBlocking {
        val cases = rules.getJSONArray("stationMatches")
        val requested = LocalDateTime.of(2026, 9, 10, 8, 0)
        val other = Station("zeta", "Zeta Testpunkt", "Zeta")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val stop = case.getJSONObject("stop").getString("name")
            val named = case.getJSONArray("named").let { array -> List(array.length()) { array.getString(it) }.toSet() }
            val zip = singleTrainArchive(stop, other.name)
            val stations = case.getJSONArray("stations")
            for (item in 0 until stations.length()) {
                val station = station(stations.getJSONObject(item))
                val result = adapter(zip, requested).loadRoute(
                    SavedRoute("match", "match", station, other), Direction.OUTBOUND,
                    JourneyQuery(requested.toLocalDate(), requested.toLocalTime())
                )
                assertEquals("${case.getString("name")}: ${station.id}", station.id in named, result is DataResult.Success)
            }
        }
    }

    @Test
    fun `the limits of a compact index are the shared archive limits`() {
        val archive = it.binario.app.shared.SharedData.RemoteGtfs.ARCHIVE
        val limits = GtfsCompactIndexLimits()
        assertEquals(archive.maximumEntries, limits.maxEntries)
        assertEquals(archive.maximumEntryBytes.toLong(), limits.maxEntryBytes)
        assertEquals(archive.maximumTotalUncompressedBytes.toLong(), limits.maxTotalUncompressedBytes)
        assertEquals(archive.maximumCsvLineLength, limits.maxCsvLineLength)
        assertEquals(archive.maximumAgencies, limits.maxAgencies)
        assertEquals(archive.maximumStops, limits.maxStops)
        assertEquals(archive.maximumRoutes, limits.maxRoutes)
        assertEquals(archive.maximumTrips, limits.maxTrips)
        assertEquals(archive.maximumCalendars, limits.maxCalendars)
        assertEquals(archive.maximumCalendarDateRows, limits.maxCalendarDateRows)
        assertEquals(archive.maximumStopTimeRows, limits.maxStopTimeRows)
    }

    private fun adapter(zip: ByteArray, requested: LocalDateTime) = RemoteGtfsFallbackDataSource(
        source = source,
        parserSourceId = SourceId(feed.pack),
        operatorScope = feed.operatorScope,
        downloader = GtfsArtifactSource { _, _ -> GtfsDownloadSession(ByteArrayInputStream(zip)) },
        clock = Clock.fixed(requested.toInstant(ZoneOffset.ofHours(2)), ZoneOffset.UTC),
        notBefore = LocalDate.parse(feed.notBefore)
    )

    private fun station(json: JSONObject): Station = Station(
        id = json.getString("id"),
        name = json.getString("name"),
        city = json.getString("name"),
        railwayCode = if (json.isNull("railwayCode")) null else json.getString("railwayCode"),
        trenitaliaLocationId = if (json.isNull("trenitaliaLocationId")) null else json.getString("trenitaliaLocationId"),
        aliases = json.getJSONArray("aliases").let { array -> List(array.length()) { array.getString(it) } },
        latitude = if (json.isNull("latitude")) null else json.getDouble("latitude"),
        longitude = if (json.isNull("longitude")) null else json.getDouble("longitude")
    )

    /** One European Sleeper train from a stop called [from] to one called [to]. */
    private fun singleTrainArchive(from: String, to: String): ByteArray {
        val files = linkedMapOf(
            "agency.txt" to "agency_id,agency_name\nes,European Sleeper\n",
            "stops.txt" to "stop_id,stop_name\na,\"$from\"\nz,\"$to\"\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type\nr1,es,ES,Contract,2\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_short_name\nr1,daily,t1,ES1\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nt1,09:00:00,09:00:00,a,1\nt1,10:00:00,10:00:00,z,2\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\ndaily,1,1,1,1,1,1,1,20260901,20261231\n"
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
