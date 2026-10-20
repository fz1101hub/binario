package it.binario.app

import it.binario.app.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

class AlexaCompanionProfileTest {
    @Test
    fun `request maps the unified profile without credentials and deduplicates monitors`() {
        val request = UnifiedBinarioProfile(
            activeRouteId = "roma-torino",
            direction = Direction.OUTBOUND,
            monitoredTrainNumbers = listOf("9512", "9512", "RV-4132"),
            wearSnapshotSharingEnabled = true,
            voiceProfileSyncRequested = true,
            appleWatchSetupRequested = false
        ).toAlexaCompanionProfileRequest(expectedRevision = 4, clientMutationId = "2b899092-512e-4a73-8c18-5e623d978c71")

        assertEquals(1, request.schemaVersion)
        assertEquals(listOf("9512", "RV-4132"), request.monitoredTrainNumbers)
        assertEquals(4, request.expectedRevision)
    }
}
