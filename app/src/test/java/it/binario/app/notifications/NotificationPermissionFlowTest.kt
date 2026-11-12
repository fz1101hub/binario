package it.binario.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionFlowTest {
    @Test fun `a grant after the screen was recreated runs the action of the recreated screen`() {
        val ran = mutableListOf<String>()
        var denied = 0
        // The screen that asked is gone (rotation while the dialog was shown): only the saved key survived, and the
        // recreated screen has its own actions.
        val recreated = mapOf("digest" to { ran += "digest (new screen)" }, "strikes" to { ran += "strikes (new screen)" })

        NotificationPermissionFlow.onResult(granted = true, pendingKey = "digest", actions = recreated) { denied++ }
        assertEquals(listOf("digest (new screen)"), ran)

        NotificationPermissionFlow.onResult(granted = false, pendingKey = "strikes", actions = recreated) { denied++ }
        assertEquals(1, denied)
        assertEquals(listOf("digest (new screen)"), ran)

        // An answer without a pending key, or for an action the screen no longer offers, does nothing.
        NotificationPermissionFlow.onResult(granted = true, pendingKey = null, actions = recreated) { denied++ }
        NotificationPermissionFlow.onResult(granted = true, pendingKey = "works", actions = recreated) { denied++ }
        assertEquals(listOf("digest (new screen)"), ran)
        assertEquals(1, denied)
    }

    @Test
    fun `a channel the user blocked shows nothing, one not created yet is not blocked`() {
        assertFalse(LiveNotifications.channelShows(android.app.NotificationManager.IMPORTANCE_NONE))
        assertTrue(LiveNotifications.channelShows(android.app.NotificationManager.IMPORTANCE_HIGH))
        assertTrue(LiveNotifications.channelShows(android.app.NotificationManager.IMPORTANCE_LOW))
        assertTrue(LiveNotifications.channelShows(null))
    }
}
