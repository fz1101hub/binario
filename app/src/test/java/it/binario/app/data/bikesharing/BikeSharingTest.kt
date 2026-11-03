package it.binario.app.data.bikesharing

import it.binario.app.data.core.GeoPoint
import it.binario.app.data.live.MutablePublicSourceClock
import it.binario.app.data.live.RecordingPublicSourceFetcher
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GBFS documents trimmed from the feeds read by the discovery workflow (run 41561372909, 3 November 2026): BikeMi
 * (GBFS 2.3, stations with vehicle types), nextbike (docked bikes listed in free_bike_status), Bird Rome (scooters
 * with `current_fuel_percent` as a fraction).
 */
class BikeSharingTest {
    private val now = Instant.parse("2026-11-03T01:42:00Z")
    /** Between the two BikeMi stations of the sample (Corso Italia / Santa Sofia and Commenda). */
    private val nearSantaSofia = GeoPoint(45.4560, 9.1890)

    private val bikemiDocs = mapOf(
        "https://gbfs.urbansharing.com/bikemi.com/gbfs.json" to """{"last_updated":1793670090,"ttl":15,"version":"2.3","data":{"it":{"feeds":[
            {"name":"system_information","url":"https://gbfs.urbansharing.com/bikemi.com/system_information.json"},
            {"name":"station_information","url":"https://gbfs.urbansharing.com/bikemi.com/station_information.json"},
            {"name":"station_status","url":"https://gbfs.urbansharing.com/bikemi.com/station_status.json"},
            {"name":"vehicle_types","url":"https://gbfs.urbansharing.com/bikemi.com/vehicle_types.json"}]}}}""",
        "https://gbfs.urbansharing.com/bikemi.com/vehicle_types.json" to """{"last_updated":1793670090,"ttl":15,"version":"2.3","data":{"vehicle_types":[
            {"vehicle_type_id":"ebike_with_childseat","form_factor":"bicycle","propulsion_type":"electric_assist","max_range_meters":60000},
            {"vehicle_type_id":"ebike","form_factor":"bicycle","propulsion_type":"electric_assist","max_range_meters":60000},
            {"vehicle_type_id":"bike","form_factor":"bicycle","propulsion_type":"human"}]}}""",
        "https://gbfs.urbansharing.com/bikemi.com/station_information.json" to """{"last_updated":1793670090,"ttl":15,"version":"2.3","data":{"stations":[
            {"station_id":"6060","name":"19 - Italia - Santa Sofia","address":"via Santa Sofia angolo Corso Italia","lat":45.45621307011987,"lon":9.187812079479983,"is_virtual_station":false,"capacity":30},
            {"station_id":"2318","name":"V3 - Commenda - Berchet","lat":45.45548125196515,"lon":9.199369054959675,"is_virtual_station":true,"capacity":20},
            {"station_id":"9999","name":"Lontana","lat":45.50,"lon":9.25,"capacity":10}]}}""",
        "https://gbfs.urbansharing.com/bikemi.com/station_status.json" to """{"last_updated":1793670101,"ttl":15,"version":"2.3","data":{"stations":[
            {"station_id":"6060","is_installed":true,"is_renting":true,"is_returning":true,"last_reported":1793670101,"num_vehicles_available":8,"num_bikes_available":8,"num_docks_available":20,
             "vehicle_types_available":[{"vehicle_type_id":"bike","count":6},{"vehicle_type_id":"ebike","count":2},{"vehicle_type_id":"ebike_with_childseat","count":0}]},
            {"station_id":"2318","is_installed":true,"is_renting":false,"is_returning":true,"last_reported":1793670101,"num_bikes_available":2,"num_docks_available":18},
            {"station_id":"9999","is_installed":true,"is_renting":true,"num_bikes_available":5,"num_docks_available":5}]}}"""
    )

    private val birdDocs = mapOf(
        "https://mds.bird.co/gbfs/v2/public/rome/gbfs.json" to """{"last_updated":1793670127,"ttl":60,"version":"2.3","data":{"en":{"feeds":[
            {"name":"free_bike_status","url":"https://mds.bird.co/gbfs/v2/public/rome/free_bike_status.json"},
            {"name":"vehicle_types","url":"https://mds.bird.co/gbfs/v2/public/rome/vehicle_types.json"},
            {"name":"station_information","url":"https://elsewhere.example/station_information.json"}]}}}""",
        "https://mds.bird.co/gbfs/v2/public/rome/vehicle_types.json" to """{"data":{"vehicle_types":[
            {"vehicle_type_id":"bae2102b-56ba-42ba-9097-720e5990b4b2","form_factor":"scooter","propulsion_type":"electric","max_range_meters":24000.0}]}}""",
        "https://mds.bird.co/gbfs/v2/public/rome/free_bike_status.json" to """{"last_updated":1793670080,"ttl":60,"version":"2.3","data":{"bikes":[
            {"lat":41.926265,"lon":12.463118,"is_disabled":false,"is_reserved":false,"vehicle_type_id":"bae2102b-56ba-42ba-9097-720e5990b4b2","last_reported":1793670039,"current_range_meters":8298.0,"current_fuel_percent":0.19354838},
            {"lat":41.926300,"lon":12.463200,"is_disabled":false,"is_reserved":true,"vehicle_type_id":"bae2102b-56ba-42ba-9097-720e5990b4b2","current_fuel_percent":0.0},
            {"lat":41.926300,"lon":12.463000,"is_disabled":true,"is_reserved":false,"vehicle_type_id":"bae2102b-56ba-42ba-9097-720e5990b4b2"}]}}"""
    )

    private fun service(docs: Map<String, String>, systems: List<BikeSharingSystem>, clock: MutablePublicSourceClock = MutablePublicSourceClock(now)) =
        RecordingPublicSourceFetcher { url -> docs[url] ?: throw IOException("404 $url") }.let { fetcher ->
            BikeSharingService(systems, fetcher, clock) to fetcher
        }

    @Test
    fun `docking stations within 300 m with bikes, e-bikes and free docks, nearest first`() = runBlocking {
        val (service, _) = service(bikemiDocs, listOf(BikeSharingSystems.BIKEMI))
        val result = service.nearby(nearSantaSofia) as BikeSharingResult.Loaded
        val nearby = result.systems.single()
        // Commenda does not rent at the moment and the third station is kilometres away.
        assertEquals(listOf("19 - Italia - Santa Sofia"), nearby.docks.map { it.name })
        val dock = nearby.docks.single()
        assertEquals(8, dock.bikesAvailable)
        assertEquals(2, dock.ebikesAvailable)
        assertEquals(20, dock.docksAvailable)
        assertTrue(dock.distanceMeters in 50..150)
        assertEquals(Instant.ofEpochSecond(1793670101), nearby.observedAt)
        assertFalse(nearby.stale)
    }

    /** Audit F9: three failures of one operator (Bird) must not pause another (BikeMi). */
    @Test
    fun `one failing system does not open the breaker of the others`() = runBlocking {
        val fetcher = RecordingPublicSourceFetcher { url ->
            if ("bird.co" in url) throw it.binario.app.data.live.PublicSourceHttpException(503, "HTTP 503")
            bikemiDocs[url] ?: throw IOException("404 $url")
        }
        val service = BikeSharingService(listOf(BikeSharingSystems.BIRD_ROMA, BikeSharingSystems.BIKEMI), fetcher, MutablePublicSourceClock(now))
        repeat(3) { assertEquals(BikeSharingResult.Unavailable, service.nearby(GeoPoint(41.9010, 12.5016))) }
        val before = fetcher.urls.count { "bikemi" in it }
        val milan = service.nearby(nearSantaSofia)
        assertTrue(milan is BikeSharingResult.Loaded)
        assertTrue(fetcher.urls.count { "bikemi" in it } > before)
    }

    @Test
    fun `free-floating scooters with battery, reserved and disabled ones left out, other hosts refused`() = runBlocking {
        val (service, fetcher) = service(birdDocs, listOf(BikeSharingSystems.BIRD_ROMA))
        val nearby = (service.nearby(GeoPoint(41.9260, 12.4630)) as BikeSharingResult.Loaded).systems.single()
        val scooter = nearby.vehicles.single()
        assertEquals(SharedVehicleKind.SCOOTER, scooter.kind)
        assertEquals(19, scooter.batteryPercent)
        assertTrue(nearby.docks.isEmpty())
        assertTrue(fetcher.urls.none { "elsewhere" in it })
    }

    @Test
    fun `docked bikes of a free_bike_status are counted at their station only`() {
        val json = """{"data":{"bikes":[
            {"is_reserved":false,"is_disabled":false,"lat":45.687389,"lon":9.638754,"vehicle_type_id":"150","station_id":"60670647","pricing_plan_id":"Rate0372"},
            {"is_reserved":false,"is_disabled":false,"lat":45.6900,"lon":9.6400,"vehicle_type_id":"201","current_fuel_percent":0.8}]}}"""
        val types = GbfsParser.vehicleTypes("""{"data":{"vehicle_types":[{"vehicle_type_id":"150","form_factor":"bicycle","propulsion_type":"human"},
            {"vehicle_type_id":"201","form_factor":"bicycle","propulsion_type":"electric_assist"}]}}""")
        val vehicles = GbfsParser.vehicles(json, types)
        assertEquals(listOf(SharedVehicleKind.EBIKE), vehicles.map { it.kind })
        assertEquals(80, vehicles.single().batteryPercent)
    }

    @Test
    fun `GBFS 3 lists feeds without languages and dates updates as text`() {
        val feeds = GbfsParser.feeds("""{"version":"3.0","data":{"feeds":[{"name":"vehicle_status","url":"https://maas.example/vs.json"}]}}""")
        assertEquals(mapOf("vehicle_status" to "https://maas.example/vs.json"), feeds)
        assertEquals(Instant.parse("2026-11-03T01:42:14Z"), GbfsParser.lastUpdated("""{"last_updated":"2026-11-03T01:42:14Z"}"""))
        assertNull(GbfsParser.lastUpdated("""{"data":{}}"""))
    }

    @Test
    fun `status is read again after a minute, station lists after hours`() = runBlocking {
        val clock = MutablePublicSourceClock(now)
        val (service, fetcher) = service(bikemiDocs, listOf(BikeSharingSystems.BIKEMI), clock)
        service.nearby(nearSantaSofia)
        val first = fetcher.urls.size
        clock.advance(Duration.ofSeconds(30))
        service.nearby(nearSantaSofia)
        assertEquals(first, fetcher.urls.size)
        clock.advance(Duration.ofSeconds(40))
        service.nearby(nearSantaSofia)
        assertEquals(listOf("https://gbfs.urbansharing.com/bikemi.com/station_status.json"), fetcher.urls.drop(first))
    }

    @Test
    fun `a station no system serves asks nothing, an unreachable system is unavailable`() = runBlocking {
        val (service, fetcher) = service(emptyMap(), listOf(BikeSharingSystems.BIKEMI))
        assertFalse(service.serves(GeoPoint(41.9010, 12.5010)))
        assertEquals(BikeSharingResult.NotCovered, service.nearby(GeoPoint(41.9010, 12.5010)))
        assertTrue(fetcher.urls.isEmpty())
        assertEquals(BikeSharingResult.Unavailable, service.nearby(nearSantaSofia))
    }

    @Test
    fun `systems without a stated open licence are personal builds only`() {
        val public = BikeSharingSystems.forBuild(personalSources = false)
        assertEquals(listOf(BikeSharingSystems.NEXTBIKE_BERGAMO, BikeSharingSystems.FAMOSE_SENIGALLIA, BikeSharingSystems.BIKEMI, BikeSharingSystems.VERONA_BIKE), public)
        assertTrue(public.all { it.licence.startsWith("CC0-1.0") || it.licence.startsWith("NLOD 2.0") })
        assertTrue(public.filter { it.licence.startsWith("NLOD") }.all { "NLOD 2.0" in it.attribution })
        assertTrue(BikeSharingSystems.BIRD_ROMA.personalOnly)
        assertEquals(BikeSharingSystems.all, BikeSharingSystems.forBuild(personalSources = true))
        assertTrue(BikeSharingService.sameHost("https://gbfs.nextbike.net/maps/gbfs/v2/nextbike_ib/it/station_status.json", BikeSharingSystems.NEXTBIKE_BERGAMO.discoveryUrl))
        assertFalse(BikeSharingService.sameHost("http://gbfs.nextbike.net/x.json", BikeSharingSystems.NEXTBIKE_BERGAMO.discoveryUrl))
        assertFalse(BikeSharingService.sameHost("https://gbfs.nextbike.net.evil.example/x.json", BikeSharingSystems.NEXTBIKE_BERGAMO.discoveryUrl))
    }
}
