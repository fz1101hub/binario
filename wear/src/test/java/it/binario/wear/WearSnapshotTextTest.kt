package it.binario.wear

import org.junit.Assert.assertEquals
import org.junit.Test

class WearSnapshotTextTest {
    @Test
    fun `phone app presence comes from the number of nodes advertising the capability`() {
        assertEquals(WearPhoneAppPresence.ABSENT, WearPhoneAppPresence.fromNodeCount(0))
        assertEquals(WearPhoneAppPresence.PRESENT, WearPhoneAppPresence.fromNodeCount(1))
        assertEquals(WearPhoneAppPresence.PRESENT, WearPhoneAppPresence.fromNodeCount(2))
    }

    @Test
    fun `empty state names the phone setting unless the phone app is missing`() {
        assertEquals(R.string.empty_snapshot_phone_app_missing, emptySnapshotMessageResId(WearPhoneAppPresence.ABSENT))
        assertEquals(R.string.empty_snapshot_message, emptySnapshotMessageResId(WearPhoneAppPresence.PRESENT))
        assertEquals(R.string.empty_snapshot_message, emptySnapshotMessageResId(WearPhoneAppPresence.UNKNOWN))
    }
}
