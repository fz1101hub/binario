package it.binario.app.gpsalarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import it.binario.app.MainActivity
import it.binario.app.R
import it.binario.app.StopAlarmScheduler
import it.binario.app.StopAlarmTrigger
import it.binario.app.notifications.FollowedTripScheduler
import it.binario.app.notifications.LiveNotifications
import it.binario.app.notifications.TripLiveText

/**
 * Foreground service of type `location` behind the GPS arrival alarm and the position estimate of
 * a followed trip ("Stima dalla tua posizione"). It is started only from the visible app, which
 * gives it while-in-use location access without background location; it is never restarted by the
 * system or after a reboot. Fixes are evaluated in memory by [GpsArrivalAlarmPolicy] and
 * [GpsTrainTracking] and dropped: no position is stored, logged or sent anywhere. What the session
 * derives from them (the last stop reached, an estimated delay) lives only in
 * [GpsArrivalAlarmRuntime] and ends with the session.
 */
class GpsArrivalAlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val listener = LocationListenerCompat(::onLocation)
    private val watchdog = object : Runnable {
        override fun run() {
            checkStillAllowed()
            if (!finished) {
                target?.let { current -> refreshTracked(current, System.currentTimeMillis()) }
                handler.postDelayed(this, WATCHDOG_INTERVAL_MILLIS)
            }
        }
    }
    private var target: GpsArrivalAlarmTarget? = null
    private var precise = false
    private var providers: List<String> = emptyList()
    private var intervalMillis = 0L
    private var finished = true
    private var tracker: GpsTrainTracking.Tracker? = null
    /** Whether the followed trip includes this train: only then is the delay estimated in alarm mode. */
    private var followed = false
    private var shownText: String? = null
    private var repaintKey: String? = null
    private var repaintAtMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(GpsArrivalAlarmTargetCodec.decode(intent.getStringExtra(EXTRA_TARGET)))
            ACTION_STOP -> finish(GpsArrivalAlarmEndReason.CANCELLED)
            else -> stopSelf()
        }
        // A location service restarted by the system would run without while-in-use access.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Reached through stopService (the app or the time-based alarm) or a system stop.
        if (!finished) end(GpsArrivalAlarmRuntime.takeStopReason() ?: GpsArrivalAlarmEndReason.CANCELLED)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun start(newTarget: GpsArrivalAlarmTarget?) {
        // Known before entering the foreground, so the ongoing notification states the radius in use.
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        precise = fine
        // startForeground must come first: a service started with startForegroundService that
        // stops before calling it makes Android crash the app.
        if (!enterForeground(newTarget)) {
            releaseLocation()
            GpsArrivalAlarmStore(this).clearActiveTarget()
            finished = true
            target = null
            GpsArrivalAlarmRuntime.markEnded(newTarget, GpsArrivalAlarmEndReason.START_FAILED)
            stopSelf()
            return
        }
        // A new alarm replaces the one that may be running.
        releaseLocation()
        handler.removeCallbacks(watchdog)
        target = newTarget
        finished = false
        intervalMillis = 0L
        tracker = newTarget?.route?.takeIf { it.usable }?.let { GpsTrainTracking.Tracker(it) }
        followed = newTarget?.let(::isFollowed) == true
        shownText = null
        repaintKey = null
        if (newTarget == null) {
            finish(GpsArrivalAlarmEndReason.START_FAILED)
            return
        }
        if (GpsArrivalAlarmPolicy.isExpired(System.currentTimeMillis(), newTarget)) {
            finish(GpsArrivalAlarmEndReason.EXPIRED)
            return
        }
        // The estimate needs precise fixes (at most 200 m): with approximate location it could never work.
        if ((!fine && !coarse) || (newTarget.isEstimate && !fine)) {
            finish(GpsArrivalAlarmEndReason.PERMISSION_REVOKED)
            return
        }
        val locationManager = getSystemService(LocationManager::class.java)
        providers = locationManager?.let {
            GpsArrivalAlarmPolicy.locationProviders(precise, Build.VERSION.SDK_INT, it.allProviders)
        }.orEmpty()
        if (locationManager == null || providers.isEmpty() || !LocationManagerCompat.isLocationEnabled(locationManager)) {
            finish(GpsArrivalAlarmEndReason.LOCATION_UNAVAILABLE)
            return
        }
        // An estimate is never persisted: it is not offered again after a restart.
        if (!newTarget.isEstimate) GpsArrivalAlarmStore(this).saveActiveTarget(newTarget)
        GpsArrivalAlarmRuntime.markActive(newTarget, precise)
        requestUpdates(GpsArrivalAlarmPolicy.nextIntervalMillis(null))
        if (!finished) handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MILLIS)
    }

    private fun enterForeground(newTarget: GpsArrivalAlarmTarget?): Boolean {
        createStatusChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, ongoingNotification(newTarget), type)
            true
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+): the app was not allowed
            // to start a foreground service at this moment.
            false
        } catch (_: SecurityException) {
            // Android 14+: location access is missing or not usable while in use.
            false
        }
    }

    private fun onLocation(location: Location) {
        val current = target ?: return
        if (finished) return
        val now = System.currentTimeMillis()
        if (GpsArrivalAlarmPolicy.isExpired(now, current)) {
            finish(GpsArrivalAlarmEndReason.EXPIRED)
            return
        }
        val fix = GpsFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        )
        tracker?.let { active ->
            active.onFix(fix, now)
            refreshTracked(current, now)
            if (finished) return
        }
        if (current.isEstimate) {
            // No alarm to ring: the same cadence rule, on the distance to the stop where the traveller gets off.
            val decision = GpsArrivalAlarmPolicy.evaluate(fix, current.latitude, current.longitude, 0)
            if (decision is GpsFixDecision.Approaching) {
                val next = GpsArrivalAlarmPolicy.nextIntervalMillis(decision.remainingMeters)
                if (next != intervalMillis) requestUpdates(next)
            }
            return
        }
        val radius = GpsArrivalAlarmPolicy.effectiveRadiusMeters(current.radiusMeters, precise)
        when (val decision = GpsArrivalAlarmPolicy.evaluate(fix, current.latitude, current.longitude, radius)) {
            is GpsFixDecision.Arrived -> ring(current)
            is GpsFixDecision.Approaching -> {
                val next = GpsArrivalAlarmPolicy.nextIntervalMillis(decision.remainingMeters)
                if (next != intervalMillis) requestUpdates(next)
            }
            is GpsFixDecision.Rejected -> Unit
        }
    }

    /**
     * Publishes the train's progress (the delay only for a followed trip or the estimate itself), updates the ongoing
     * notification when its text changes and, at most once a minute, the followed-trip notification. Ends an estimate
     * whose trip is over.
     */
    private fun refreshTracked(current: GpsArrivalAlarmTarget, now: Long) {
        val raw = tracker?.track ?: return
        val track = if (current.isEstimate || followed) raw else raw.copy(estimate = null)
        GpsArrivalAlarmRuntime.updateTrack(track)
        if (current.isEstimate) {
            val official = FollowedTripScheduler.officialDelayMinutes(this, current.trainNumber)
            if (!followed || now >= GpsTrainTracking.estimateEndsAt(current, track, official)) {
                finish(if (followed) GpsArrivalAlarmEndReason.EXPIRED else GpsArrivalAlarmEndReason.CANCELLED)
                return
            }
        }
        val notification = ongoingNotification(current, track, now)
        val text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        // Same id as the foreground notification: posting it again updates it in place.
        if (text != shownText && LiveNotifications.post(this, NOTIFICATION_ID, notification)) shownText = text
        val key = "${track.status}|${track.passedIndex}|${track.estimate?.delayMinutes}"
        if ((current.isEstimate || followed) && key != repaintKey && now - repaintAtMillis >= REPAINT_INTERVAL_MILLIS) {
            repaintKey = key
            repaintAtMillis = now
            FollowedTripScheduler.repaint(this)
        }
    }

    private fun isFollowed(current: GpsArrivalAlarmTarget): Boolean = FollowedTripScheduler.follows(this, current.trainNumber)

    private fun ring(current: GpsArrivalAlarmTarget) {
        val posted = StopAlarmScheduler.notify(
            context = this,
            train = current.trainNumber,
            destination = current.trainDestination,
            station = current.stopName,
            expected = current.expectedTime,
            trigger = StopAlarmTrigger.LOCATION
        )
        // Only a delivered notification replaces the time-based alarm for the same stop.
        if (posted) StopAlarmScheduler.cancel(this, current.timeAlarmPlan())
        finish(GpsArrivalAlarmEndReason.TRIGGERED)
    }

    private fun checkStillAllowed() {
        val current = target ?: return
        followed = isFollowed(current)
        when {
            finished -> Unit
            GpsArrivalAlarmPolicy.isExpired(System.currentTimeMillis(), current) -> finish(GpsArrivalAlarmEndReason.EXPIRED)
            // Revoked, or precise access downgraded to approximate, while running. Android
            // normally kills the process on revocation; this covers the cases where it does not.
            !granted(if (precise) Manifest.permission.ACCESS_FINE_LOCATION else Manifest.permission.ACCESS_COARSE_LOCATION) ->
                finish(GpsArrivalAlarmEndReason.PERMISSION_REVOKED)
        }
    }

    private fun requestUpdates(nextIntervalMillis: Long) {
        val current = target ?: return
        val locationManager = getSystemService(LocationManager::class.java) ?: return
        val permitted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!permitted) {
            finish(GpsArrivalAlarmEndReason.PERMISSION_REVOKED)
            return
        }
        val remaining = (current.expiresAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(1L)
        val request = LocationRequestCompat.Builder(nextIntervalMillis)
            .setQuality(if (precise) LocationRequestCompat.QUALITY_HIGH_ACCURACY else LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
            .setMinUpdateIntervalMillis(nextIntervalMillis)
            // Android itself ends the request at the expiry even if this service misbehaves.
            .setDurationMillis(remaining)
            .build()
        val registered = try {
            // Requesting again for the same listener and provider replaces the previous request.
            providers.count { provider ->
                try {
                    LocationManagerCompat.requestLocationUpdates(locationManager, provider, request, ContextCompat.getMainExecutor(this), listener)
                    true
                } catch (_: IllegalArgumentException) {
                    // The provider vanished; the other one may still work.
                    false
                }
            }
        } catch (_: SecurityException) {
            finish(GpsArrivalAlarmEndReason.PERMISSION_REVOKED)
            return
        }
        if (registered == 0) finish(GpsArrivalAlarmEndReason.LOCATION_UNAVAILABLE) else intervalMillis = nextIntervalMillis
    }

    // Removing a listener must also work after the permission was revoked, so it is not guarded
    // by a permission check; the platform does not require one to unregister.
    @SuppressLint("MissingPermission")
    private fun releaseLocation() {
        val locationManager = getSystemService(LocationManager::class.java) ?: return
        runCatching { LocationManagerCompat.removeUpdates(locationManager, listener) }
    }

    /** Terminal stop requested by the service itself: cleans up, then leaves the foreground. */
    private fun finish(reason: GpsArrivalAlarmEndReason) {
        if (!finished) end(reason)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun end(reason: GpsArrivalAlarmEndReason) {
        finished = true
        handler.removeCallbacks(watchdog)
        releaseLocation()
        val ended = target
        if (ended?.isEstimate != true) GpsArrivalAlarmStore(this).clearActiveTarget()
        GpsArrivalAlarmRuntime.markEnded(ended, reason)
        target = null
        tracker = null
        // The estimate leaves the followed-trip notification with the session.
        if (ended != null && (ended.isEstimate || followed)) FollowedTripScheduler.repaint(this)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun notificationManager(): NotificationManager? = getSystemService(NotificationManager::class.java)

    private fun createStatusChannel() {
        notificationManager()?.createNotificationChannel(
            NotificationChannel(
                STATUS_CHANNEL_ID,
                getString(R.string.gps_alarm_status_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.gps_alarm_status_channel_description) }
        )
    }

    private fun ongoingNotification(current: GpsArrivalAlarmTarget?, track: GpsTrainTrack? = null, now: Long = System.currentTimeMillis()): Notification {
        val builder = NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_binario)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(
                R.drawable.ic_binario,
                getString(if (current?.isEstimate == true) R.string.action_trip_estimate_stop else R.string.action_gps_alarm_stop),
                stopIntent()
            )
        if (current != null) {
            val countdown = current.route?.let { route -> GpsTrainRoutes.countdown(route, track, now) }?.let { TripLiveText.countdown(resources, it) }
            val official = if (track?.estimate != null) FollowedTripScheduler.officialDelayMinutes(this, current.trainNumber) else null
            val position = TripLiveText.position(resources, track, official, now, includeWaiting = current.isEstimate)
            if (current.isEstimate) {
                val status = position ?: getString(R.string.trip_estimate_waiting)
                builder.setContentTitle(getString(R.string.trip_estimate_active_title, current.trainNumber))
                    .setContentText(status)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(status, countdown, getString(R.string.trip_estimate_privacy_short)).joinToString("\n")))
            } else {
                val effectiveKilometres = GpsArrivalAlarmPolicy.effectiveRadiusMeters(current.radiusMeters, precise) / 1_000
                val radius = resources.getQuantityString(R.plurals.gps_alarm_radius_km, effectiveKilometres, effectiveKilometres)
                val radiusText = getString(if (precise) R.string.gps_alarm_active_precise else R.string.gps_alarm_active_approximate, radius)
                builder.setContentTitle(getString(R.string.gps_alarm_active_title, current.stopName))
                    .setContentText(countdown ?: radiusText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(countdown, position, radiusText).joinToString("\n")))
            }
        } else {
            builder.setContentTitle(getString(R.string.gps_alarm_status_channel_name))
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        NOTIFICATION_ID,
        Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        NOTIFICATION_ID,
        Intent(this, GpsArrivalAlarmService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    internal companion object {
        private const val ACTION_START = "it.binario.app.gpsalarm.START"
        private const val ACTION_STOP = "it.binario.app.gpsalarm.STOP"
        private const val EXTRA_TARGET = "target"
        private const val STATUS_CHANNEL_ID = "binario_gps_alarm_status"
        private const val NOTIFICATION_ID = 0x6A51
        private const val WATCHDOG_INTERVAL_MILLIS = 60_000L
        private const val REPAINT_INTERVAL_MILLIS = 60_000L

        fun startIntent(context: Context, target: GpsArrivalAlarmTarget): Intent =
            Intent(context, GpsArrivalAlarmService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TARGET, GpsArrivalAlarmTargetCodec.encode(target))
    }
}
