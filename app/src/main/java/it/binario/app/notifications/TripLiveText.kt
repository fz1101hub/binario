package it.binario.app.notifications

import android.content.res.Resources
import it.binario.app.R
import it.binario.app.gpsalarm.GpsDelayEstimate
import it.binario.app.gpsalarm.GpsTrackStatus
import it.binario.app.gpsalarm.GpsTrainRoutes
import it.binario.app.gpsalarm.GpsTrainTrack
import it.binario.app.gpsalarm.GpsTrainTracking

/**
 * Localized lines of the stop countdown and of the position estimate, shared by the followed-trip notification, the
 * GPS session's ongoing notification and the panels of the app. The estimate is always labelled as such and, when the
 * official source has live data, followed by it ("(fonte ufficiale: +4 min)"); it never replaces the official value.
 */
internal object TripLiveText {
    private const val SEPARATOR = " · "

    /** "Scendi tra 3 fermate", or "Scendi alla prossima fermata: Monza alle 08:56 · Binario 3". */
    fun countdown(resources: Resources, countdown: StopCountdown, platform: String? = null): String {
        if (!countdown.alightAtNextStop) {
            return resources.getQuantityString(R.plurals.stop_countdown_stops, countdown.stopsLeft, countdown.stopsLeft)
        }
        val line = countdown.nextStopTime?.let { resources.getString(R.string.stop_countdown_alight_next, countdown.nextStop, it) }
            ?: resources.getString(R.string.stop_countdown_alight_next_untimed, countdown.nextStop)
        return platform?.takeIf { it.isNotBlank() }?.let { line + SEPARATOR + resources.getString(R.string.platform_label, it) } ?: line
    }

    fun position(
        resources: Resources,
        track: GpsTrainTrack?,
        officialDelayMinutes: Int?,
        nowEpochMillis: Long,
        includeWaiting: Boolean = false,
        accessible: Boolean = false
    ): String? = position(resources, track?.estimate, track?.status, officialDelayMinutes, nowEpochMillis, includeWaiting, accessible)

    /**
     * The estimate line, "Posizione fuori dal percorso" when the device left the line, the waiting line when asked
     * for, else null. [officialDelayMinutes] is null when the official source has no live data.
     */
    fun position(
        resources: Resources,
        estimate: GpsDelayEstimate?,
        status: GpsTrackStatus?,
        officialDelayMinutes: Int?,
        nowEpochMillis: Long,
        includeWaiting: Boolean = false,
        accessible: Boolean = false
    ): String? = when {
        status == GpsTrackStatus.OFF_ROUTE -> resources.getString(R.string.trip_estimate_off_route)
        estimate != null -> estimate(resources, estimate, officialDelayMinutes, nowEpochMillis, accessible)
        includeWaiting && status != null -> resources.getString(R.string.trip_estimate_waiting)
        else -> null
    }

    /** "Stima dalla tua posizione: circa +6 min, arrivo ~18:47 · stima delle 18:31 (fonte ufficiale: +4 min)". */
    fun estimate(resources: Resources, estimate: GpsDelayEstimate, officialDelayMinutes: Int?, nowEpochMillis: Long, accessible: Boolean = false): String {
        val arrival = GpsTrainRoutes.clockLabel(estimate.arrivalEpochMillis)
        val delay = estimate.delayMinutes
        val line = when {
            delay <= 0 && accessible -> resources.getString(R.string.trip_estimate_on_time_accessible, arrival)
            delay <= 0 -> resources.getString(R.string.trip_estimate_line_on_time, arrival)
            accessible -> resources.getQuantityString(R.plurals.trip_estimate_accessible, delay, delay, arrival)
            else -> resources.getString(R.string.trip_estimate_line, resources.getQuantityString(R.plurals.minutes_short, delay, delay), arrival)
        }
        val aged = if (nowEpochMillis - estimate.atEpochMillis > GpsTrainTracking.ESTIMATE_FRESH_MILLIS) {
            line + SEPARATOR + resources.getString(R.string.trip_estimate_age, GpsTrainRoutes.clockLabel(estimate.atEpochMillis))
        } else line
        val official = officialDelayMinutes?.let { minutes ->
            val value = when {
                minutes <= 0 && accessible -> resources.getString(R.string.commuter_alert_status_on_time)
                minutes <= 0 -> resources.getString(R.string.service_on_time_lowercase)
                accessible -> resources.getQuantityString(R.plurals.commuter_alert_status_delay, minutes, minutes)
                else -> resources.getString(R.string.delay_minutes, minutes)
            }
            resources.getString(R.string.trip_estimate_official, value)
        }
        return listOfNotNull(aged, official).joinToString(" ")
    }
}
