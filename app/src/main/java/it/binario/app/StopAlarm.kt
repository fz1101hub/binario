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
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class StopAlarmPlan(
    val trainNumber: String,
    val destination: String,
    val station: String,
    val expectedTime: String,
    val triggerAtEpochMillis: Long
)

object StopAlarmPlanner {
    private const val LEAD_MINUTES = 5L
    private val timeFormatter = DateTimeFormatter.ofPattern("H:mm")

    fun plan(
        trainNumber: String,
        destination: String,
        station: String,
        expectedTime: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): StopAlarmPlan? {
        val time = runCatching { LocalTime.parse(expectedTime, timeFormatter) }.getOrNull() ?: return null
        val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
        val todayExpected = LocalDateTime.of(now.toLocalDate(), time).atZone(zoneId).toInstant().toEpochMilli()
        // Timetables only expose a clock time. A small early-morning time while it is late at
        // night is therefore the next service day, not an already-passed daytime stop.
        val expected = if (
            todayExpected <= nowEpochMillis &&
            now.toLocalTime() >= LocalTime.of(18, 0) &&
            time < LocalTime.of(6, 0)
        ) {
            LocalDateTime.of(now.toLocalDate().plusDays(1), time).atZone(zoneId).toInstant().toEpochMilli()
        } else {
            todayExpected
        }
        if (expected <= nowEpochMillis) return null
        val trigger = maxOf(nowEpochMillis + 1_000L, expected - LEAD_MINUTES * 60_000L)
        return StopAlarmPlan(trainNumber, destination, station, expectedTime, trigger)
    }

    fun plan(service: TrainService, stop: TrainStop, nowEpochMillis: Long = System.currentTimeMillis()): StopAlarmPlan? =
        plan(service.number, service.destination, stop.station, stop.expected, nowEpochMillis)
}

object StopAlarmScheduler {
    const val CHANNEL_ID = "binario_stop_alarm"
    private const val ACTION_STOP_ALARM = "it.binario.app.STOP_ALARM"
    private const val EXTRA_TRAIN = "train"
    private const val EXTRA_DESTINATION = "destination"
    private const val EXTRA_STATION = "station"
    private const val EXTRA_EXPECTED = "expected"

    fun schedule(context: Context, plan: StopAlarmPlan): DeviceAlarmScheduleResult {
        val intent = Intent(context, StopAlarmReceiver::class.java).apply {
            action = ACTION_STOP_ALARM
            putExtra(EXTRA_TRAIN, plan.trainNumber)
            putExtra(EXTRA_DESTINATION, plan.destination)
            putExtra(EXTRA_STATION, plan.station)
            putExtra(EXTRA_EXPECTED, plan.expectedTime)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            "${plan.trainNumber}-${plan.station}-${plan.triggerAtEpochMillis}".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return scheduleWakeupAlarm(context, alarmManager, plan.triggerAtEpochMillis, pendingIntent)
    }

    fun notify(context: Context, train: String, destination: String, station: String, expected: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.stop_alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.stop_alarm_channel_description) }
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle(context.getString(R.string.stop_alarm_notification_title, station))
            .setContentText(context.getString(R.string.stop_alarm_notification_text, train, destination, expected))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.stop_alarm_notification_big_text, train, destination, station, expected)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, train, station, expected))
            .build()
        NotificationManagerCompat.from(context).notify("$train-$station-$expected".hashCode(), notification)
    }

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
        StopAlarmScheduler.notify(
            context = context,
            train = StopAlarmScheduler.trainExtra(intent),
            destination = StopAlarmScheduler.destinationExtra(intent),
            station = StopAlarmScheduler.stationExtra(intent),
            expected = StopAlarmScheduler.expectedExtra(intent)
        )
    }
}
