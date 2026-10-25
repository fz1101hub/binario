package it.binario.app.gpsalarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import it.binario.app.StopAlarmPlan
import it.binario.app.StopAlarmScheduler
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The options dialog shown when the user sets a stop alarm. */
internal data class StopAlarmOffer(
    val plan: StopAlarmPlan,
    val trainClass: GpsAlarmTrainClass,
    val station: GpsAlarmStation?,
    val availability: GpsAlarmAvailability,
    val initialRadiusMeters: Int
) {
    val locationAvailable: Boolean get() = station != null && availability == GpsAlarmAvailability.AVAILABLE
}

internal enum class GpsAlarmNoticeKind { PERMISSION_DENIED, LOCATION_DISABLED, START_FAILED, NOTIFICATIONS_REQUIRED }

internal data class GpsAlarmNotice(val kind: GpsAlarmNoticeKind, val stopName: String)

/**
 * UI side of the GPS arrival alarm, as an option of the existing stop alarm flow: the dialog,
 * the location permission request (asked only when the user enables the option) and the start
 * of the foreground service while the app is visible. The time-based alarm and its notification
 * permission stay with the caller.
 */
@Stable
internal class GpsArrivalAlarmFlow(
    context: Context,
    private val scope: CoroutineScope,
    private val offerState: MutableState<StopAlarmOffer?>,
    private val pendingState: MutableState<String?>,
    private val noticeState: MutableState<GpsAlarmNotice?>,
    private val storeRevisionState: MutableIntState
) {
    private val context = context.applicationContext
    private val store = GpsArrivalAlarmStore(this.context)
    internal var locationPermissionLauncher: ActivityResultLauncher<Array<String>>? = null

    val offer: StopAlarmOffer? get() = offerState.value
    val notice: GpsAlarmNotice? get() = noticeState.value

    /** Changes whenever the persisted target changes from the UI, so derived UI state is re-read. */
    val storeRevision: Int get() = storeRevisionState.intValue

    /** Opens the options dialog for [plan]; resolving the catalogue coordinates runs off the main thread. */
    fun offer(service: TrainService, stop: TrainStop, plan: StopAlarmPlan, stations: List<Station>) {
        val trainClass = GpsArrivalAlarmPolicy.trainClass(service.trainCategory, service.operator)
        val radius = GpsArrivalAlarmPolicy.initialRadiusMeters(trainClass, store.rememberedRadiusMeters(trainClass))
        scope.launch {
            val station = withContext(Dispatchers.Default) { GpsArrivalAlarmStations.resolve(stop.station, stations) }
            val availability = GpsArrivalAlarmPolicy.availability(System.currentTimeMillis(), plan.expectedAtEpochMillis, station != null)
            offerState.value = StopAlarmOffer(plan, trainClass, station, availability, radius)
        }
    }

    fun dismissOffer() {
        offerState.value = null
    }

    /** Confirms the dialog: the caller schedules the time-based alarm, then reports back. */
    fun confirm(useLocation: Boolean, radiusMeters: Int, scheduleTimeAlarm: (StopAlarmPlan) -> Unit) {
        val current = offerState.value ?: return
        offerState.value = null
        noticeState.value = null
        val station = current.station
        pendingState.value = if (useLocation && current.locationAvailable && station != null && GpsArrivalAlarmPolicy.isRadiusOption(radiusMeters)) {
            store.rememberRadius(current.trainClass, radiusMeters)
            GpsArrivalAlarmTargetCodec.encode(GpsArrivalAlarmTarget.from(current.plan, station, radiusMeters))
        } else {
            null
        }
        scheduleTimeAlarm(current.plan)
    }

    /** The time-based alarm for [plan] is scheduled: continue with the GPS part if it was chosen. */
    fun onTimeAlarmScheduled(plan: StopAlarmPlan) {
        val pending = GpsArrivalAlarmTargetCodec.decode(pendingState.value) ?: return
        if (!pending.isFor(plan.trainNumber, plan.station)) {
            pendingState.value = null
            return
        }
        val target = pending.copy(timeAlarmTriggerEpochMillis = plan.triggerAtEpochMillis)
        pendingState.value = GpsArrivalAlarmTargetCodec.encode(target)
        continueWithLocation(target)
    }

    /** Notifications were refused, so neither alarm can ring. */
    fun onTimeAlarmRefused() {
        pendingState.value = null
    }

    fun onLocationPermissionResult() {
        val target = GpsArrivalAlarmTargetCodec.decode(pendingState.value) ?: return
        pendingState.value = null
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            start(target)
        } else {
            noticeState.value = GpsAlarmNotice(GpsAlarmNoticeKind.PERMISSION_DENIED, target.stopName)
        }
    }

    fun stopActive() {
        GpsArrivalAlarmController.stop(context)
        storeRevisionState.intValue += 1
    }

    /**
     * A persisted target without a running service: the system interrupted the alarm (reboot,
     * process death). Expired ones are dropped silently.
     */
    fun interruptedTarget(state: GpsArrivalAlarmState): GpsArrivalAlarmTarget? {
        if (state is GpsArrivalAlarmState.Active) return null
        val stored = store.activeTarget() ?: return null
        if (GpsArrivalAlarmPolicy.isExpired(System.currentTimeMillis(), stored)) {
            store.clearActiveTarget()
            return null
        }
        return stored
    }

    /** Re-enables an interrupted alarm, re-scheduling the time-based alarm a reboot may have cleared. */
    fun resume(target: GpsArrivalAlarmTarget) {
        noticeState.value = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            noticeState.value = GpsAlarmNotice(GpsAlarmNoticeKind.NOTIFICATIONS_REQUIRED, target.stopName)
            return
        }
        // The persisted target stays until the service runs again (it then rewrites it), so a
        // refused permission or a disabled location keeps the offer to re-enable visible.
        // Same request code as the original alarm: scheduling again replaces it if it survived.
        if (target.timeAlarmTriggerEpochMillis > System.currentTimeMillis()) StopAlarmScheduler.schedule(context, target.timeAlarmPlan())
        pendingState.value = GpsArrivalAlarmTargetCodec.encode(target)
        continueWithLocation(target)
    }

    fun dismissInterrupted() {
        store.clearActiveTarget()
        storeRevisionState.intValue += 1
    }

    fun acknowledge() {
        noticeState.value = null
        GpsArrivalAlarmRuntime.acknowledge()
    }

    private fun continueWithLocation(target: GpsArrivalAlarmTarget) {
        val locationManager = context.getSystemService(LocationManager::class.java)
        if (locationManager == null || !LocationManagerCompat.isLocationEnabled(locationManager)) {
            pendingState.value = null
            noticeState.value = GpsAlarmNotice(GpsAlarmNoticeKind.LOCATION_DISABLED, target.stopName)
            return
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            pendingState.value = null
            start(target)
            return
        }
        // Fine and coarse are requested together (required from Android 12); the user may grant
        // only approximate location, which the service handles with a wider radius. A user who
        // granted approximate location before is offered the upgrade to precise.
        val launcher = locationPermissionLauncher
        if (launcher != null) {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            onLocationPermissionResult()
        }
    }

    private fun start(target: GpsArrivalAlarmTarget) {
        val now = System.currentTimeMillis()
        val armed = target.copy(expiresAtEpochMillis = GpsArrivalAlarmPolicy.expiresAtEpochMillis(now, target.arrivalEpochMillis))
        if (GpsArrivalAlarmPolicy.isExpired(now, armed)) return
        if (!GpsArrivalAlarmController.start(context, armed)) {
            noticeState.value = GpsAlarmNotice(GpsAlarmNoticeKind.START_FAILED, target.stopName)
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
internal fun rememberGpsArrivalAlarmFlow(): GpsArrivalAlarmFlow {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val offer = rememberSaveable(stateSaver = stopAlarmOfferSaver) { mutableStateOf<StopAlarmOffer?>(null) }
    val pending = rememberSaveable { mutableStateOf<String?>(null) }
    val notice = rememberSaveable(stateSaver = gpsAlarmNoticeSaver) { mutableStateOf<GpsAlarmNotice?>(null) }
    val storeRevision = remember { mutableIntStateOf(0) }
    val flow = remember(context, scope) { GpsArrivalAlarmFlow(context, scope, offer, pending, notice, storeRevision) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        flow.onLocationPermissionResult()
    }
    SideEffect { flow.locationPermissionLauncher = launcher }
    return flow
}

private val stopAlarmOfferSaver = listSaver<StopAlarmOffer?, Any>(
    save = { offer ->
        if (offer == null) {
            emptyList()
        } else {
            val plan = offer.plan
            listOf(
                plan.trainNumber, plan.destination, plan.station, plan.expectedTime, plan.triggerAtEpochMillis,
                plan.expectedAtEpochMillis, offer.trainClass.name, offer.availability.name, offer.initialRadiusMeters,
                offer.station?.id.orEmpty(), offer.station?.name.orEmpty(),
                offer.station?.latitude ?: Double.NaN, offer.station?.longitude ?: Double.NaN
            )
        }
    },
    restore = { values ->
        runCatching {
            val station = (values[9] as String).takeIf(String::isNotEmpty)?.let { id ->
                GpsAlarmStation(id, values[10] as String, values[11] as Double, values[12] as Double)
            }
            StopAlarmOffer(
                plan = StopAlarmPlan(
                    trainNumber = values[0] as String,
                    destination = values[1] as String,
                    station = values[2] as String,
                    expectedTime = values[3] as String,
                    triggerAtEpochMillis = values[4] as Long,
                    expectedAtEpochMillis = values[5] as Long
                ),
                trainClass = GpsAlarmTrainClass.valueOf(values[6] as String),
                station = station,
                availability = GpsAlarmAvailability.valueOf(values[7] as String),
                initialRadiusMeters = values[8] as Int
            )
        }.getOrNull()
    }
)

private val gpsAlarmNoticeSaver = listSaver<GpsAlarmNotice?, String>(
    save = { notice -> if (notice == null) emptyList() else listOf(notice.kind.name, notice.stopName) },
    restore = { values ->
        runCatching { GpsAlarmNotice(GpsAlarmNoticeKind.valueOf(values[0]), values[1]) }.getOrNull()
    }
)
