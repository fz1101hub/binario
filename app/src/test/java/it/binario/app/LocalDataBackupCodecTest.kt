package it.binario.app

import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataBackupCodecTest {
    @Test
    fun `backup round trip keeps a valid personal route and preferences`() {
        val roma = Station("roma", "Roma Termini", "Roma")
        val torino = Station("torino", "Torino Porta Nuova", "Torino")
        val backup = LocalDataBackup(listOf(SavedRoute("route", "Casa – lavoro", roma, torino, manualOrder = 3)), setOf("roma"), emptyList(), emptyList(), emptyList(), MobilityPreferences(maxChanges = 1, stepFreePreferred = true), WidgetSurface.ACTIVE_ROUTE)
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
}
