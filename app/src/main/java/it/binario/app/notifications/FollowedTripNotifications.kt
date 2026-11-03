package it.binario.app.notifications

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
import it.binario.app.R
import it.binario.app.StopAlarmScheduler
import it.binario.app.data.RailTime
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainService
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.gpsalarm.GpsArrivalAlarmController
import it.binario.app.gpsalarm.GpsArrivalAlarmRuntime
import it.binario.app.journeys.TransferRiskLevel
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The followed trip of the ongoing notification and what the last refresh showed, in local preferences. */
internal class FollowedTripStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_followed_trip_v1", Context.MODE_PRIVATE)

    fun trip(): FollowedTrip? = FollowedTripCodec.decode(preferences.getString(KEY_TRIP, null))

    fun save(trip: FollowedTrip) = preferences.edit {
        clear()
        putString(KEY_TRIP, FollowedTripCodec.encode(trip))
    }

    fun clear() = preferences.edit { clear() }

    val lastDelayMinutes: Int? get() = preferences.getInt(KEY_DELAY, -1).takeIf { it >= 0 }
    val lastTitle: String? get() = preferences.getString(KEY_TITLE, null)
    val lastText: String? get() = preferences.getString(KEY_TEXT, null)
    val lastUpdatedAt: Long get() = preferences.getLong(KEY_UPDATED, 0L)

    /** Records the official status only: an estimate from the device position is never written here. */
    fun recordShown(delayMinutes: Int?, title: String, text: String, at: Long) = preferences.edit {
        if (delayMinutes != null) putInt(KEY_DELAY, delayMinutes)
        putString(KEY_TITLE, title)
        putString(KEY_TEXT, text)
        putLong(KEY_UPDATED, at)
    }

    /** The changes already notified with sound for this trip ([FollowedTripAlerts]); cleared with the trip. */
    fun alertedKeys(): Set<String> = preferences.getStringSet(KEY_ALERTED, null)?.toSet().orEmpty()

    fun recordAlerted(keys: Collection<String>) = preferences.edit { putStringSet(KEY_ALERTED, alertedKeys() + keys) }

    /** The boarding platform the last refresh read, to notice a change the source does not mark itself. */
    fun lastBoarding(): FollowedTripAlerts.BoardingPlatform? {
        val platform = preferences.getString(KEY_BOARD_PLATFORM, null) ?: return null
        return FollowedTripAlerts.BoardingPlatform(preferences.getInt(KEY_BOARD_LEG, 0), preferences.getString(KEY_BOARD_STATION, null).orEmpty(), platform, null)
    }

    fun recordBoarding(boarding: FollowedTripAlerts.BoardingPlatform?) = preferences.edit {
        if (boarding == null) {
            remove(KEY_BOARD_LEG)
            remove(KEY_BOARD_STATION)
            remove(KEY_BOARD_PLATFORM)
        } else {
            putInt(KEY_BOARD_LEG, boarding.legIndex)
            putString(KEY_BOARD_STATION, boarding.station)
            putString(KEY_BOARD_PLATFORM, boarding.platform)
        }
    }

    private companion object {
        const val KEY_TRIP = "trip"
        const val KEY_ALERTED = "alerted"
        const val KEY_BOARD_LEG = "board_leg"
        const val KEY_BOARD_STATION = "board_station"
        const val KEY_BOARD_PLATFORM = "board_platform"
        const val KEY_DELAY = "last_delay"
        const val KEY_TITLE = "last_title"
        const val KEY_TEXT = "last_text"
        const val KEY_UPDATED = "last_updated"
    }
}

/**
 * The trains the last refresh read, in memory only, so that the GPS session can update the countdown and its estimate
 * in the notification without asking the sources again. It never outlives the process.
 */
internal object FollowedTripLiveCache {
    private val MAX_AGE: Duration = Duration.ofMinutes(20)

    private data class Entry(val tripId: String, val services: List<TrainService?>, val at: Instant)

    @Volatile
    private var entry: Entry? = null

    fun record(tripId: String, services: List<TrainService?>, at: Instant) {
        if (services.any { it != null }) entry = Entry(tripId, services, at)
    }

    fun services(tripId: String, now: Instant): Pair<List<TrainService?>, Instant>? =
        entry?.takeIf { it.tripId == tripId && Duration.between(it.at, now) <= MAX_AGE }?.let { it.services to it.at }

    fun clear() {
        entry = null
    }
}

/**
 * One unique one-time work per followed trip, re-enqueued by each run for the next refresh: nothing is scheduled
 * before the window opens, and the chain ends with the window.
 */
object FollowedTripScheduler {
    internal const val WORK = "binario-followed-trip"

    fun current(context: Context): FollowedTrip? = FollowedTripStore(context).trip()

    /** Starts the ongoing notification for [trip], replacing a previous one; the first run is at the window start. */
    fun start(context: Context, trip: FollowedTrip) {
        FollowedTripStore(context).save(trip)
        LiveNotifications.cancel(context, LiveNotifications.FOLLOWED_TRIP_ID)
        enqueue(context, Instant.now(), ExistingWorkPolicy.REPLACE)
        LiveNotificationScheduling.onFavouritesChanged(context)
    }

    /** Removes the notification and stops every refresh: the "Interrompi" action, a swipe, or the end of the trip. */
    fun stop(context: Context) {
        val app = context.applicationContext
        FollowedTripStore(app).clear()
        FollowedTripLiveCache.clear()
        WorkManager.getInstance(app).cancelUniqueWork(WORK)
        LiveNotifications.cancel(app, LiveNotifications.FOLLOWED_TRIP_ID)
        // The position estimate exists only for a followed trip: it ends with it (a GPS alarm keeps running).
        runCatching { GpsArrivalAlarmController.stopEstimate(app) }
        runCatching { TrainAlertScheduler.reschedule(app) }
    }

    /** Whether the followed trip includes [trainNumber]. */
    internal fun follows(context: Context, trainNumber: String): Boolean =
        TrainAlertWatch.key(trainNumber) in current(context)?.trainNumbers.orEmpty()

    /**
     * The official delay of [trainNumber] the last refresh read, for the "(fonte ufficiale: +4 min)" next to an
     * estimate; null when that train had no live data.
     */
    internal fun officialDelayMinutes(context: Context, trainNumber: String): Int? {
        val trip = current(context) ?: return null
        val (services, _) = FollowedTripLiveCache.services(trip.id, Instant.now()) ?: return null
        val index = trip.legs.indexOfFirst { TrainAlertWatch.key(it.number) == TrainAlertWatch.key(trainNumber) }
        val service = services.getOrNull(index)?.takeIf { it.operationalStatusVerified && it.status != ServiceStatus.CANCELLED } ?: return null
        return (service.officialDelayMinutes ?: service.delayMinutes).coerceAtLeast(0)
    }

    /**
     * Shows the notification again from the trains read by the last refresh, with the current GPS progress: no source
     * is asked. Nothing happens without a recent refresh in this process.
     */
    internal fun repaint(context: Context) {
        val app = context.applicationContext
        val store = FollowedTripStore(app)
        val trip = store.trip() ?: return
        val now = Instant.now()
        val (services, readAt) = FollowedTripLiveCache.services(trip.id, now) ?: return
        if (!LiveNotifications.allowed(app)) return
        val view = FollowedTripStatus.view(trip, services, now, position = GpsArrivalAlarmRuntime.currentTrack())
        val content = FollowedTripText.content(app, trip, view, answered = true, updatedAt = FollowedTripNotification.time(readAt))
        val end = FollowedTripPlanner.windowEnd(trip, store.lastDelayMinutes)
        LiveNotifications.post(app, LiveNotifications.FOLLOWED_TRIP_ID, FollowedTripNotification.build(app, content, now, end))
    }

    /**
     * Keeps the notification of a single followed train in step with "Segui viaggio": [ending] stops it when it
     * follows that train ("Termina"); otherwise starting [trainNumber] stops one that follows another train. A
     * journey with changes followed from its detail is left alone.
     */
    fun syncWithActiveTrip(context: Context, trainNumber: String, ending: Boolean) {
        val trip = current(context)?.takeIf { it.legs.size == 1 } ?: return
        val same = TrainAlertWatch.key(trainNumber) in trip.trainNumbers
        if (same == ending) stop(context)
    }

    fun refreshNow(context: Context) {
        if (current(context) != null) enqueue(context, Instant.now(), ExistingWorkPolicy.REPLACE)
    }

    /** After a start of the app: keeps a pending refresh, restarts a chain that a force stop removed. */
    fun reschedule(context: Context) {
        val trip = current(context) ?: return
        when (val step = FollowedTripPlanner.step(trip, Instant.now(), FollowedTripStore(context).lastDelayMinutes)) {
            FollowedTripPlanner.Step.Finish -> stop(context)
            is FollowedTripPlanner.Step.WaitUntil -> enqueue(context, step.at, ExistingWorkPolicy.KEEP)
            is FollowedTripPlanner.Step.Refresh -> enqueue(context, Instant.now(), ExistingWorkPolicy.KEEP)
        }
    }

    internal fun enqueue(context: Context, at: Instant, policy: ExistingWorkPolicy) {
        val delay = Duration.between(Instant.now(), at).toMillis().coerceAtLeast(0)
        // No network constraint: the run that ends the trip must remove the notification even offline.
        val request = OneTimeWorkRequestBuilder<FollowedTripWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }
}

class FollowedTripWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val store = FollowedTripStore(context)
        val trip = store.trip() ?: run {
            LiveNotifications.cancel(context, LiveNotifications.FOLLOWED_TRIP_ID)
            return@withContext Result.success()
        }
        try {
            when (val step = FollowedTripPlanner.step(trip, Instant.now(), store.lastDelayMinutes)) {
                FollowedTripPlanner.Step.Finish -> FollowedTripScheduler.stop(context)
                is FollowedTripPlanner.Step.WaitUntil -> FollowedTripScheduler.enqueue(context, step.at, ExistingWorkPolicy.APPEND_OR_REPLACE)
                is FollowedTripPlanner.Step.Refresh -> {
                    val result = if (LiveNotifications.allowed(context)) refresh(context, store, trip) else RefreshResult(store.lastDelayMinutes, null)
                    val now = Instant.now()
                    val next = FollowedTripPlanner.step(trip, now, result.delayMinutes)
                    if (!isStopped && FollowedTripStore(context).trip()?.id == trip.id) {
                        when (next) {
                            is FollowedTripPlanner.Step.Refresh -> FollowedTripScheduler.enqueue(
                                context,
                                FollowedTripPlanner.nextRefreshAt(next.nextAt, now, result.countdown),
                                ExistingWorkPolicy.APPEND_OR_REPLACE
                            )
                            is FollowedTripPlanner.Step.WaitUntil -> FollowedTripScheduler.enqueue(context, next.at, ExistingWorkPolicy.APPEND_OR_REPLACE)
                            FollowedTripPlanner.Step.Finish -> FollowedTripScheduler.stop(context)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed refresh keeps the last notification; the next run (or the timeout) still ends it.
            if (!isStopped) FollowedTripScheduler.enqueue(context, Instant.now().plus(FollowedTripPlanner.INTERVAL), ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        Result.success()
    }

    private data class RefreshResult(val delayMinutes: Int?, val countdown: StopCountdown?)

    /** Looks the running trains up, posts the notification and returns the arrival delay that stretches the window. */
    private suspend fun refresh(context: Context, store: FollowedTripStore, trip: FollowedTrip): RefreshResult {
        val now = Instant.now()
        val services = lookUp(context, trip, now)
        FollowedTripLiveCache.record(trip.id, services, now)
        // A stop alarm on one of these trains follows its expected time: a late train does not ring early.
        runCatching { StopAlarmScheduler.follow(context, services, now.toEpochMilli()) }
        val delay = FollowedTripStatus.arrivalDelayMinutes(trip, services)
        // The window end (and the notification's timeout) follows the delay just read, not the previous one.
        val end = FollowedTripPlanner.windowEnd(trip, delay ?: store.lastDelayMinutes)
        val view = FollowedTripStatus.view(trip, services, now, position = GpsArrivalAlarmRuntime.currentTrack())
        val content = if (services.all { it == null } && store.lastTitle != null) {
            // Nothing answered: the last status stays, saying how old it is.
            val stale = context.getString(R.string.followed_trip_stale, FollowedTripNotification.time(Instant.ofEpochMilli(store.lastUpdatedAt)))
            val last = stale + "\n" + store.lastText.orEmpty()
            FollowedTripContent(store.lastTitle.orEmpty(), stale, last, last)
        } else {
            FollowedTripText.content(context, trip, view, services.any { it != null }, FollowedTripNotification.time(now))
        }
        val notification = FollowedTripNotification.build(context, content, now, end)
        // A stop requested while the trains were being looked up wins over this refresh.
        if (FollowedTripStore(context).trip()?.id != trip.id) return RefreshResult(null, null)
        if (LiveNotifications.post(context, LiveNotifications.FOLLOWED_TRIP_ID, notification) && services.any { it != null }) {
            store.recordShown(delay, content.title, content.recordedText, now.toEpochMilli())
        }
        // A cancellation, a platform change or a connection at risk also gets one notification with sound.
        if (services.any { it != null }) runCatching { FollowedTripAlertNotifier.notify(context, store, trip, view, services, now) }
        return RefreshResult(delay ?: store.lastDelayMinutes, view.countdown.takeIf { services.any { it != null } })
    }

    /** Only the trains of the trip that run now or soon are asked, at most two (the current one and the next). */
    private suspend fun lookUp(context: Context, trip: FollowedTrip, now: Instant): List<TrainService?> {
        val search = BinarioDataGraph.trainNumberSearchService(context)
        val relevant = trip.legs.indices.filter { index ->
            val leg = trip.legs[index]
            !leg.departure.isAfter(now.plus(LOOKAHEAD)) && !leg.arrival.plus(LOOKBACK).isBefore(now)
        }.ifEmpty { listOf(trip.legs.indices.firstOrNull { trip.legs[it].arrival.isAfter(now) } ?: trip.legs.lastIndex) }.take(2)
        return coroutineScope {
            trip.legs.mapIndexed { index, leg ->
                async {
                    if (index !in relevant) return@async null
                    try {
                        withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) {
                            val date = leg.departure.atZone(RailTime.defaultZone).toLocalDate()
                            val matches = search.search(TrainNumberQuery(leg.number, date)).lastOrNull()?.matches.orEmpty()
                            FollowedTripStatus.pick(matches.map { it.service }, leg)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MILLIS = 30_000L
        val LOOKAHEAD: Duration = Duration.ofHours(2)
        val LOOKBACK: Duration = Duration.ofHours(3)
    }
}

/** [recordedText] is [bigText] without the position estimate: what the store may keep for an offline refresh. */
internal data class FollowedTripContent(val title: String, val text: String, val bigText: String, val recordedText: String = bigText)

/** The ongoing notification itself, for a refresh and for a repaint from the GPS session. */
internal object FollowedTripNotification {
    private const val REQUEST_OPEN = 0x5EA1

    fun time(instant: Instant): String = instant.atZone(RailTime.defaultZone).format(DateTimeFormatter.ofPattern("H:mm", Locale.ROOT))

    fun build(context: Context, content: FollowedTripContent, now: Instant, end: Instant): android.app.Notification =
        NotificationCompat.Builder(context, LiveChannel.FOLLOWED_TRIP.ensure(context))
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.bigText))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(now.toEpochMilli())
            // Removed by the system at the end of the window even if no later run happens (offline, Doze).
            .setTimeoutAfter(Duration.between(now, end).toMillis().coerceAtLeast(60_000L))
            .setContentIntent(LiveNotifications.openApp(context, REQUEST_OPEN))
            .setDeleteIntent(FollowedTripActionReceiver.intent(context, FollowedTripActionReceiver.ACTION_STOP))
            .addAction(0, context.getString(R.string.action_refresh), FollowedTripActionReceiver.intent(context, FollowedTripActionReceiver.ACTION_REFRESH))
            .addAction(0, context.getString(R.string.followed_trip_action_stop), FollowedTripActionReceiver.intent(context, FollowedTripActionReceiver.ACTION_STOP))
            .build()
}

/** Localized text of the ongoing notification. */
internal object FollowedTripText {
    fun content(context: Context, trip: FollowedTrip, view: FollowedTripView, answered: Boolean, updatedAt: String): FollowedTripContent {
        val label = view.leg.label.ifBlank { view.leg.number }
        val title = when {
            !answered -> context.getString(R.string.followed_trip_title_unknown, label)
            view.cancelled -> context.getString(R.string.followed_trip_title_cancelled, label)
            !view.live -> context.getString(R.string.followed_trip_title_scheduled, label)
            (view.delayMinutes ?: 0) > 0 -> context.getString(
                R.string.followed_trip_title_delay, label,
                context.resources.getQuantityString(R.plurals.minutes_short, view.delayMinutes ?: 0, view.delayMinutes ?: 0)
            )
            else -> context.getString(R.string.followed_trip_title_on_time, label)
        }
        val stop = view.nextStop
        val time = view.nextStopTime
        val countdown = view.countdown
        val text = when {
            // At the last stop before getting off the countdown line names it, with time and platform.
            countdown?.alightAtNextStop == true -> TripLiveText.countdown(context.resources, countdown, view.platform)
            stop != null && time != null && view.platform != null -> context.getString(R.string.personal_travel_next_stop_platform, stop, time, view.platform)
            stop != null && time != null -> context.getString(R.string.personal_travel_next_stop, stop, time)
            else -> trip.title
        }
        val countdownHead = countdown?.takeUnless { it.alightAtNextStop }?.let { TripLiveText.countdown(context.resources, it) }
        val official = if (view.live && !view.cancelled) view.delayMinutes ?: 0 else null
        val estimate = TripLiveText.position(context.resources, view.estimate, view.trackStatus, official, System.currentTimeMillis())
        val transfer = view.transfer?.let { assessment ->
            val risk = when (assessment.level) {
                TransferRiskLevel.MISSED -> R.string.journey_transfer_missed
                TransferRiskLevel.AT_RISK -> R.string.journey_transfer_at_risk
                TransferRiskLevel.OK -> R.string.journey_transfer_confirmed
                TransferRiskLevel.UNKNOWN -> R.string.journey_transfer_scheduled_only
            }
            context.getString(R.string.followed_trip_transfer, view.transferStation.orEmpty(), view.nextLegLabel.orEmpty()) + " · " + context.getString(risk)
        }
        val updated = context.getString(R.string.followed_trip_updated_at, updatedAt)
        val big = listOfNotNull(countdownHead, text, estimate, transfer, updated).joinToString("\n")
        val recorded = listOfNotNull(countdownHead, text, transfer, updated).joinToString("\n")
        val collapsed = when {
            countdown?.alightAtNextStop == true -> text
            transfer != null -> transfer
            countdownHead != null -> "$countdownHead · $text"
            else -> text
        }
        return FollowedTripContent(title, collapsed, big, recorded)
    }
}

/** "Aggiorna" and "Interrompi" of the ongoing notification, and its dismissal. Not exported. */
class FollowedTripActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> FollowedTripScheduler.refreshNow(context.applicationContext)
            ACTION_STOP -> FollowedTripScheduler.stop(context.applicationContext)
        }
    }

    companion object {
        const val ACTION_REFRESH = "it.binario.app.FOLLOWED_TRIP_REFRESH"
        const val ACTION_STOP = "it.binario.app.FOLLOWED_TRIP_STOP"

        internal fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, FollowedTripActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
