package it.binario.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import it.binario.app.gpsalarm.GpsArrivalAlarmController

/** What made a stop alarm ring: its scheduled time, or the GPS arrival alarm reaching the radius. */
enum class StopAlarmTrigger { TIME, LOCATION }

object StopAlarmScheduler {
    const val CHANNEL_ID = "binario_stop_alarm"
    private const val ACTION_STOP_ALARM = "it.binario.app.STOP_ALARM"
    private const val EXTRA_TRAIN = "train"
    private const val EXTRA_DESTINATION = "destination"
    private const val EXTRA_STATION = "station"
    private const val EXTRA_EXPECTED = "expected"

    fun schedule(context: Context, plan: StopAlarmPlan): DeviceAlarmScheduleResult {
        val intent = alarmIntent(context).apply {
            putExtra(EXTRA_TRAIN, plan.trainNumber)
            putExtra(EXTRA_DESTINATION, plan.destination)
            putExtra(EXTRA_STATION, plan.station)
            putExtra(EXTRA_EXPECTED, plan.expectedTime)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(plan.trainNumber, plan.station, plan.triggerAtEpochMillis),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return scheduleWakeupAlarm(context, alarmManager, plan.triggerAtEpochMillis, pendingIntent)
    }

    /** Cancels the time-based alarm scheduled for [plan], if it is still pending. */
    fun cancel(context: Context, plan: StopAlarmPlan) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(plan.trainNumber, plan.station, plan.triggerAtEpochMillis),
            alarmIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    /** Posts the stop alarm notification; false when Android does not let the app notify. */
    fun notify(
        context: Context,
        train: String,
        destination: String,
        station: String,
        expected: String,
        trigger: StopAlarmTrigger = StopAlarmTrigger.TIME
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.stop_alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.stop_alarm_channel_description) }
        )
        val byLocation = trigger == StopAlarmTrigger.LOCATION
        val title = if (byLocation) R.string.gps_alarm_triggered_title else R.string.stop_alarm_notification_title
        val text = if (byLocation) R.string.gps_alarm_triggered_text else R.string.stop_alarm_notification_text
        val bigText = if (byLocation) R.string.gps_alarm_triggered_big_text else R.string.stop_alarm_notification_big_text
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle(context.getString(title, station))
            .setContentText(context.getString(text, train, destination, expected))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(bigText, train, destination, station, expected)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, train, station, expected))
            .build()
        // Same id for both triggers: if the other alarm still rings later it replaces this
        // notification instead of adding a second one.
        NotificationManagerCompat.from(context).notify("$train-$station-$expected".hashCode(), notification)
        return true
    }

    private fun alarmIntent(context: Context): Intent =
        Intent(context, StopAlarmReceiver::class.java).setAction(ACTION_STOP_ALARM)

    private fun requestCode(train: String, station: String, triggerAtEpochMillis: Long): Int =
        "$train-$station-$triggerAtEpochMillis".hashCode()

    internal fun trainExtra(intent: Intent) = intent.getStringExtra(EXTRA_TRAIN).orEmpty()
    internal fun destinationExtra(intent: Intent) = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
    internal fun stationExtra(intent: Intent) = intent.getStringExtra(EXTRA_STATION).orEmpty()
    internal fun expectedExtra(intent: Intent) = intent.getStringExtra(EXTRA_EXPECTED).orEmpty()

    private fun contentIntent(context: Context, train: String, station: String, expected: String): PendingIntent = PendingIntent.getActivity(
        context,
        "$train-$station-$expected".hashCode(),
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class StopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val train = StopAlarmScheduler.trainExtra(intent)
        val station = StopAlarmScheduler.stationExtra(intent)
        StopAlarmScheduler.notify(
            context = context,
            train = train,
            destination = StopAlarmScheduler.destinationExtra(intent),
            station = station,
            expected = StopAlarmScheduler.expectedExtra(intent)
        )
        // Whichever alarm rings first wins: the GPS alarm for the same stop stops here.
        GpsArrivalAlarmController.onTimeAlarmFired(context, train, station)
    }
}
