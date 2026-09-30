package it.binario.app

import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataBackupCodecTest {
    private val curatedStations by lazy {
        EmbeddedCuratedRailStationCatalog(
            openAsset = { assetPath -> File("src/main/assets", assetPath).inputStream() }
        ).load()
    }

    @Test
    fun `backup round trip keeps a valid personal route and preferences`() {
        val roma = Station("roma", "Roma Termini", "Roma")
        val torino = Station("torino", "Torino Porta Nuova", "Torino")
        val backup = LocalDataBackup(listOf(SavedRoute("route", "Casa – lavoro", roma, torino, manualOrder = 3)), setOf("roma"), emptyList(), emptyList(), emptyList(), MobilityPreferences(maxChanges = 1, stepFreePreferred = true))
        val result = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(backup), listOf(roma, torino))
        assertTrue(result is LocalBackupResult.Success)
        assertEquals(3, (result as LocalBackupResult.Success).backup.routes.single().manualOrder)
    }

    @Test
    fun `version one backup remains importable without a manual order`() {
        val roma = Station("roma", "Roma Termini", "Roma")
        val torino = Station("torino", "Torino Porta Nuova", "Torino")
        val raw = """{"schemaVersion":1,"kind":"binario-local-backup","routes":[{"id":"route","name":"Casa – lavoro","originId":"roma","destinationId":"torino","pinned":false}]}"""

        val result = LocalDataBackupCodec.decode(raw, listOf(roma, torino))
        assertTrue(result is LocalBackupResult.Success)
        assertNull((result as LocalBackupResult.Success).backup.routes.single().manualOrder)
    }

    @Test
    fun `v0 16 3 encoder fixture migrates all legacy station identities`() {
        val raw = checkNotNull(javaClass.getResource("/backups/v0.16.3-schema-1.json")).readText()

        val result = LocalDataBackupCodec.decode(raw, curatedStations)

        assertTrue(result is LocalBackupResult.Success)
        val backup = (result as LocalBackupResult.Success).backup
        val routeStationIds = backup.routes.flatMap { route ->
            listOf(route.outwardOrigin.id, route.outwardDestination.id)
        }.toSet()
        assertEquals(EXPECTED_CURATED_IDS, routeStationIds)
        assertEquals(EXPECTED_CURATED_IDS, backup.favouriteStationIds)
        assertEquals(EXPECTED_CURATED_IDS.take(12).toSet(), backup.stationMonitors.map { it.stationId }.toSet())
        backup.stationMonitors.forEach { monitor ->
            assertEquals(curatedStations.single { it.id == monitor.stationId }.name, monitor.stationName)
        }
    }

    @Test
    fun `schema two does not reinterpret legacy-looking station ids`() {
        val legacyLooking = Station("roma-termini", "Not the curated station", "Test")
        val curated = curatedStations.single { it.id == "curated:S08409" }
        val destination = curatedStations.single { it.id == "curated:S00219" }
        val raw = """{"schemaVersion":2,"kind":"binario-local-backup","routes":[{"id":"route","name":"Modern","originId":"roma-termini","destinationId":"curated:S00219","pinned":false}],"favouriteStationIds":["roma-termini"],"stationMonitors":[{"stationId":"roma-termini","stationName":"Modern name","mode":"BOTH"}]}"""

        val result = LocalDataBackupCodec.decode(raw, listOf(legacyLooking, curated, destination))

        assertTrue(result is LocalBackupResult.Success)
        val backup = (result as LocalBackupResult.Success).backup
        assertEquals("roma-termini", backup.routes.single().outwardOrigin.id)
        assertEquals(setOf("roma-termini"), backup.favouriteStationIds)
        assertEquals("roma-termini", backup.stationMonitors.single().stationId)
    }

    @Test
    fun `route-less backup remains rejected by the versioned codec contract`() {
        val raw = """{"schemaVersion":2,"kind":"binario-local-backup","routes":[],"commuterAlerts":[{"id":"alert","routeLabel":"Casa","stationName":"Roma","time":"08:00","leadMinutes":15,"repeat":"DAILY","quietHoursEnabled":false,"enabled":true}]}"""

        val result = LocalDataBackupCodec.decode(raw, emptyList())

        assertTrue(result is LocalBackupResult.Failure)
        assertTrue((result as LocalBackupResult.Failure).message.contains("tratte valide"))
    }

    @Test
    fun `reserved route identifier rejects the complete backup`() {
        val roma = Station("roma", "Roma Termini", "Roma")
        val torino = Station("torino", "Torino Porta Nuova", "Torino")
        val raw = """{"schemaVersion":2,"kind":"binario-local-backup","routes":[{"id":"unconfigured-route","name":"Riservata","originId":"roma","destinationId":"torino","pinned":false}]}"""

        val result = LocalDataBackupCodec.decode(raw, listOf(roma, torino))

        assertTrue(result is LocalBackupResult.Failure)
        assertTrue((result as LocalBackupResult.Failure).message.contains("riservato"))
    }

    @Test
    fun `oversized backup is rejected before JSON parsing`() {
        val oversized = "x".repeat(LocalDataBackupCodec.MAX_BYTES + 1)

        val result = LocalDataBackupCodec.decode(oversized, emptyList())

        assertTrue(result is LocalBackupResult.Failure)
        assertTrue((result as LocalBackupResult.Failure).message.contains("1 MiB"))
    }

    @Test
    fun `invalid alert time is discarded while valid route imports`() {
        val roma = Station("roma", "Roma Termini", "Roma")
        val torino = Station("torino", "Torino Porta Nuova", "Torino")
        val raw = """{"schemaVersion":2,"kind":"binario-local-backup","routes":[{"id":"route","name":"Casa","originId":"roma","destinationId":"torino","pinned":false}],"commuterAlerts":[{"id":"alert","routeLabel":"Casa","stationName":"Roma","time":"not-a-time","leadMinutes":15,"repeat":"DAILY","quietHoursEnabled":false,"enabled":true}]}"""

        val result = LocalDataBackupCodec.decode(raw, listOf(roma, torino))

        assertTrue(result is LocalBackupResult.Success)
        assertTrue((result as LocalBackupResult.Success).backup.commuterAlerts.isEmpty())
    }

    private companion object {
        val EXPECTED_CURATED_IDS = listOf(
            "curated:S08409",
            "curated:S08217",
            "curated:S08406",
            "curated:S08405",
            "curated:S08323",
            "curated:S08408",
            "curated:S08411",
            "curated:S08650",
            "curated:S08010",
            "curated:S08751",
            "curated:S08734",
            "curated:S08304",
            "curated:S08209",
            "curated:S08608",
            "curated:S08662",
            "curated:S08671",
            "curated:S09218",
            "curated:S06421",
            "curated:S05043",
            "curated:S01700",
            "curated:NC001358",
            "curated:S04700",
            "curated:S00219",
            "curated:S11119",
            "curated:S12002",
            "curated:S12891",
        ).toSet()
    }
}
