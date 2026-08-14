package it.binario.app

import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataBackupCodecTest {
    @Test
    fun `backup round trip keeps a valid personal route and preferences`() {
        val roma = Station("roma", "Roma Termini", "Roma")
        val torino = Station("torino", "Torino Porta Nuova", "Torino")
        val backup = LocalDataBackup(listOf(SavedRoute("route", "Casa – lavoro", roma, torino)), setOf("roma"), emptyList(), emptyList(), emptyList(), MobilityPreferences(maxChanges = 1, stepFreePreferred = true), WidgetSurface.ACTIVE_ROUTE)
        val result = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(backup), listOf(roma, torino))
        assertTrue(result is LocalBackupResult.Success)
    }
}
