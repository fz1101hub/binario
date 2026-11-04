package it.binario.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import it.binario.app.MainActivity
import it.binario.app.R

/** The channels of the live features; importance is fixed at creation, as Android requires. */
internal enum class LiveChannel(val id: String, val nameRes: Int, val descriptionRes: Int, val importance: Int) {
    /** The ongoing notification of the followed trip: low importance, so its updates never make a sound. */
    FOLLOWED_TRIP("binario_followed_trip", R.string.followed_trip_channel_name, R.string.followed_trip_channel_description, NotificationManager.IMPORTANCE_LOW),
    TRAIN_ALERTS("binario_train_alerts", R.string.train_alerts_channel_name, R.string.train_alerts_channel_description, NotificationManager.IMPORTANCE_DEFAULT),
    STRIKES("binario_strikes", R.string.strike_reminder_channel_name, R.string.strike_reminder_channel_description, NotificationManager.IMPORTANCE_DEFAULT),

    /** Cancellation, platform change or connection at risk on the followed trip: with sound, once per change. */
    FOLLOWED_TRIP_ALERTS(
        "binario_followed_trip_alerts",
        R.string.followed_trip_alerts_channel_name,
        R.string.followed_trip_alerts_channel_description,
        NotificationManager.IMPORTANCE_HIGH
    ),

    /** "Il treno sta arrivando": time-sensitive, like the commuter alerts. */
    APPROACH("binario_approach_alerts", R.string.approach_alert_channel_name, R.string.approach_alert_channel_description, NotificationManager.IMPORTANCE_HIGH),

    /** The Sunday summary of the favourite trains: never urgent, so it comes without sound. */
    WEEKLY_DIGEST("binario_weekly_digest", R.string.weekly_digest_channel_name, R.string.weekly_digest_channel_description, NotificationManager.IMPORTANCE_LOW);

    fun ensure(context: Context): String {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(id, context.getString(nameRes), importance).apply {
                description = context.getString(descriptionRes)
                if (this@LiveChannel == FOLLOWED_TRIP) setSound(null, null)
            }
        )
        return id
    }
}

internal object LiveNotifications {
    const val FOLLOWED_TRIP_ID = 0x5EA1
    const val STRIKE_ID = 0x5EA2
    const val FOLLOWED_TRIP_ALERT_ID = 0x5EA3
    const val WEEKLY_DIGEST_ID = 0x5EA4

    /** Whether Binario may post now: the runtime permission (Android 13+) and the app's notification switch. */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Posts [notification] only with the notification permission; false when it was not shown. */
    fun post(context: Context, id: Int, notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        NotificationManagerCompat.from(context).notify(id, notification)
        return true
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    /** Opens the app on its current screen. */
    fun openApp(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/**
 * The switches of the live features, kept on the device: notices for followed and favourite trains (off until the
 * user turns it on), the day-before strike reminder (on by default, effective only when notifications are allowed)
 * the daily punctuality recording of the favourite trains (on by default, nothing leaves the device but the
 * status request itself) and the weekly digest of the favourite trains (off until the user turns it on, no request).
 */
internal class LiveNotificationSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var trainAlertsEnabled: Boolean
        get() = preferences.getBoolean(KEY_TRAIN_ALERTS, false)
        set(value) = preferences.edit { putBoolean(KEY_TRAIN_ALERTS, value) }

    var strikeReminderEnabled: Boolean
        get() = preferences.getBoolean(KEY_STRIKES, true)
        set(value) = preferences.edit { putBoolean(KEY_STRIKES, value) }

    var punctualityRecordingEnabled: Boolean
        get() = preferences.getBoolean(KEY_PUNCTUALITY, true)
        set(value) = preferences.edit { putBoolean(KEY_PUNCTUALITY, value) }

    var weeklyDigestEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEEKLY_DIGEST, false)
        set(value) = preferences.edit { putBoolean(KEY_WEEKLY_DIGEST, value) }

    private companion object {
        const val PREFERENCES = "binario_live_notifications_v1"
        const val KEY_TRAIN_ALERTS = "train_alerts"
        const val KEY_STRIKES = "strike_reminder"
        const val KEY_PUNCTUALITY = "punctuality_recording"
        const val KEY_WEEKLY_DIGEST = "weekly_digest"
    }
}

/** Starts or stops every live chain after a start of the app, a settings change or a change of the favourites. */
object LiveNotificationScheduling {
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        runCatching { FollowedTripScheduler.reschedule(app) }
        runCatching { TrainAlertScheduler.reschedule(app, keepPending = true) }
        runCatching { StrikeReminderScheduler.reschedule(app, keepPending = true) }
        runCatching { PunctualityRecorderScheduler.reschedule(app) }
        runCatching { ApproachAlertScheduler.reschedule(app) }
        runCatching { WeeklyDigestScheduler.reschedule(app, keepPending = true) }
    }

    fun onFavouritesChanged(context: Context) {
        val app = context.applicationContext
        runCatching { TrainAlertScheduler.reschedule(app) }
        runCatching { PunctualityRecorderScheduler.reschedule(app) }
        runCatching { ApproachAlertScheduler.reschedule(app) }
    }
}
