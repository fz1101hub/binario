package it.binario.app.notifications

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import it.binario.app.R
import it.binario.app.widget.WidgetAutoRefreshScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The periodic checks Binario keeps planned with WorkManager, each under its own unique work name, in the order
 * «Attività in background» lists them. Every one is a chain of one-time works that plans its next run itself.
 */
enum class BackgroundCheck(val uniqueWork: String, val labelRes: Int) {
    FOLLOWED_TRIP(FollowedTripScheduler.WORK, R.string.followed_trip_channel_name),
    TRAIN_ALERTS(TrainAlertScheduler.WORK, R.string.train_alerts_channel_name),
    APPROACH_ALERTS(ApproachAlertScheduler.WORK, R.string.approach_alert_channel_name),
    PUNCTUALITY(PunctualityRecorderScheduler.WORK, R.string.settings_punctuality_recording_title),
    STRIKES(StrikeReminderScheduler.WORK, R.string.settings_strike_reminder_title),
    WIDGETS(WidgetAutoRefreshScheduler.WORK, R.string.settings_widgets_title)
}

/** What WorkManager reports for one work of a chain, without its library type. */
data class BackgroundWorkEntry(val state: State, val nextScheduleTimeMillis: Long = Long.MAX_VALUE) {
    enum class State { ENQUEUED, RUNNING, BLOCKED, FINISHED }
}

/** The works recorded under a unique name; a seam so the panel's rules run without WorkManager. */
fun interface BackgroundWorkQuery {
    suspend fun entries(uniqueWork: String): List<BackgroundWorkEntry>
}

/** [BackgroundWorkQuery] on WorkManager's own records; a query that fails or takes over two seconds reads as none. */
class WorkManagerBackgroundWorkQuery(private val workManager: WorkManager) : BackgroundWorkQuery {
    constructor(context: Context) : this(WorkManager.getInstance(context.applicationContext))

    override suspend fun entries(uniqueWork: String): List<BackgroundWorkEntry> = try {
        withTimeoutOrNull(QUERY_TIMEOUT_MILLIS) { entriesOf(workManager.getWorkInfosForUniqueWorkFlow(uniqueWork).first()) }.orEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        private const val QUERY_TIMEOUT_MILLIS = 2_000L

        fun entriesOf(infos: List<WorkInfo>): List<BackgroundWorkEntry> = infos.map { info ->
            BackgroundWorkEntry(
                state = when (info.state) {
                    WorkInfo.State.ENQUEUED -> BackgroundWorkEntry.State.ENQUEUED
                    WorkInfo.State.RUNNING -> BackgroundWorkEntry.State.RUNNING
                    WorkInfo.State.BLOCKED -> BackgroundWorkEntry.State.BLOCKED
                    WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> BackgroundWorkEntry.State.FINISHED
                },
                nextScheduleTimeMillis = info.nextScheduleTimeMillis
            )
        }
    }
}

/** When a check runs next, as the panel says it. */
sealed interface BackgroundRunTime {
    data object Running : BackgroundRunTime
    /** Already due: waiting for the network, Doze or the battery optimisation. */
    data object Soon : BackgroundRunTime
    data class Today(val time: LocalTime) : BackgroundRunTime
    data class Tomorrow(val time: LocalTime) : BackgroundRunTime
    data class Later(val date: LocalDate, val time: LocalTime) : BackgroundRunTime
}

/** One line of «Prossimi controlli»: the check and when it runs. */
data class BackgroundRun(val check: BackgroundCheck, val time: BackgroundRunTime)

/** The texts of the panel, resolved from the resources on screen and from the resource files in the tests. */
class BackgroundRunStrings(
    val locale: Locale,
    val label: (BackgroundCheck) -> String,
    /** «%1$s: in corso». */
    val running: String,
    /** «%1$s: a breve». */
    val soon: String,
    /** «%1$s: oggi alle %2$s». */
    val today: String,
    /** «%1$s: domani alle %2$s». */
    val tomorrow: String,
    /** «%1$s: %2$s alle %3$s». */
    val later: String,
    val timeFormatter: DateTimeFormatter,
    val dateFormatter: DateTimeFormatter
)

/** Pure rules of «Prossimi controlli in background». */
object BackgroundRuns {
    /**
     * The next run of one chain: running when one of its works runs; else the earliest work waiting for its time. A
     * blocked work (chained after the running one) and a finished one have no time; no work at all is no run.
     */
    fun nextRun(entries: List<BackgroundWorkEntry>, now: ZonedDateTime): BackgroundRunTime? {
        if (entries.any { it.state == BackgroundWorkEntry.State.RUNNING }) return BackgroundRunTime.Running
        val next = entries
            .filter { it.state == BackgroundWorkEntry.State.ENQUEUED && it.nextScheduleTimeMillis in 1 until Long.MAX_VALUE }
            .minOfOrNull { it.nextScheduleTimeMillis }
            ?: return null
        val at = Instant.ofEpochMilli(next).atZone(now.zone)
        if (!at.isAfter(now)) return BackgroundRunTime.Soon
        val time = at.toLocalTime().withSecond(0).withNano(0)
        val today = now.toLocalDate()
        return when (at.toLocalDate()) {
            today -> BackgroundRunTime.Today(time)
            today.plusDays(1) -> BackgroundRunTime.Tomorrow(time)
            else -> BackgroundRunTime.Later(at.toLocalDate(), time)
        }
    }

    /** Every check with a planned or running work, in the order of [BackgroundCheck]. */
    suspend fun planned(query: BackgroundWorkQuery, now: ZonedDateTime): List<BackgroundRun> =
        BackgroundCheck.entries.mapNotNull { check -> nextRun(query.entries(check.uniqueWork), now)?.let { BackgroundRun(check, it) } }

    fun text(run: BackgroundRun, strings: BackgroundRunStrings): String {
        val label = strings.label(run.check)
        return when (val time = run.time) {
            BackgroundRunTime.Running -> String.format(strings.locale, strings.running, label)
            BackgroundRunTime.Soon -> String.format(strings.locale, strings.soon, label)
            is BackgroundRunTime.Today -> String.format(strings.locale, strings.today, label, time.time.format(strings.timeFormatter))
            is BackgroundRunTime.Tomorrow -> String.format(strings.locale, strings.tomorrow, label, time.time.format(strings.timeFormatter))
            is BackgroundRunTime.Later ->
                String.format(strings.locale, strings.later, label, time.date.format(strings.dateFormatter), time.time.format(strings.timeFormatter))
        }
    }
}
