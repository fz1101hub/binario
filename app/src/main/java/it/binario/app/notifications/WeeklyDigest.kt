package it.binario.app.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.binario.app.FavouriteTrainPunctuality
import it.binario.app.FavouriteTrainStore
import it.binario.app.LocalPunctuality
import it.binario.app.PunctualityHistory
import it.binario.app.PunctualityHistoryStore
import it.binario.app.PunctualityObservation
import it.binario.app.R
import it.binario.app.ServiceMonitor
import it.binario.app.data.RailTime
import it.binario.app.data.reliability.NetworkReliabilityRepository
import it.binario.app.widget.CatalogueStationCodes
import it.binario.app.widget.ReliabilityMark
import it.binario.app.widget.ReliabilityMarks
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One favourite train in the weekly digest: its runs recorded this week and in the four weeks before, and its
 * reliability in the embedded network statistics when they have it.
 */
data class DigestTrain(
    val monitor: ServiceMonitor,
    val week: LocalPunctuality?,
    val earlier: LocalPunctuality?,
    val network: ReliabilityMark?
)

/** The train whose on-time share dropped most this week, against [usualPercent] (earlier weeks or the network). */
data class DigestWorse(val train: DigestTrain, val weekPercent: Int, val usualPercent: Int)

data class WeeklyDigest(val weekEnd: LocalDate, val trains: List<DigestTrain>, val worse: DigestWorse?) {
    val runs: Int get() = trains.sumOf { it.week?.runs ?: 0 }
    val onTime: Int get() = trains.sumOf { it.week?.onTime ?: 0 }
}

/**
 * When the weekly digest comes: Sunday at [RUN_AT], for the week Monday–Sunday that ends that day. A run that Android
 * delays still notifies until Monday [LATEST], never in the quiet hours (22–7): then it waits for 7:00.
 */
object WeeklyDigestPlanner {
    val RUN_DAY: DayOfWeek = DayOfWeek.SUNDAY
    val RUN_AT: LocalTime = LocalTime.of(19, 0)
    val QUIET_FROM: LocalTime = LocalTime.of(22, 0)
    val QUIET_UNTIL: LocalTime = LocalTime.of(7, 0)
    val LATEST: LocalTime = LocalTime.of(12, 0)

    /** The Sunday whose digest may be shown at [now], or null outside Sunday [RUN_AT] – Monday [LATEST]. */
    fun weekEndAt(now: ZonedDateTime): LocalDate? {
        val date = now.toLocalDate()
        val time = now.toLocalTime()
        return when {
            date.dayOfWeek == RUN_DAY && !time.isBefore(RUN_AT) -> date
            date.dayOfWeek == RUN_DAY.plus(1) && time.isBefore(LATEST) -> date.minusDays(1)
            else -> null
        }
    }

    fun quiet(now: ZonedDateTime): Boolean = !now.toLocalTime().isBefore(QUIET_FROM) || now.toLocalTime().isBefore(QUIET_UNTIL)

    fun mayNotify(now: ZonedDateTime): Boolean = weekEndAt(now) != null && !quiet(now)

    /**
     * The next run: now while the digest of this window is still to send (7:00 in the quiet hours), else the next
     * Sunday at [RUN_AT].
     */
    fun nextRunAt(now: ZonedDateTime, lastSentWeekEnd: LocalDate?): ZonedDateTime {
        val pending = weekEndAt(now)?.takeIf { it != lastSentWeekEnd }
        if (pending != null) {
            if (!quiet(now)) return now
            val morning = if (now.toLocalTime().isBefore(QUIET_UNTIL)) now.toLocalDate() else now.toLocalDate().plusDays(1)
            val wake = morning.atTime(QUIET_UNTIL).atZone(now.zone)
            if (weekEndAt(wake) == pending) return wake
        }
        val sunday = now.toLocalDate().with(TemporalAdjusters.nextOrSame(RUN_DAY)).atTime(RUN_AT).atZone(now.zone)
        return if (sunday.isAfter(now)) sunday else sunday.plusWeeks(1)
    }

    /** Monday of the week that ends on [weekEnd]. */
    fun weekStart(weekEnd: LocalDate): LocalDate = weekEnd.minusDays(6)

    /**
     * What a run does with the week it finds open ([weekEndAt]): the week ending on [weekEnd] is handled (notified, or
     * given up for the week when the digest could not be built), so [nextRunAt] moves to next Sunday. A run with the
     * setting off, notifications blocked or in the quiet hours handles nothing.
     */
    fun handles(enabled: Boolean, notificationsAllowed: Boolean, now: ZonedDateTime, lastSentWeekEnd: LocalDate?): LocalDate? =
        weekEndAt(now)?.takeIf { enabled && notificationsAllowed && mayNotify(now) && it != lastSentWeekEnd }

    /**
     * Whether a run plans the next one: only with the setting on and notifications allowed. Otherwise [nextRunAt] would
     * be «now» for the whole window, a run every minute; the app start plans the chain again.
     */
    fun continuesAfterRun(enabled: Boolean, notificationsAllowed: Boolean): Boolean = enabled && notificationsAllowed
}

/**
 * Pure rules of the digest: what each favourite train did ([FavouriteTrainPunctuality.local], shared with «Affidabilità
 * dei miei treni» in Per te), and which one got worse.
 */
object WeeklyDigestBuilder {
    /** The earlier weeks compared with this one. */
    const val EARLIER_DAYS = 28L
    const val MIN_WEEK_RUNS = 2
    const val MIN_EARLIER_RUNS = 3
    /** A drop of at least this many percentage points names the train. */
    const val MIN_DROP_POINTS = 20

    /**
     * The digest of the week ending on [weekEnd], or null when no favourite train has anything to show (no run recorded
     * this week and no network statistics).
     */
    fun build(
        monitors: List<ServiceMonitor>,
        history: List<PunctualityObservation>,
        weekEnd: LocalDate,
        network: (ServiceMonitor) -> ReliabilityMark?
    ): WeeklyDigest? {
        val weekStart = WeeklyDigestPlanner.weekStart(weekEnd)
        val trains = monitors.map { monitor ->
            DigestTrain(
                monitor = monitor,
                week = FavouriteTrainPunctuality.local(history, monitor.number, weekStart, weekEnd),
                earlier = FavouriteTrainPunctuality.local(history, monitor.number, weekStart.minusDays(EARLIER_DAYS), weekStart.minusDays(1)),
                network = network(monitor)
            )
        }.filter { it.week != null || it.network != null }
        if (trains.isEmpty()) return null
        return WeeklyDigest(weekEnd, trains, worse(trains))
    }

    /**
     * The train with the largest drop of its on-time share this week (at least [MIN_WEEK_RUNS] runs) against its
     * earlier weeks on this phone (at least [MIN_EARLIER_RUNS] runs), or, without them, against the network share
     * within the same 5 minutes the local history uses; only a drop of [MIN_DROP_POINTS] points or more counts.
     */
    fun worse(trains: List<DigestTrain>): DigestWorse? = trains.mapNotNull { train ->
        val week = train.week?.takeIf { it.runs >= MIN_WEEK_RUNS } ?: return@mapNotNull null
        val usual = train.earlier?.takeIf { it.runs >= MIN_EARLIER_RUNS }?.onTimePercent ?: train.network?.within5Percent ?: return@mapNotNull null
        DigestWorse(train, week.onTimePercent, usual).takeIf { usual - week.onTimePercent >= MIN_DROP_POINTS }
    }.maxWithOrNull(
        compareBy<DigestWorse> { it.usualPercent - it.weekPercent }
            .thenBy { it.train.week?.runs ?: 0 }
            .thenByDescending { it.train.monitor.number }
    )
}

/** The texts of the digest notification, as functions of its numbers; from the resources or the test catalogue. */
class WeeklyDigestStrings(
    val locale: Locale,
    /** «Treno %1$s». */
    val trainNumber: String,
    /** «%1$s: %2$s». */
    val trainLine: String,
    /** «In orario %1$d volte su %2$d». */
    val onTimeCount: (onTime: Int, runs: Int) -> String,
    /** «soppresso %1$d volta». */
    val cancelled: (count: Int) -> String,
    /** «nessuna corsa registrata». */
    val noRuns: String,
    /** «ultimi %1$d giorni sulla rete: %2$d%% in orario». */
    val network: (days: Int, percent: Int) -> String,
    /** «Peggiorato: %1$s, in orario %2$d%% questa settimana (di solito %3$d%%)». */
    val worse: String,
    /** «Statistiche non ufficiali di Binario su dati ViaggiaTreno, aggiornate al %1$s». */
    val attribution: String,
    /** «%1$s (entro %2$s)»: a count or share with the on-time limit it counts against. */
    val within: (text: String, limitMinutes: Int) -> String
)

/** The notification texts of a [WeeklyDigest]: a summary line and one line per train. */
object WeeklyDigestText {
    fun title(train: DigestTrain, strings: WeeklyDigestStrings): String =
        train.monitor.displayName ?: String.format(strings.locale, strings.trainNumber, train.monitor.number)

    fun line(train: DigestTrain, strings: WeeklyDigestStrings): String {
        val parts = buildList {
            val week = train.week
            if (week == null) add(strings.noRuns) else {
                add(strings.within(strings.onTimeCount(week.onTime, week.runs), PunctualityHistory.ON_TIME_MINUTES))
                if (week.cancelled > 0) add(strings.cancelled(week.cancelled))
            }
            // The network share counts against the train's own limit (15 minutes for a regional train), the week within 5.
            train.network?.let { add(strings.within(strings.network(it.days, it.percent), it.limitMinutes)) }
        }
        return String.format(strings.locale, strings.trainLine, title(train, strings), parts.joinToString(" · "))
    }

    fun worse(worse: DigestWorse, strings: WeeklyDigestStrings): String =
        String.format(strings.locale, strings.worse, title(worse.train, strings), worse.weekPercent, worse.usualPercent)

    /** The collapsed line: the train that got worse, else the week's total, else the first train's line. */
    fun summary(digest: WeeklyDigest, strings: WeeklyDigestStrings): String = when {
        digest.worse != null -> worse(digest.worse, strings)
        digest.runs > 0 -> strings.within(strings.onTimeCount(digest.onTime, digest.runs), PunctualityHistory.ON_TIME_MINUTES)
        else -> line(digest.trains.first(), strings)
    }

    /** Every line of the expanded notification; the attribution closes it when a network share is shown. */
    fun lines(digest: WeeklyDigest, strings: WeeklyDigestStrings, statsEnd: LocalDate?): List<String> = buildList {
        digest.worse?.let { add(worse(it, strings)) }
        digest.trains.forEach { add(line(it, strings)) }
        if (statsEnd != null && digest.trains.any { it.network != null }) {
            add(String.format(strings.locale, strings.attribution, statsEnd.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(strings.locale))))
        }
    }
}

internal class WeeklyDigestStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_weekly_digest_v1", Context.MODE_PRIVATE)

    var lastSentWeekEnd: LocalDate?
        get() = preferences.getString(KEY_LAST_SENT, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(value) = preferences.edit { if (value == null) remove(KEY_LAST_SENT) else putString(KEY_LAST_SENT, value.toString()) }

    private companion object {
        const val KEY_LAST_SENT = "last_sent_week_end"
    }
}

/**
 * The weekly digest's chain of one-time works: one run a week (no network constraint: it reads only the local history
 * and the embedded statistics). Off by default; on only with the setting and notifications allowed.
 */
object WeeklyDigestScheduler {
    internal const val WORK = "binario-weekly-digest"

    fun reschedule(context: Context, keepPending: Boolean = false) {
        val workManager = WorkManager.getInstance(context)
        if (!LiveNotificationSettings(context).weeklyDigestEnabled || !LiveNotifications.allowed(context)) {
            workManager.cancelUniqueWork(WORK)
            return
        }
        enqueue(context, if (keepPending) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, fromWorker = false)
    }

    internal fun enqueue(context: Context, policy: ExistingWorkPolicy, fromWorker: Boolean) {
        val now = ZonedDateTime.now(RailTime.defaultZone)
        val minimum = if (fromWorker) Duration.ofMinutes(1) else Duration.ZERO
        val delay = Duration.between(now, WeeklyDigestPlanner.nextRunAt(now, WeeklyDigestStore(context).lastSentWeekEnd)).let { if (it < minimum) minimum else it }
        val request = OneTimeWorkRequestBuilder<WeeklyDigestWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }
}

class WeeklyDigestWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val settings = LiveNotificationSettings(context)
        try {
            val now = ZonedDateTime.now(RailTime.defaultZone)
            val store = WeeklyDigestStore(context)
            WeeklyDigestPlanner.handles(settings.weeklyDigestEnabled, LiveNotifications.allowed(context), now, store.lastSentWeekEnd)?.let { weekEnd ->
                try {
                    notify(context, weekEnd)
                } finally {
                    // Also with nothing to say, or unreadable history or statistics: the week is over, next week's run
                    // tries again (without this a failing build would run again every minute).
                    store.lastSentWeekEnd = weekEnd
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Nothing is shown.
        } finally {
            if (!isStopped && WeeklyDigestPlanner.continuesAfterRun(settings.weeklyDigestEnabled, LiveNotifications.allowed(context))) {
                WeeklyDigestScheduler.enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE, fromWorker = true)
            }
        }
        Result.success()
    }

    private suspend fun notify(context: Context, weekEnd: LocalDate) {
        val monitors = FavouriteTrainStore(context).all()
        if (monitors.isEmpty()) return
        val history = PunctualityHistoryStore(context).read()
        val stats = NetworkReliabilityRepository.load(context)
        val codes = CatalogueStationCodes.originCodes(context, monitors)
        val digest = WeeklyDigestBuilder.build(monitors, history, weekEnd) { monitor ->
            stats?.let { ReliabilityMarks.favourite(it, monitor.number, codes[monitor.number]) }
        } ?: return
        val resources = context.resources
        val strings = WeeklyDigestStrings(
            locale = resources.configuration.locales[0],
            trainNumber = resources.getString(R.string.train_number_label),
            trainLine = resources.getString(R.string.weekly_digest_train_line),
            onTimeCount = { onTime, runs -> resources.getQuantityString(R.plurals.reliability_on_time_count, onTime, onTime, runs) },
            cancelled = { count -> resources.getQuantityString(R.plurals.reliability_cancelled_count, count, count) },
            noRuns = resources.getString(R.string.weekly_digest_no_runs),
            network = { days, percent -> resources.getQuantityString(R.plurals.weekly_digest_network, days, days, percent) },
            worse = resources.getString(R.string.weekly_digest_worse),
            attribution = resources.getString(R.string.reliability_attribution),
            within = { text, minutes ->
                resources.getString(R.string.reliability_within_limit, text, resources.getQuantityString(R.plurals.minutes_short, minutes, minutes))
            }
        )
        val lines = WeeklyDigestText.lines(digest, strings, stats?.windowEnd)
        val notification = NotificationCompat.Builder(context, LiveChannel.WEEKLY_DIGEST.ensure(context))
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle(resources.getString(R.string.weekly_digest_title))
            .setContentText(WeeklyDigestText.summary(digest, strings))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(LiveNotifications.openApp(context, LiveNotifications.WEEKLY_DIGEST_ID))
            .build()
        LiveNotifications.post(context, LiveNotifications.WEEKLY_DIGEST_ID, notification)
    }
}
