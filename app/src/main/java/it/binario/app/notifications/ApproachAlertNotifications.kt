package it.binario.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.binario.app.CommuterAlertStatusLookup
import it.binario.app.CommuterAlertStatusRules
import it.binario.app.CommuterLiveStatus
import it.binario.app.FavouriteTrainStore
import it.binario.app.R
import it.binario.app.ServiceMonitor
import it.binario.app.data.RailTime
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.scheduleWakeupAlarm
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The approach alerts of the favourite trains and their state of the day, in local preferences (never in the backup). */
internal class ApproachAlertStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_approach_alerts_v1", Context.MODE_PRIVATE)

    fun alerts(): List<ApproachAlert> = ApproachAlertCodec.decode(preferences.getString(KEY_ALERTS, null))

    fun alert(number: String): ApproachAlert? = alerts().firstOrNull { it.key == TrainAlertWatch.key(number) }

    fun save(alert: ApproachAlert) = preferences.edit {
        val updated = (listOf(alert) + alerts().filterNot { it.key == alert.key }).take(ApproachAlertPlanner.MAX_ALERTS)
        putString(KEY_ALERTS, ApproachAlertCodec.encode(updated))
        // A changed station or lead starts the day afresh.
        remove(stateKey(alert.key))
    }

    fun remove(number: String) = preferences.edit {
        val key = TrainAlertWatch.key(number)
        val updated = alerts().filterNot { it.key == key }
        if (updated.isEmpty()) remove(KEY_ALERTS) else putString(KEY_ALERTS, ApproachAlertCodec.encode(updated))
        remove(stateKey(key))
    }

    /** Forgets the alerts of trains that are no longer favourites. */
    fun retainOnly(active: List<ApproachAlert>) {
        val all = alerts()
        if (all.size == active.size) return
        val keep = active.mapTo(hashSetOf()) { it.key }
        preferences.edit {
            if (active.isEmpty()) remove(KEY_ALERTS) else putString(KEY_ALERTS, ApproachAlertCodec.encode(active))
            all.filterNot { it.key in keep }.forEach { remove(stateKey(it.key)) }
        }
    }

    fun state(key: String): ApproachAlertCodec.DayState = ApproachAlertCodec.decodeState(preferences.getString(stateKey(key), null))

    fun saveState(key: String, state: ApproachAlertCodec.DayState) = preferences.edit { putString(stateKey(key), ApproachAlertCodec.encodeState(state)) }

    private fun stateKey(key: String) = "state_$key"

    private companion object {
        const val KEY_ALERTS = "alerts"
    }
}

/**
 * The approach alerts' chain of one-time works: a check every 15 minutes in each alert's window, and a one-shot run at
 * the moment to notify when it comes before the next check. No network constraint: the run at the moment to notify
 * uses the last observation when offline. Doze can defer those runs past the moment, so a wake-up alarm
 * ([ApproachAlertAlarms], exact when Android allows it) also delivers it from the last observation of the day.
 */
object ApproachAlertScheduler {
    internal const val WORK = "binario-approach-alerts"

    internal fun active(context: Context): Pair<List<ApproachAlert>, List<ServiceMonitor>> {
        val favourites = FavouriteTrainStore(context).all()
        val store = ApproachAlertStore(context)
        val active = ApproachAlertPlanner.active(store.alerts(), favourites.map { it.number })
        store.retainOnly(active)
        return active to favourites
    }

    fun reschedule(context: Context) = schedule(context.applicationContext, fromWorker = false)

    internal fun schedule(context: Context, fromWorker: Boolean) {
        val workManager = WorkManager.getInstance(context)
        val (alerts, _) = active(context)
        val store = ApproachAlertStore(context)
        val now = Instant.now()
        val today = now.atZone(RailTime.defaultZone).toLocalDate()
        // Without the permission to notify the checks could show nothing: no lookup runs, the next app start plans again.
        val allowed = LiveNotifications.allowed(context, LiveChannel.APPROACH)
        val next = if (!allowed) null else alerts.mapNotNull { alert ->
            val state = store.state(alert.key)
            val date = ApproachAlertPlanner.serviceDate(alert, today)
            val observation = state.observation.takeIf { state.date == date }
            ApproachAlertAlarms.set(context, alert.key, ApproachAlertPlanner.alarmAt(alert, state.doneOn, state.notifiedOn, observation, now))
            ApproachAlertPlanner.nextRun(alert, state.doneOn, state.notifiedOn, observation, now)
        }.minOrNull()
        if (!allowed) alerts.forEach { ApproachAlertAlarms.set(context, it.key, null) }
        if (next == null) {
            if (!fromWorker) workManager.cancelUniqueWork(WORK)
            return
        }
        val minimum = if (fromWorker) ApproachAlertPlanner.MIN_DELAY_BETWEEN_RUNS else Duration.ZERO
        val delay = Duration.between(now, next).let { if (it < minimum) minimum else it }
        val request = OneTimeWorkRequestBuilder<ApproachAlertWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(WORK)
            .build()
        workManager.enqueueUniqueWork(WORK, if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
    }
}

/** Checks the favourite trains whose approach alert is due, notifies the ones about to arrive, plans the next run. */
class ApproachAlertWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        try {
            check(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed run is retried by the next one; the chain goes on.
        } finally {
            if (!isStopped) ApproachAlertScheduler.schedule(context, fromWorker = true)
        }
        Result.success()
    }

    private suspend fun check(context: Context) {
        val (alerts, favourites) = ApproachAlertScheduler.active(context)
        if (alerts.isEmpty() || !LiveNotifications.allowed(context, LiveChannel.APPROACH)) return
        val store = ApproachAlertStore(context)
        val zone = RailTime.defaultZone
        alerts.forEach { alert ->
            val now = Instant.now()
            // The run reaching the station today: for a station after midnight, the one that left yesterday. Its
            // departure date is the date of the lookup and of the day state.
            val date = ApproachAlertPlanner.serviceDate(alert, now.atZone(zone).toLocalDate(), zone)
            val state = store.state(alert.key).let { if (it.date == date) it else ApproachAlertCodec.DayState(date, it.doneOn, it.notifiedOn, null) }
            val window = ApproachAlertPlanner.window(alert, date, zone) ?: return@forEach
            if (state.doneOn == date || state.notifiedOn == date || now.isBefore(window.start.minus(ApproachAlertPlanner.EARLY_TOLERANCE)) || now.isAfter(window.endInclusive)) {
                return@forEach
            }
            val monitor = favourites.firstOrNull { TrainAlertWatch.key(it.number) == alert.key } ?: return@forEach
            // Offline or without an answer, the last observation of the day (or the timetable) decides; a day the sources
            // say the train does not run, or does not call at the station, is over.
            val lookup = lookUp(context, monitor, alert, date)
            val observation = ApproachAlertPlanner.observationAfter(lookup, state.observation)
            val decided = Instant.now()
            synchronized(ApproachAlertDelivery.lock) {
                // The wake-up alarm may have notified while the sources were asked.
                if (store.state(alert.key).notifiedOn == date) return@forEach
                when (val decision = ApproachAlertPlanner.decide(alert, date, lookup, state.observation, decided, zone)) {
                    is ApproachDecision.Notify -> {
                        if (LiveNotifications.allowed(context)) ApproachAlertDelivery.notify(context, alert, monitor, observation, decision.minutesLeft)
                        store.saveState(alert.key, state.copy(notifiedOn = date, observation = observation))
                    }
                    ApproachDecision.Done -> store.saveState(alert.key, state.copy(doneOn = date, observation = observation))
                    is ApproachDecision.WaitUntil -> store.saveState(alert.key, state.copy(observation = observation))
                }
            }
        }
    }

    private suspend fun lookUp(context: Context, monitor: ServiceMonitor, alert: ApproachAlert, date: LocalDate): ApproachLookup {
        // The last update of the search keeps whether every source answered; a timeout or a failure gives no update.
        val update = try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) {
                BinarioDataGraph.trainNumberSearchService(context).search(TrainNumberQuery(monitor.number, date)).lastOrNull()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return ApproachAlertPlanner.lookupOf(update, monitor, alert, date)
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MILLIS = 30_000L
    }
}

/** Posts an approach alert; shared by the checks and the wake-up alarm, which never notify the same day twice. */
internal object ApproachAlertDelivery {
    /** Guards «read the day state, notify, save it» between a check and the alarm (both run in the app process). */
    val lock = Any()
    private const val NOTIFICATION_BASE = 0x5EC00000

    /**
     * The wake-up alarm of [key] rang at [now]: notifies from the observation the last check of the day stored, when
     * the rules still say so (no request: the check that planned the alarm asked the sources). A day already
     * notified or finished, or an alarm early or late beyond the tolerances, is left to the checks.
     */
    fun atAlarm(context: Context, key: String, now: Instant) {
        if (!LiveNotifications.allowed(context, LiveChannel.APPROACH)) return
        val (alerts, favourites) = ApproachAlertScheduler.active(context)
        val alert = alerts.firstOrNull { it.key == key } ?: return
        val monitor = favourites.firstOrNull { TrainAlertWatch.key(it.number) == key } ?: return
        val zone = RailTime.defaultZone
        val date = ApproachAlertPlanner.serviceDate(alert, now.atZone(zone).toLocalDate(), zone)
        val store = ApproachAlertStore(context)
        synchronized(lock) {
            val state = store.state(key)
            if (state.date != date || state.doneOn == date || state.notifiedOn == date) return
            val decision = ApproachAlertPlanner.decide(alert, date, state.observation, now, notified = false, zone)
            if (decision is ApproachDecision.Notify) {
                notify(context, alert, monitor, state.observation, decision.minutesLeft)
                store.saveState(key, state.copy(notifiedOn = date))
            }
        }
    }

    fun notify(context: Context, alert: ApproachAlert, monitor: ServiceMonitor, observation: ApproachObservation?, minutes: Int) {
        val label = monitor.displayName ?: alert.label.ifBlank { alert.number }
        val title = if (minutes <= 0) {
            context.getString(R.string.approach_alert_title_now, label, alert.station)
        } else {
            context.getString(R.string.approach_alert_title, label, alert.station, context.resources.getQuantityString(R.plurals.minutes_short, minutes, minutes))
        }
        val text = if (observation?.live == true) {
            val status = CommuterLiveStatus(observation.delayMinutes ?: 0, observation.platform, cancelled = false)
            context.getString(R.string.commuter_alert_notification_status, CommuterAlertStatusRules.format(status, CommuterAlertStatusLookup.strings(context)))
        } else {
            context.getString(R.string.journey_transfer_scheduled_only)
        }
        val notification = NotificationCompat.Builder(context, LiveChannel.APPROACH.ensure(context))
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            // «Arriva tra ~10 min» means nothing once the train has gone: off the shade a while after its expected time.
            .setTimeoutAfter(ApproachAlertPlanner.NOTIFICATION_TIMEOUT.plusMinutes(minutes.toLong().coerceAtLeast(0)).toMillis())
            .setContentIntent(LiveNotifications.openApp(context, alert.key.hashCode()))
            .build()
        LiveNotifications.post(context, NOTIFICATION_BASE + (alert.key.hashCode() and 0xFFFF), notification)
    }
}

/** The wake-up alarm of each approach alert at its moment to notify (one per alert; a new moment replaces the old). */
internal object ApproachAlertAlarms {
    private const val ACTION = "it.binario.app.action.APPROACH_ALERT"
    private const val EXTRA_KEY = "it.binario.app.extra.APPROACH_ALERT_KEY"

    /** Sets the alarm of [key] at [at], or cancels it when null. */
    fun set(context: Context, key: String, at: Instant?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            "approach-alert|$key".hashCode(),
            Intent(context, ApproachAlertReceiver::class.java).setAction(ACTION).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (at == null) alarmManager.cancel(pending) else scheduleWakeupAlarm(context, alarmManager, at.toEpochMilli(), pending)
    }

    fun key(intent: Intent): String? = intent.getStringExtra(EXTRA_KEY)?.takeIf { intent.action == ACTION }
}

/** The approach alert's wake-up alarm: delivers the notification from the stored observation, no request. */
class ApproachAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = ApproachAlertAlarms.key(intent) ?: return
        ApproachAlertDelivery.atAlarm(context.applicationContext, key, Instant.now())
    }
}
