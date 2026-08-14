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
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class AlertRepeat(val label: String, val days: Set<DayOfWeek>) {
    ONCE("Una volta", emptySet()),
    WEEKDAYS("Lun–ven", setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)),
    DAILY("Ogni giorno", DayOfWeek.entries.toSet())
}

data class CommuterAlert(
    val id: String,
    val routeLabel: String,
    val stationName: String,
    val time: String,
    val leadMinutes: Int,
    val repeat: AlertRepeat,
    val quietHoursEnabled: Boolean,
    val enabled: Boolean = true
) {
    val accessibleSummary: String
        get() = "$routeLabel, fermata $stationName alle $time, avviso $leadMinutes minuti prima, ${repeat.label}${if (quietHoursEnabled) ", fascia silenziosa 22:00–07:00" else ""}${if (!enabled) ", disattivato" else ""}."
}

object CommuterAlertPlanner {
    private val formatter = DateTimeFormatter.ofPattern("H:mm")

    fun nextTrigger(
        alert: CommuterAlert,
        nowEpochMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        if (!alert.enabled) return null
        val time = runCatching { LocalTime.parse(alert.time, formatter) }.getOrNull() ?: return null
        val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            if (alert.repeat != AlertRepeat.ONCE && date.dayOfWeek !in alert.repeat.days) continue
            val scheduled = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
            val trigger = scheduled - alert.leadMinutes.coerceIn(1, 60) * 60_000L
            if (trigger <= nowEpochMillis) continue
            if (alert.quietHoursEnabled && isQuiet(Instant.ofEpochMilli(trigger).atZone(zone).toLocalTime())) continue
            return trigger
        }
        return null
    }

    fun isQuiet(time: LocalTime): Boolean = time >= LocalTime.of(22, 0) || time < LocalTime.of(7, 0)
}

class CommuterAlertStore(context: Context) {
    private val prefs = context.getSharedPreferences("binario_commuter_alerts", Context.MODE_PRIVATE)

    fun alerts(): List<CommuterAlert> = prefs.getString(KEY_ALERTS, "").orEmpty()
        .split(";")
        .mapNotNull(::decode)
        .sortedWith(compareBy<CommuterAlert> { !it.enabled }.thenBy { it.time })

    fun save(alert: CommuterAlert): List<CommuterAlert> {
        val updated = (listOf(alert) + alerts().filterNot { it.id == alert.id }).take(24)
        persist(updated)
        return updated
    }

    fun remove(id: String): List<CommuterAlert> {
        val updated = alerts().filterNot { it.id == id }
        persist(updated)
        return updated
    }

    fun find(id: String): CommuterAlert? = alerts().firstOrNull { it.id == id }

    fun newAlert(routeLabel: String, stationName: String, time: String, leadMinutes: Int, repeat: AlertRepeat, quietHoursEnabled: Boolean) =
        CommuterAlert(UUID.randomUUID().toString(), routeLabel, stationName, time, leadMinutes.coerceIn(1, 60), repeat, quietHoursEnabled)

    private fun persist(alerts: List<CommuterAlert>) {
        prefs.edit { putString(KEY_ALERTS, alerts.joinToString(";") { encode(it) }) }
    }

    private fun encode(alert: CommuterAlert): String = listOf(
        alert.id, alert.routeLabel, alert.stationName, alert.time, alert.leadMinutes.toString(), alert.repeat.name,
        if (alert.quietHoursEnabled) "1" else "0", if (alert.enabled) "1" else "0"
    ).joinToString(",") { encodeText(it) }

    private fun decode(value: String): CommuterAlert? = runCatching {
        val parts = value.split(",").map(::decodeText)
        if (parts.size != 8) return null
        CommuterAlert(
            id = parts[0], routeLabel = parts[1], stationName = parts[2], time = parts[3], leadMinutes = parts[4].toInt(),
            repeat = AlertRepeat.valueOf(parts[5]), quietHoursEnabled = parts[6] == "1", enabled = parts[7] == "1"
        )
    }.getOrNull()

    private fun encodeText(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun decodeText(value: String): String = String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)

    private companion object { const val KEY_ALERTS = "alerts" }
}

object CommuterAlertScheduler {
    private const val ACTION = "it.binario.app.COMMUTER_ALERT"
    private const val EXTRA_ID = "alert_id"
    private const val CHANNEL_ID = "binario_commuter_alerts"

    fun schedule(context: Context, alert: CommuterAlert) {
        val trigger = CommuterAlertPlanner.nextTrigger(alert) ?: return
        val pending = pendingIntent(context, alert.id)
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    }

    fun cancel(context: Context, alertId: String) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context, alertId)
        manager.cancel(pending)
        pending.cancel()
    }

    fun notifyAndReschedule(context: Context, alertId: String) {
        val alert = CommuterAlertStore(context).find(alertId) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Avvisi pendolare Binario", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Promemoria locali ricorrenti configurati dall’utente."
                }
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle("Promemoria: ${alert.stationName}")
            .setContentText("${alert.routeLabel}. Fermata prevista alle ${alert.time}.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${alert.routeLabel}: la fermata ${alert.stationName} è prevista alle ${alert.time}. L’avviso è locale e non segue i ritardi senza una fonte autorizzata."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(alert.id.hashCode(), notification)
        if (alert.repeat != AlertRepeat.ONCE) schedule(context, alert)
    }

    private fun pendingIntent(context: Context, alertId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        alertId.hashCode(),
        Intent(context, CommuterAlertReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, alertId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    internal fun alertId(intent: Intent): String = intent.getStringExtra(EXTRA_ID).orEmpty()
}

class CommuterAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CommuterAlertScheduler.notifyAndReschedule(context, CommuterAlertScheduler.alertId(intent))
    }
}
