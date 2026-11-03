package it.binario.app

import it.binario.app.data.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `numbers the companion would reject are normalised or dropped one by one`() {
        val request = UnifiedBinarioProfile(
            activeRouteId = "milano-bergamo",
            direction = Direction.RETURN,
            monitoredTrainNumbers = listOf(
                "RE_2 - 2247", " RV 4132 ", "RE_2 - 2247", "S9 - 25015", "", "   ", "OCESN017538F0100100:2026-10-15", "x".repeat(33), "9512"
            ),
            wearSnapshotSharingEnabled = false,
            voiceProfileSyncRequested = true,
            appleWatchSetupRequested = false
        ).toAlexaCompanionProfileRequest()

        // "S9 - 25015" is already acceptable once its spaces go, exactly as the server stores it.
        assertEquals(listOf("2247", "RV4132", "S9-25015", "9512"), request.monitoredTrainNumbers)
        request.monitoredTrainNumbers.forEach { assertTrue(it, Regex("^[A-Za-z0-9-]{1,32}$").matches(it)) }
    }
}
