package it.binario.app.gpsalarm

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class GpsArrivalAlarmEndReason {
    TRIGGERED,
    CANCELLED,
    EXPIRED,
    PERMISSION_REVOKED,
    LOCATION_UNAVAILABLE,
    START_FAILED,
    TIME_ALARM_FIRED
}

internal sealed interface GpsArrivalAlarmState {
    data object Idle : GpsArrivalAlarmState
    /** [track] is the train's progress from the device position, when the target has a route: in memory only. */
    data class Active(val target: GpsArrivalAlarmTarget, val precise: Boolean, val track: GpsTrainTrack? = null) : GpsArrivalAlarmState
    data class Ended(val target: GpsArrivalAlarmTarget?, val reason: GpsArrivalAlarmEndReason) : GpsArrivalAlarmState
}

/**
 * Process-wide state of the GPS alarm service for the UI. It is in memory only, so it never
 * outlives the process that runs the service.
 */
internal object GpsArrivalAlarmRuntime {
    private val mutableState = MutableStateFlow<GpsArrivalAlarmState>(GpsArrivalAlarmState.Idle)
    val state: StateFlow<GpsArrivalAlarmState> = mutableState.asStateFlow()

    @Volatile
    private var pendingStopReason: GpsArrivalAlarmEndReason? = null

    fun markActive(target: GpsArrivalAlarmTarget, precise: Boolean) {
        mutableState.value = GpsArrivalAlarmState.Active(target, precise)
    }

    /** The latest progress of the running session; ignored once the session has ended. */
    fun updateTrack(track: GpsTrainTrack) {
        val active = mutableState.value as? GpsArrivalAlarmState.Active ?: return
        if (active.target.route?.isFor(track.trainNumber) == true) mutableState.value = active.copy(track = track)
    }

    /** The progress of the running session, if it follows a train with a route. */
    fun currentTrack(): GpsTrainTrack? = (mutableState.value as? GpsArrivalAlarmState.Active)?.track

    fun markEnded(target: GpsArrivalAlarmTarget?, reason: GpsArrivalAlarmEndReason) {
        mutableState.value = GpsArrivalAlarmState.Ended(target, reason)
    }

    fun acknowledge() {
        if (mutableState.value is GpsArrivalAlarmState.Ended) mutableState.value = GpsArrivalAlarmState.Idle
    }

    /** Why the next external stopService happened; read once by the service's onDestroy. */
    fun requestStopReason(reason: GpsArrivalAlarmEndReason) {
        pendingStopReason = reason
    }

    fun takeStopReason(): GpsArrivalAlarmEndReason? = pendingStopReason.also { pendingStopReason = null }
}

/**
 * Private preferences of the GPS alarm: the active target (timetable and catalogue data only,
 * never a user position) and the last radius chosen for each train class. A position estimate
 * session is never stored here: it ends with the process. Backup and device
 * transfer exclude every preference file of the app.
 */
internal class GpsArrivalAlarmStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_gps_alarm_v1", Context.MODE_PRIVATE)

    fun activeTarget(): GpsArrivalAlarmTarget? = GpsArrivalAlarmTargetCodec.decode(preferences.getString(KEY_ACTIVE, null))

    fun saveActiveTarget(target: GpsArrivalAlarmTarget) {
        preferences.edit { putString(KEY_ACTIVE, GpsArrivalAlarmTargetCodec.encode(target)) }
    }

    fun clearActiveTarget() {
        preferences.edit { remove(KEY_ACTIVE) }
    }

    fun rememberedRadiusMeters(trainClass: GpsAlarmTrainClass): Int? =
        preferences.getInt(radiusKey(trainClass), 0).takeIf(GpsArrivalAlarmPolicy::isRadiusOption)

    fun rememberRadius(trainClass: GpsAlarmTrainClass, radiusMeters: Int) {
        if (GpsArrivalAlarmPolicy.isRadiusOption(radiusMeters)) preferences.edit { putInt(radiusKey(trainClass), radiusMeters) }
    }

    private fun radiusKey(trainClass: GpsAlarmTrainClass) = "radius_${trainClass.name.lowercase()}"

    private companion object {
        const val KEY_ACTIVE = "active_target"
    }
}

/** Thin entry points used by the UI and by the time-based alarm receiver. */
internal object GpsArrivalAlarmController {
    /**
     * Starts the location foreground service. It must be called while the app is visible: that
     * is what grants the service while-in-use location access without background location.
     * Returns false when Android refuses the start (for example
     * ForegroundServiceStartNotAllowedException on Android 12+); the caller then keeps only the
     * time-based alarm.
     */
    fun start(context: Context, target: GpsArrivalAlarmTarget): Boolean = try {
        ContextCompat.startForegroundService(context, GpsArrivalAlarmService.startIntent(context, target))
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Stops the running GPS alarm, if any; the time-based alarm is left as it is. */
    fun stop(context: Context, reason: GpsArrivalAlarmEndReason = GpsArrivalAlarmEndReason.CANCELLED) {
        GpsArrivalAlarmStore(context).clearActiveTarget()
        // stopService is only sent to a service that already called startForeground: stopping
        // one that is still starting would make Android crash the app.
        if (GpsArrivalAlarmRuntime.state.value is GpsArrivalAlarmState.Active) {
            GpsArrivalAlarmRuntime.requestStopReason(reason)
            context.stopService(Intent(context, GpsArrivalAlarmService::class.java))
        }
    }

    /**
     * Starts the position estimate of a followed trip. Like [start] it must be called while the app is
     * visible. A running GPS alarm is never replaced by an estimate: it already provides one.
     */
    fun startEstimate(context: Context, target: GpsArrivalAlarmTarget): Boolean {
        if (!target.isEstimate) return false
        val running = (GpsArrivalAlarmRuntime.state.value as? GpsArrivalAlarmState.Active)?.target
        if (running != null && !running.isEstimate) return false
        return start(context, target)
    }

    /** Ends the position estimate (the trip ended or was stopped); a GPS alarm is left running. */
    fun stopEstimate(context: Context) {
        val running = (GpsArrivalAlarmRuntime.state.value as? GpsArrivalAlarmState.Active)?.target ?: return
        if (!running.isEstimate) return
        // The estimate is never persisted, so an interrupted alarm's stored target stays untouched.
        GpsArrivalAlarmRuntime.requestStopReason(GpsArrivalAlarmEndReason.CANCELLED)
        context.stopService(Intent(context, GpsArrivalAlarmService::class.java))
    }

    /** The time-based alarm for this stop rang first: the GPS part has nothing left to do. */
    fun onTimeAlarmFired(context: Context, trainNumber: String, stopName: String) {
        val active = (GpsArrivalAlarmRuntime.state.value as? GpsArrivalAlarmState.Active)?.target
        val store = GpsArrivalAlarmStore(context)
        val stored = store.activeTarget()
        when {
            // A position estimate is not an alarm: it goes on until shortly after the arrival, and
            // only an interrupted alarm stored for this stop is dropped.
            active?.isEstimate == true -> if (stored?.isFor(trainNumber, stopName) == true) store.clearActiveTarget()
            active?.isFor(trainNumber, stopName) == true || stored?.isFor(trainNumber, stopName) == true ->
                stop(context, GpsArrivalAlarmEndReason.TIME_ALARM_FIRED)
        }
    }
}
