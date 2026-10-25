package it.binario.wear

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
