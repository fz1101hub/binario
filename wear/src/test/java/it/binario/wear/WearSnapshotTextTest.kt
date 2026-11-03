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

    @Test
    fun `refresh feedback reports the first delivered request and unreachable only when every send failed`() {
        val reports = mutableListOf<WearRefreshFeedback>()
        val delivery = WearRefreshDelivery(2) { reports += it }
        delivery.record(delivered = false)
        assertEquals(emptyList<WearRefreshFeedback>(), reports)
        delivery.record(delivered = true)
        assertEquals(listOf(WearRefreshFeedback.SENT), reports)

        val failures = mutableListOf<WearRefreshFeedback>()
        val failed = WearRefreshDelivery(2) { failures += it }
        failed.record(delivered = false)
        failed.record(delivered = false)
        assertEquals(listOf(WearRefreshFeedback.UNREACHABLE), failures)
    }

    @Test
    fun `refresh feedback is reported once and without a reachable phone at once`() {
        val reports = mutableListOf<WearRefreshFeedback>()
        val delivery = WearRefreshDelivery(2) { reports += it }
        delivery.record(delivered = true)
        delivery.record(delivered = false)
        assertEquals(listOf(WearRefreshFeedback.SENT), reports)

        val none = mutableListOf<WearRefreshFeedback>()
        WearRefreshDelivery(0) { none += it }
        assertEquals(listOf(WearRefreshFeedback.UNREACHABLE), none)
        assertEquals(R.string.refresh_request_sent, WearRefreshFeedback.SENT.messageResId)
        assertEquals(R.string.refresh_phone_unreachable, WearRefreshFeedback.UNREACHABLE.messageResId)
        assertEquals("/binario/wear/request-refresh", REFRESH_REQUEST_PATH)
    }
}
