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
    data class Active(val target: GpsArrivalAlarmTarget, val precise: Boolean) : GpsArrivalAlarmState
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
 * never a user position) and the last radius chosen for each train class. Backup and device
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

    /** The time-based alarm for this stop rang first: the GPS part has nothing left to do. */
    fun onTimeAlarmFired(context: Context, trainNumber: String, stopName: String) {
        val running = (GpsArrivalAlarmRuntime.state.value as? GpsArrivalAlarmState.Active)?.target
        val stored = GpsArrivalAlarmStore(context).activeTarget()
        if (running?.isFor(trainNumber, stopName) == true || stored?.isFor(trainNumber, stopName) == true) {
            stop(context, GpsArrivalAlarmEndReason.TIME_ALARM_FIRED)
        }
    }
}
