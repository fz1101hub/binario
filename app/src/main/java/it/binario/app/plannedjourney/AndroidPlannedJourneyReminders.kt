package it.binario.app.plannedjourney

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import it.binario.app.MainActivity
import it.binario.app.R
import it.binario.app.scheduleWakeupAlarm
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.Executors

/** Application-process gate covers consent, durable journey writes and alarm delivery. */
object AndroidPlannedJourneyReminders {
    internal val gate = Any()
    const val CHANNEL_ID = "binario_planned_journey_reminders"
    private const val ACTION_REMINDER = "it.binario.app.PLANNED_JOURNEY_REMINDER"
    private const val URI_SCHEME = "binario-agenda-alarm"
    private val executor = Executors.newSingleThreadExecutor()

    fun controller(context: Context): PlannedJourneyReminderController {
        val app = context.applicationContext
        return object : PlannedJourneyReminderController {
            override fun setEnabled(enabled: Boolean) = synchronized(gate) {
                val store = StateStore(app)
                store.write(store.read().copy(enabled = enabled))
                requestRefresh(app)
            }
            override fun refresh(): PlannedJourneyReminderStatus = refreshNow(app)
        }
    }

    internal fun requestRefresh(context: Context) {
        val app = context.applicationContext
        executor.execute { runCatching { refreshNow(app) } }
    }

    private fun snapshot(context: Context): PlannedJourneyPersistenceSnapshot =
        PlannedJourneyRepository(SharedPreferencesPlannedJourneyStorage(context)).read().snapshot

    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.agenda_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.agenda_reminder_channel_description) }
        )
    }

    private fun refreshNow(context: Context, alarmsCleared: Boolean = false): PlannedJourneyReminderStatus = synchronized(gate) {
        val store = StateStore(context)
        runCatching {
            createChannel(context)
            PlannedJourneyReminderEngine(store, Device(context)).refresh(snapshot(context), allowed(context), Instant.now(), alarmsCleared)
        }.getOrElse { PlannedJourneyReminderStatus(store.read().enabled, allowed(context), failed = true) }
    }

    internal fun acceptsRestoreAction(action: String?): Boolean = action in setOf(
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED
    ) || (Build.VERSION.SDK_INT >= 31 && action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)

    internal fun dispatch(context: Context, intent: Intent, finished: () -> Unit) {
        val app = context.applicationContext
        executor.execute {
            try {
                synchronized(gate) {
                    val key = intent.data?.takeIf { it.scheme == URI_SCHEME && it.host == "reminder" }
                        ?.lastPathSegment?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                    if (intent.action == ACTION_REMINDER && key != null) {
                        createChannel(app)
                        PlannedJourneyReminderEngine(StateStore(app), Device(app))
                            .deliver(key, snapshot(app), allowed(app), Instant.now())
                    } else if (acceptsRestoreAction(intent.action)) {
                        refreshNow(app, alarmsCleared = intent.action == Intent.ACTION_BOOT_COMPLETED ||
                            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
                    }
                }
            } catch (_: Exception) {
                // No journey content or notification identifiers in logs. A later refresh repairs the journal.
            } finally {
                finished()
            }
        }
    }

    private class StateStore(context: Context) : PlannedJourneyReminderStateStore {
        private val prefs = context.getSharedPreferences("binario_agenda_reminders_v1", Context.MODE_PRIVATE)
        override fun read(): PlannedJourneyReminderState = runCatching {
            PlannedJourneyReminderStateCodec.decode(prefs.getString("state", null))
        }.getOrNull() ?: PlannedJourneyReminderState()

        // KTX edit(commit = true) discards the result; scheduling requires a confirmed durable journal.
        @SuppressLint("UseKtx")
        override fun write(state: PlannedJourneyReminderState) {
            check(prefs.edit().putString("state", PlannedJourneyReminderStateCodec.encode(state)).commit())
        }
    }

    private class Device(private val context: Context) : PlannedJourneyReminderDevice {
        private val alarms = context.getSystemService(AlarmManager::class.java)
        private fun intent(key: String) = Intent(context, PlannedJourneyReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            data = Uri.Builder().scheme(URI_SCHEME).authority("reminder").appendPath(key).build()
        }

        override fun schedule(plan: PlannedJourneyReminderPlan) {
            val pending = PendingIntent.getBroadcast(context, 0, intent(plan.key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            scheduleWakeupAlarm(context, alarms, plan.trigger.toEpochMilli(), pending)
        }

        override fun cancel(key: String) {
            PendingIntent.getBroadcast(context, 0, intent(key),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { pending ->
                alarms.cancel(pending)
                pending.cancel()
            }
            NotificationManagerCompat.from(context).cancel(key, 0)
        }

        override fun notify(plan: PlannedJourneyReminderPlan) {
            // Permissions can be revoked between reconciliation and delivery.
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            if (!allowed(context)) return
            val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(context.resources.configuration.locales[0]).format(plan.departure.atZone(plan.zone))
            val text = context.getString(R.string.agenda_reminder_notification_text, time, plan.origin, plan.destination)
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = "binario://agenda".toUri()
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val public = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_binario)
                .setContentTitle(context.getString(R.string.agenda_reminder_channel)).build()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_binario)
                .setContentTitle(context.getString(R.string.agenda_reminder_notification_title, plan.title))
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
                .setContentIntent(open).setAutoCancel(true)
                .setTimeoutAfter((plan.departure.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1))
                .build()
            NotificationManagerCompat.from(context).notify(plan.key, 0, notification)
        }
    }
}

/** Non-exported alarm receiver; payloads contain only an opaque identifier. */
class PlannedJourneyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        AndroidPlannedJourneyReminders.dispatch(context, intent, pending::finish)
    }
}

/** System-protected broadcasts only; arbitrary actions never trigger restore work. */
class PlannedJourneyReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AndroidPlannedJourneyReminders.acceptsRestoreAction(intent.action)) return
        val pending = goAsync()
        AndroidPlannedJourneyReminders.dispatch(context, intent, pending::finish)
    }
}
