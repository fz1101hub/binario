package it.binario.app.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.binario.app.R
import it.binario.app.data.RailTime
import it.binario.app.data.live.StrikeCalendar
import it.binario.app.data.live.StrikeNotice
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.repository.BinarioDataGraph
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The day-before strike reminder: one run a day at [RUN_AT]; a strike starting tomorrow is notified once. A run that
 * Android delays past the start of the quiet hours (22:00) notifies nothing, like the commuter alerts.
 */
object StrikeReminderPlanner {
    val RUN_AT: LocalTime = LocalTime.of(18, 0)
    val QUIET_FROM: LocalTime = LocalTime.of(22, 0)
    const val NOTIFIED_RETENTION_DAYS = 3L

    fun nextRunAt(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(RUN_AT).atZone(now.zone)
        return if (now.isBefore(today)) today else now.toLocalDate().plusDays(1).atTime(RUN_AT).atZone(now.zone)
    }

    fun mayNotify(now: ZonedDateTime): Boolean = !now.toLocalTime().isBefore(RUN_AT) && now.toLocalTime().isBefore(QUIET_FROM)

    /** Rail strikes starting tomorrow that were not notified yet. */
    fun toNotify(strikes: List<StrikeNotice>, today: LocalDate, notified: Map<String, Long>): List<StrikeNotice> =
        StrikeCalendar.startingTomorrow(strikes, today).filter { it.id !in notified }

    /** [notified] with [strikes] added under today's day, without entries older than [NOTIFIED_RETENTION_DAYS]. */
    fun remember(notified: Map<String, Long>, strikes: List<StrikeNotice>, today: LocalDate): Map<String, Long> {
        val day = today.toEpochDay()
        return (notified + strikes.associate { it.id to day }).filterValues { day - it < NOTIFIED_RETENTION_DAYS }
    }
}

internal class StrikeReminderStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_strike_reminder_v1", Context.MODE_PRIVATE)

    fun notified(): Map<String, Long> = runCatching {
        val value = JSONObject(preferences.getString(KEY, null) ?: return emptyMap())
        value.keys().asSequence().take(200).associateWith { value.optLong(it) }
    }.getOrDefault(emptyMap())

    fun save(notified: Map<String, Long>) = preferences.edit {
        putString(KEY, JSONObject().apply { notified.forEach { (id, day) -> put(id, day) } }.toString())
    }

    private companion object {
        const val KEY = "notified"
    }
}

object StrikeReminderScheduler {
    internal const val WORK = "binario-strike-reminder"
    private val networkConnected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** On only with the setting (default on) and notifications allowed; [keepPending] leaves a scheduled run alone. */
    fun reschedule(context: Context, keepPending: Boolean = false) {
        val workManager = WorkManager.getInstance(context)
        if (!LiveNotificationSettings(context).strikeReminderEnabled || !LiveNotifications.allowed(context) || BinarioDataGraph.strikeCalendar() == null) {
            workManager.cancelUniqueWork(WORK)
            return
        }
        enqueue(context, if (keepPending) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE)
    }

    internal fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val now = ZonedDateTime.now(RailTime.defaultZone)
        val delay = Duration.between(now, StrikeReminderPlanner.nextRunAt(now))
        val request = OneTimeWorkRequestBuilder<StrikeReminderWorker>()
            .setConstraints(networkConnected)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }
}

class StrikeReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val settings = LiveNotificationSettings(context)
        try {
            val now = ZonedDateTime.now(RailTime.defaultZone)
            if (settings.strikeReminderEnabled && LiveNotifications.allowed(context) && StrikeReminderPlanner.mayNotify(now)) remind(context, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The calendar is unreachable or unreadable: nothing is shown, tomorrow's run tries again.
        } finally {
            if (!isStopped && settings.strikeReminderEnabled) StrikeReminderScheduler.enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        Result.success()
    }

    private suspend fun remind(context: Context, now: ZonedDateTime) {
        val strikes = when (val read = BinarioDataGraph.strikeCalendar()?.strikes()) {
            is ProviderResult.Success -> read.value
            is ProviderResult.Partial -> read.value
            else -> return
        }
        val store = StrikeReminderStore(context)
        val notified = store.notified()
        val today = now.toLocalDate()
        val tomorrow = StrikeReminderPlanner.toNotify(strikes, today, notified)
        if (tomorrow.isEmpty()) return
        val title = context.resources.getQuantityString(R.plurals.strike_reminder_notification_title, tomorrow.size, tomorrow.size)
        val notification = NotificationCompat.Builder(context, LiveChannel.STRIKES.ensure(context))
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle(title)
            .setContentText(tomorrow.first().title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(tomorrow.joinToString("\n\n") { it.title }))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(LiveNotifications.openApp(context, LiveNotifications.STRIKE_ID))
            .build()
        if (LiveNotifications.post(context, LiveNotifications.STRIKE_ID, notification)) {
            store.save(StrikeReminderPlanner.remember(notified, tomorrow, today))
        }
    }
}
