package it.binario.app.data

/** The share of the ticket price Regulation (EU) 2021/782, art. 19, sets for a delay at the final destination. */
enum class CompensationShare(val percent: Int) {
    /** 60 to 119 minutes. */
    QUARTER(25),
    /** 120 minutes or more. */
    HALF(50)
}

/**
 * A run that reached [stationName] [delayMinutes] late, enough for the compensation notice. The notice names the
 * station: after a train-number search the last stop shown is the terminus, not necessarily where the traveller left.
 */
data class DelayCompensation(val stationName: String, val delayMinutes: Int, val share: CompensationShare)

/**
 * When the train detail shows the delay compensation notice: only on the actual arrival a source verified at the last
 * stop of the run shown (the destination of a route search, else the terminus), at least [QUARTER_FROM_MINUTES] late.
 * Never on scheduled-only, estimated or demonstration data, a cancelled train or a stop it skipped. It states the rule
 * and nothing about the ticket, which the app does not know.
 */
object DelayCompensationRule {
    const val QUARTER_FROM_MINUTES = 60
    const val HALF_FROM_MINUTES = 120

    fun of(service: TrainService, isDemonstration: Boolean): DelayCompensation? {
        if (isDemonstration || service.status == ServiceStatus.CANCELLED) return null
        val destination = service.stops.lastOrNull() ?: return null
        // completed: the source published an actual arrival there (ViaggiaTreno arrivoReale, Italo's actual time).
        if (!destination.completed || destination.routeChange == StopRouteChange.SKIPPED) return null
        if (!(destination.operationalStatusVerified ?: service.operationalStatusVerified)) return null
        val delay = destination.delayMinutes
        if (delay < QUARTER_FROM_MINUTES) return null
        return DelayCompensation(destination.station, delay, if (delay >= HALF_FROM_MINUTES) CompensationShare.HALF else CompensationShare.QUARTER)
    }
}
