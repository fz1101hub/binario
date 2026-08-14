package it.binario.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
        val expected = LocalDateTime.of(LocalDate.now(zoneId), time).atZone(zoneId).toInstant().toEpochMilli()
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

    fun schedule(context: Context, plan: StopAlarmPlan) {
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
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAtEpochMillis, pendingIntent)
    }

    fun notify(context: Context, train: String, destination: String, station: String, expected: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Sveglie fermata Binario",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Avvisi locali per le fermate ferroviarie impostate nell’app." }
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle("Fermata in arrivo: $station")
            .setContentText("Treno $train verso $destination. Orario previsto $expected.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Treno $train verso $destination: la fermata $station è prevista alle $expected. Controlla sempre monitor e annunci ufficiali."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify("$train-$station-$expected".hashCode(), notification)
    }

    internal fun trainExtra(intent: Intent) = intent.getStringExtra(EXTRA_TRAIN).orEmpty()
    internal fun destinationExtra(intent: Intent) = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
    internal fun stationExtra(intent: Intent) = intent.getStringExtra(EXTRA_STATION).orEmpty()
    internal fun expectedExtra(intent: Intent) = intent.getStringExtra(EXTRA_EXPECTED).orEmpty()
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
