package it.binario.wear

import androidx.annotation.StringRes

/** Capability the phone app advertises in app/src/main/res/values/wear.xml; the watch probes it. */
internal const val PHONE_APP_CAPABILITY = "binario_phone_app"

/** Whether a paired phone has the Binario app, so the empty state can say what is actually missing. */
internal enum class WearPhoneAppPresence {
    UNKNOWN, PRESENT, ABSENT;

    companion object {
        /** FILTER_ALL lists every paired node advertising the capability, reachable or not. */
        fun fromNodeCount(nodeCount: Int): WearPhoneAppPresence = if (nodeCount > 0) PRESENT else ABSENT
    }
}

/** Message path the phone's WearRefreshRequestListenerService accepts. */
internal const val REFRESH_REQUEST_PATH = "/binario/wear/request-refresh"

/** Outcome of "Refresh on phone", shown under the button and announced by TalkBack. */
internal enum class WearRefreshFeedback(@StringRes val messageResId: Int) {
    SENT(R.string.refresh_request_sent),
    UNREACHABLE(R.string.refresh_phone_unreachable),
}

/**
 * Collects the send results of one refresh request, one per reachable phone node. The first
 * delivered message reports [WearRefreshFeedback.SENT]; the phone is out of reach only when there
 * is no node or every send failed. A late result after the report is ignored.
 */
internal class WearRefreshDelivery(nodeCount: Int, private val report: (WearRefreshFeedback) -> Unit) {
    private var pending = nodeCount
    private var reported = false

    init {
        if (nodeCount <= 0) finish(WearRefreshFeedback.UNREACHABLE)
    }

    @Synchronized
    fun record(delivered: Boolean) {
        if (reported) return
        pending--
        if (delivered) finish(WearRefreshFeedback.SENT) else if (pending <= 0) finish(WearRefreshFeedback.UNREACHABLE)
    }

    private fun finish(feedback: WearRefreshFeedback) {
        reported = true
        report(feedback)
    }
}
