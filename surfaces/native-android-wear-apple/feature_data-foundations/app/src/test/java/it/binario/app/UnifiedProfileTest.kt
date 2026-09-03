package it.binario.app

import it.binario.app.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedProfileTest {
    @Test
    fun `profile keeps only integration preferences and monitored identifiers`() {
        val profile = UnifiedBinarioProfile(
            activeRouteId = "roma-torino",
            direction = Direction.OUTBOUND,
            monitoredTrainNumbers = listOf("9512"),
            wearSnapshotSharingEnabled = true,
            voiceProfileSyncRequested = false,
            appleWatchSetupRequested = true
        )

        assertEquals(1, profile.schemaVersion)
        assertEquals("roma-torino", profile.activeRouteId)
        assertEquals(listOf("9512"), profile.monitoredTrainNumbers)
        assertTrue(profile.wearSnapshotSharingEnabled)
        assertFalse(profile.voiceProfileSyncRequested)
        assertTrue(profile.appleWatchSetupRequested)
    }
}
