package it.binario.app.data.bikesharing

import it.binario.app.data.core.GeoPoint
import it.binario.app.data.live.MutablePublicSourceClock
import it.binario.app.data.live.RecordingPublicSourceFetcher
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * contracts/shared-rules/v1/bike-sharing-v1.json: the systems serving a station, the sub-feeds allowed, the GBFS
 * documents read and what the section lists, as the iPhone's BikeSharingContractTests read them.
 */
class BikeSharingContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/bike-sharing-v1.json").readText(Charsets.UTF_8))
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)

    @Test fun `the systems serving a station in each build`() {
        for (case in rules.getJSONArray("serves").objects()) {
            val point = GeoPoint(case.getDouble("latitude"), case.getDouble("longitude"))
            val systems = BikeSharingSystems.forBuild(case.getBoolean("personal")).filter { it.serves(point) }
            assertEquals(case.getString("name"), case.getJSONArray("expected").strings(), systems.map { it.id.value })
        }
    }

    @Test fun `sub-feeds only over HTTPS on the auto-discovery host`() {
        for (case in rules.getJSONArray("sameHost").objects()) {
            assertEquals(case.getString("url"), case.getBoolean("expected"), BikeSharingService.sameHost(case.getString("url"), case.getString("discovery")))
        }
    }

    @Test fun `the GBFS documents read`() {
        for (case in rules.getJSONArray("feeds").objects()) {
            val expected = case.getJSONObject("expected").let { map -> map.keys().asSequence().associateWith(map::getString) }
            assertEquals(case.getString("name"), expected, GbfsParser.feeds(case.getString("json")))
        }
        for (case in rules.getJSONArray("vehicleTypes").objects()) {
            val expected = case.getJSONObject("expected").let { map -> map.keys().asSequence().associateWith { SharedVehicleKind.valueOf(map.getString(it)) } }
            assertEquals(expected, GbfsParser.vehicleTypes(case.getString("json")))
        }
        for (case in rules.getJSONArray("stations").objects()) {
            val types = GbfsParser.vehicleTypes(case.getString("types"))
            val expected = case.getJSONArray("expected").objects().map {
                GbfsStation(it.getString("id"), it.getString("name"), it.getDouble("latitude"), it.getDouble("longitude"),
                    it.getInt("bikes"), it.intOrNull("ebikes"), it.intOrNull("docks"), renting = true)
            }
            assertEquals(case.getString("name"), expected, GbfsParser.stations(case.getString("information"), case.getString("status"), types))
        }
        for (case in rules.getJSONArray("vehicles").objects()) {
            val types = GbfsParser.vehicleTypes(case.getString("types"))
            val expected = case.getJSONArray("expected").objects().map {
                GbfsVehicle(it.getDouble("latitude"), it.getDouble("longitude"), SharedVehicleKind.valueOf(it.getString("kind")), it.intOrNull("batteryPercent"))
            }
            assertEquals(case.getString("name"), expected, GbfsParser.vehicles(case.getString("json"), types))
        }
        for (case in rules.getJSONArray("lastUpdated").objects()) {
            val expected = if (case.isNull("expected")) null else Instant.ofEpochSecond(case.getLong("expected"))
            assertEquals(case.getString("json"), expected, GbfsParser.lastUpdated(case.getString("json")))
        }
        for (case in rules.getJSONArray("distances").objects()) {
            val from = case.getJSONObject("from")
            val to = case.getJSONObject("to")
            assertEquals(case.getInt("expectedMeters"),
                BikeSharingService.distance(GeoPoint(from.getDouble("latitude"), from.getDouble("longitude")), to.getDouble("latitude"), to.getDouble("longitude")))
        }
    }

    @Test fun `what the section lists near a station`() = runBlocking {
        for (case in rules.getJSONArray("nearby").objects()) {
            val documents = case.getJSONObject("documents")
            val fetcher = RecordingPublicSourceFetcher { url -> if (documents.has(url)) documents.getString(url) else throw IOException("404 $url") }
            val system = BikeSharingSystems.all.single { it.id.value == case.getString("system") }
            val service = BikeSharingService(listOf(system), fetcher, MutablePublicSourceClock(Instant.parse("2026-11-03T01:42:00Z")))
            val result = service.nearby(GeoPoint(case.getDouble("latitude"), case.getDouble("longitude"))) as BikeSharingResult.Loaded
            val nearby = result.systems.single()
            val expected = case.getJSONObject("expected")
            assertEquals(case.getString("name"), expected.getJSONArray("docks").objects().map {
                NearbyDock(it.getString("name"), it.getInt("distanceMeters"), it.getInt("bikes"), it.intOrNull("ebikes"), it.intOrNull("docks"))
            }, nearby.docks)
            assertEquals(case.getString("name"), expected.getJSONArray("vehicles").objects().map {
                NearbyVehicle(SharedVehicleKind.valueOf(it.getString("kind")), it.getInt("distanceMeters"), it.intOrNull("batteryPercent"))
            }, nearby.vehicles)
            assertEquals(Instant.ofEpochSecond(expected.getLong("observedAt")), nearby.observedAt)
            assertEquals(case.getJSONArray("asked").strings().toSet(), fetcher.urls.toSet())
        }
    }
}
