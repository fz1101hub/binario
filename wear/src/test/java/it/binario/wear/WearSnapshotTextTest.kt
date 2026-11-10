package it.binario.wear

import it.binario.wear.model.WearConfidence
import it.binario.wear.model.WearDataNature
import it.binario.wear.model.WearDirection
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearProvenance
import it.binario.wear.model.WearProvenanceProducer
import it.binario.wear.model.WearSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class WearSnapshotTextTest {
    @Test
    fun `live data name the time their source observed them, scheduled data do not`() {
        // Observed at 08:12 in Rome, shown up to fifteen minutes later: the status says how old the values are.
        val live = snapshot(WearOperationalState.OPERATIONAL, WearFreshness.FRESH, observedAt = 1_893_481_920_000L)

        assertEquals(WearText(R.string.status_live_as_of, listOf("08:12")), live.dataStatus())
        assertEquals("status_live_as_of(08:12)", ResourceNameStrings.string(live.dataStatus()))
        assertEquals(WearText(R.string.status_programmed), snapshot(WearOperationalState.PROGRAMMED, WearFreshness.FRESH).dataStatus())
        assertEquals(WearText(R.string.status_live_unavailable), snapshot(WearOperationalState.UNAVAILABLE, WearFreshness.UNAVAILABLE).dataStatus())
        // Live data without a recent observation are not "updating": they say how old they are.
        assertEquals(
            WearText(R.string.status_not_recent_as_of, listOf("08:12")),
            snapshot(WearOperationalState.OPERATIONAL, WearFreshness.AGING).dataStatus(),
        )
        // The time is the rail clock (Europe/Rome), like the departures, also in summer.
        assertEquals("09:12", railClockTime(1_909_120_320_000L))
    }

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

    private fun snapshot(state: WearOperationalState, freshness: WearFreshness, observedAt: Long = 1_893_481_920_000L) = WearSnapshot(
        routeId = "route",
        routeLabel = "Roma Termini → Napoli Centrale",
        direction = WearDirection.OUTBOUND,
        provenance = WearProvenance(WearProvenanceProducer.ANDROID_APP, WearDataNature.OPERATIONAL, "Fonte", observedAt),
        operationalState = state,
        freshness = freshness,
        confidence = WearConfidence.OPERATOR_PRIMARY,
        generatedAtEpochMillis = observedAt,
        expiresAtEpochMillis = observedAt + 900_000L,
        ttlMillis = 900_000L,
        notice = "",
        services = emptyList(),
    )
}
