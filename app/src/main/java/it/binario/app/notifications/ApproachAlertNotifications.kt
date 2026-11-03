package it.binario.app.notifications

import android.content.Context
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
import it.binario.app.FavouriteTrainMatcher
import it.binario.app.FavouriteTrainStore
import it.binario.app.R
import it.binario.app.ServiceMonitor
import it.binario.app.data.RailTime
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.repository.TrainNumberQuery
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
 * the moment to notify when it comes before the next check (WorkManager, inexact like the other live notifications; no
 * exact alarm). No network constraint: the run at the moment to notify uses the last observation when offline.
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
        val next = alerts.mapNotNull { alert ->
            val state = store.state(alert.key)
            ApproachAlertPlanner.nextRun(alert, state.doneOn, state.notifiedOn, state.observation.takeIf { state.date == today }, now)
        }.minOrNull()
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
        if (alerts.isEmpty()) return
        val store = ApproachAlertStore(context)
        val zone = RailTime.defaultZone
        alerts.forEach { alert ->
            val now = Instant.now()
            val today = now.atZone(zone).toLocalDate()
            val state = store.state(alert.key).let { if (it.date == today) it else ApproachAlertCodec.DayState(today, it.doneOn, it.notifiedOn, null) }
            val window = ApproachAlertPlanner.window(alert, today, zone) ?: return@forEach
            if (state.doneOn == today || state.notifiedOn == today || now.isBefore(window.start.minus(ApproachAlertPlanner.EARLY_TOLERANCE)) || now.isAfter(window.endInclusive)) {
                return@forEach
            }
            val monitor = favourites.firstOrNull { TrainAlertWatch.key(it.number) == alert.key } ?: return@forEach
            // Offline or without an answer, the last observation of the day (or the timetable) decides.
            val observation = lookUp(context, monitor, alert, today) ?: state.observation
            when (val decision = ApproachAlertPlanner.decide(alert, today, observation, now, notified = false, zone)) {
                is ApproachDecision.Notify -> {
                    if (LiveNotifications.allowed(context)) notify(context, alert, monitor, observation, decision.minutesLeft)
                    store.saveState(alert.key, state.copy(notifiedOn = today, observation = observation))
                }
                ApproachDecision.Done -> store.saveState(alert.key, state.copy(doneOn = today, observation = observation))
                is ApproachDecision.WaitUntil -> store.saveState(alert.key, state.copy(observation = observation))
            }
        }
    }

    private suspend fun lookUp(context: Context, monitor: ServiceMonitor, alert: ApproachAlert, date: LocalDate): ApproachObservation? = try {
        withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) {
            val search = BinarioDataGraph.trainNumberSearchService(context)
            val matches = search.search(TrainNumberQuery(monitor.number, date)).lastOrNull()?.matches.orEmpty()
            FavouriteTrainMatcher.pick(matches.map { it.service }, monitor)?.let { ApproachAlertPlanner.observe(it, alert, date) }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun notify(context: Context, alert: ApproachAlert, monitor: ServiceMonitor, observation: ApproachObservation?, minutes: Int) {
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
            .setContentIntent(LiveNotifications.openApp(context, alert.key.hashCode()))
            .build()
        LiveNotifications.post(context, NOTIFICATION_BASE + (alert.key.hashCode() and 0xFFFF), notification)
    }

    private companion object {
        const val NOTIFICATION_BASE = 0x5EC00000
        const val LOOKUP_TIMEOUT_MILLIS = 30_000L
    }
}
