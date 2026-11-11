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
    @Test fun `a file over 1 MiB is refused with a reason in the app language`() {
        val text = { id: Int -> if (id == R.string.backup_import_too_large) "The backup is larger than the 1 MiB limit." else "file error" }
        val tooLarge = runCatching { LocalDataBackupCodec.readBoundedUtf8(java.io.ByteArrayInputStream(ByteArray(LocalDataBackupCodec.MAX_BYTES + 1) { ' '.code.toByte() })) }
            .exceptionOrNull()

        assertTrue(tooLarge is BackupTooLargeException)
        assertEquals("The backup is larger than the 1 MiB limit.", LocalDataBackupCodec.importFailureReason(tooLarge!!, text))
        assertEquals(LocalDataBackupCodec.MAX_BYTES, LocalDataBackupCodec.readBoundedUtf8(java.io.ByteArrayInputStream(ByteArray(LocalDataBackupCodec.MAX_BYTES) { ' '.code.toByte() })).length)
        // Other failures keep their own message, or the generic file error.
        assertEquals("Permission denied", LocalDataBackupCodec.importFailureReason(java.io.IOException("Permission denied"), text))
        assertEquals("file error", LocalDataBackupCodec.importFailureReason(java.io.IOException(), text))
    }

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
    fun `free text stations travel inside the backup with their routes favourites and monitors`() {
        val roma = Station("roma", "Roma Termini", "Roma")
        val borgo = stationFromFreeText("Borgo San Dalmazzo")!!
        val cuneo = stationFromFreeText("Cuneo Gesso")!!
        val backup = LocalDataBackup(
            routes = listOf(
                SavedRoute("free", "Valle", borgo, cuneo),
                SavedRoute("mixed", "Roma", roma, borgo)
            ),
            favouriteStationIds = setOf(cuneo.id),
            monitoredServices = emptyList(),
            stationMonitors = listOf(StationMonitor(borgo.id, borgo.name, StationMonitorMode.BOTH)),
            commuterAlerts = emptyList(),
            mobilityPreferences = MobilityPreferences(),
            stations = listOf(borgo, cuneo, roma)
        )

        // The importing device knows only the catalogue, not the stations typed on the old one.
        val result = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(backup), listOf(roma))

        assertTrue(result is LocalBackupResult.Success)
        val restored = (result as LocalBackupResult.Success).backup
        assertEquals(listOf("free", "mixed"), restored.routes.map { it.id })
        assertEquals(borgo, restored.routes.first().outwardOrigin)
        assertEquals(setOf(cuneo.id), restored.favouriteStationIds)
        assertEquals(listOf(borgo.id), restored.stationMonitors.map { it.stationId })
        assertEquals(setOf(borgo.id, cuneo.id), restored.stations.map { it.id }.toSet())
    }

    @Test
    fun `catalogue station wins over the record embedded in the backup`() {
        val roma = Station("roma", "Roma Termini", "Roma", railwayCode = "S08409")
        val torino = Station("torino", "Torino Porta Nuova", "Torino")
        val raw = """{"schemaVersion":2,"kind":"binario-local-backup","routes":[{"id":"route","name":"R","originId":"roma","destinationId":"torino","pinned":false}],"stations":[{"id":"roma","name":"Roma vecchia","city":"Roma"},{"id":"torino","name":"Torino Porta Nuova","city":"Torino","latitude":"x"}]}"""

        val result = LocalDataBackupCodec.decode(raw, listOf(roma))

        assertTrue(result is LocalBackupResult.Success)
        val restored = (result as LocalBackupResult.Success).backup
        assertEquals(roma, restored.routes.single().outwardOrigin)
        assertEquals(listOf(torino), restored.stations)
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
    fun `a backup without saved routes is accepted when it carries anything else`() {
        // Who only searches in Pianifica still has favourite trains, an alert, a history: «Esporta» writes this file.
        val raw = """{"schemaVersion":2,"kind":"binario-local-backup","routes":[],"commuterAlerts":[{"id":"alert","routeLabel":"Casa","stationName":"Roma","time":"08:00","leadMinutes":15,"repeat":"DAILY","quietHoursEnabled":false,"enabled":true}]}"""

        val result = LocalDataBackupCodec.decode(raw, emptyList())

        assertTrue(result is LocalBackupResult.Success)
        val backup = (result as LocalBackupResult.Success).backup
        assertTrue(backup.routes.isEmpty())
        assertEquals("alert", backup.commuterAlerts.single().id)
    }

    @Test
    fun `what the export writes for a user without routes can be imported again`() {
        val roma = Station("roma", "Roma Termini", "Roma")
        val exported = LocalDataBackupCodec.encode(
            LocalDataBackup(
                routes = emptyList(), favouriteStationIds = setOf("roma"),
                monitoredServices = listOf(ServiceMonitor("9631", "FR 9631")), stationMonitors = emptyList(), commuterAlerts = emptyList(),
                mobilityPreferences = MobilityPreferences(), stations = listOf(roma)
            )
        )

        val result = LocalDataBackupCodec.decode(exported, listOf(roma))

        assertTrue(result is LocalBackupResult.Success)
        val backup = (result as LocalBackupResult.Success).backup
        assertEquals(setOf("roma"), backup.favouriteStationIds)
        assertEquals("9631", backup.monitoredServices.single().number)
        // Preferences alone count too; the same file with nothing in it does not.
        val preferencesOnly = LocalDataBackup(emptyList(), emptySet(), emptyList(), emptyList(), emptyList(), MobilityPreferences(maxChanges = 1))
        assertTrue(LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(preferencesOnly), emptyList()) is LocalBackupResult.Success)
    }

    @Test
    fun `a backup that carries nothing is refused`() {
        val empty = LocalDataBackup(emptyList(), emptySet(), emptyList(), emptyList(), emptyList(), MobilityPreferences(), punctualityHistory = emptyList(), savedTimetables = emptyList())

        val result = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(empty), emptyList())

        assertTrue(result is LocalBackupResult.Failure)
        assertTrue((result as LocalBackupResult.Failure).message.contains("non contiene dati"))
    }

    @Test
    fun `a backup whose listed routes are all invalid is still refused`() {
        // The stations of its only route are unknown here: importing the rest would silently erase this device's routes.
        val raw = """{"schemaVersion":2,"kind":"binario-local-backup","routes":[{"id":"route","name":"Casa","originId":"unknown-a","destinationId":"unknown-b","pinned":false}],"monitoredServices":[{"number":"9631","label":"FR 9631"}]}"""

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
