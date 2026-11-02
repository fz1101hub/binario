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
import it.binario.app.FavouriteTrainStore
import it.binario.app.R
import it.binario.app.data.RailTime
import it.binario.app.data.repository.BinarioDataGraph
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bells of the train detail and the notice ids already seen, in local preferences. */
internal class TrainAlertStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_train_alerts_v1", Context.MODE_PRIVATE)

    fun subscriptions(): List<TrainAlertSubscription> = TrainAlertCodec.decodeSubscriptions(preferences.getString(KEY_SUBSCRIPTIONS, null))

    fun isSubscribed(number: String): Boolean = subscriptions().any { it.key == TrainAlertWatch.key(number) }

    fun subscribe(subscription: TrainAlertSubscription) = saveSubscriptions(
        (listOf(subscription) + subscriptions().filterNot { it.key == subscription.key }).take(TrainAlertWatch.MAX_SUBSCRIPTIONS)
    )

    fun unsubscribe(number: String) = saveSubscriptions(subscriptions().filterNot { it.key == TrainAlertWatch.key(number) })

    fun seen(): Map<String, Long> = TrainAlertCodec.decodeSeen(preferences.getString(KEY_SEEN, null))

    fun saveSeen(seen: Map<String, Long>) = preferences.edit { putString(KEY_SEEN, TrainAlertCodec.encodeSeen(seen)) }

    private fun saveSubscriptions(subscriptions: List<TrainAlertSubscription>) = preferences.edit {
        if (subscriptions.isEmpty()) remove(KEY_SUBSCRIPTIONS) else putString(KEY_SUBSCRIPTIONS, TrainAlertCodec.encodeSubscriptions(subscriptions))
    }

    private companion object {
        const val KEY_SUBSCRIPTIONS = "subscriptions"
        const val KEY_SEEN = "seen"
    }
}

/**
 * Checks the notices of the trains with the bell on in their windows ([TrainAlertWatch]): a chain of one-time works
 * with a network constraint, each scheduling the next; with the setting off or no train to watch there is none.
 */
object TrainAlertScheduler {
    internal const val WORK = "binario-train-alerts"
    private val networkConnected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    internal fun watched(context: Context): Pair<List<TrainAlertSubscription>, List<FollowedTrainTimes>> {
        val followed = FollowedTripStore(context).trip()?.legs.orEmpty().map { FollowedTrainTimes(it.number, it.departure, it.arrival) }
        val active = TrainAlertWatch.active(
            TrainAlertStore(context).subscriptions(),
            FavouriteTrainStore(context).all().map { it.number },
            followed.map { it.number }
        )
        return active to followed
    }

    fun reschedule(context: Context) = schedule(context, fromWorker = false)

    internal fun schedule(context: Context, fromWorker: Boolean) {
        val workManager = WorkManager.getInstance(context)
        val (subscriptions, followed) = watched(context)
        val now = Instant.now()
        val next = if (LiveNotificationSettings(context).trainAlertsEnabled) {
            TrainAlertWatch.nextRun(subscriptions, followed, now, RailTime.defaultZone)
        } else null
        if (next == null) {
            if (!fromWorker) workManager.cancelUniqueWork(WORK)
            return
        }
        val minimum = if (fromWorker) Duration.ofMinutes(1) else Duration.ZERO
        val delay = Duration.between(now, next).let { if (it < minimum) minimum else it }
        val request = OneTimeWorkRequestBuilder<TrainAlertWorker>()
            .setConstraints(networkConnected)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(WORK)
            .build()
        workManager.enqueueUniqueWork(WORK, if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
    }
}

class TrainAlertWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        try {
            if (LiveNotificationSettings(context).trainAlertsEnabled && LiveNotifications.allowed(context)) check(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A source that fails is asked again at the next run; the chain goes on.
        } finally {
            if (!isStopped) TrainAlertScheduler.schedule(context, fromWorker = true)
        }
        Result.success()
    }

    private suspend fun check(context: Context) {
        val now = Instant.now()
        val (subscriptions, followed) = TrainAlertScheduler.watched(context)
        val due = TrainAlertWatch.due(subscriptions, followed, now, RailTime.defaultZone)
        if (due.isEmpty()) return
        val store = TrainAlertStore(context)
        var seen = store.seen()
        val today = now.atZone(RailTime.defaultZone).toLocalDate()
        val service = BinarioDataGraph.trainAlertNoticesService()
        val english = context.resources.configuration.locales[0].language == Locale.ENGLISH.language
        due.forEach { subscription ->
            val notices = service.forTrain(subscription.noticeContext(today))
            val watched = notices.trainNotices.map { WatchedNotice(it.id, (if (english) it.englishText else null) ?: it.text, it.publishedAt) } +
                notices.networkNotices.map { WatchedNotice(it.id, listOfNotNull(it.title, it.body).joinToString("\n"), it.publishedAt) }
            val fresh = TrainAlertWatch.newNotices(watched, seen, subscription.subscribedAt)
            if (fresh.isNotEmpty()) notify(context, subscription, fresh)
            seen = TrainAlertWatch.remember(seen, watched, today)
        }
        store.saveSeen(seen)
    }

    private fun notify(context: Context, subscription: TrainAlertSubscription, fresh: List<WatchedNotice>) {
        val label = subscription.label.ifBlank { subscription.number }
        val title = context.resources.getQuantityString(R.plurals.train_alerts_notification_title, fresh.size, fresh.size, label)
        val notification = NotificationCompat.Builder(context, LiveChannel.TRAIN_ALERTS.ensure(context))
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle(title)
            .setContentText(fresh.first().text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fresh.joinToString("\n\n") { it.text }))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(LiveNotifications.openApp(context, subscription.key.hashCode()))
            .build()
        LiveNotifications.post(context, NOTIFICATION_BASE + (subscription.key.hashCode() and 0xFFFF), notification)
    }

    private companion object {
        const val NOTIFICATION_BASE = 0x5EB00000
    }
}
