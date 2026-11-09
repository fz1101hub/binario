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
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.notices.NoticeDates
import it.binario.app.data.notices.RouteWorksMatcher
import it.binario.app.data.notices.RouteWorksNotice
import it.binario.app.data.notices.RouteWorksReason
import it.binario.app.data.notices.RouteWorksStations
import it.binario.app.data.notices.WorksRoute
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.shortcuts.RouteShortcutPlan
import it.binario.app.shortcuts.RouteShortcutStore
import it.binario.app.ui.common.LocalizedDates
import it.binario.app.ui.departures.RouteWorksViewModel
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One notice of the evening alert: works valid tomorrow and the saved route they concern, the first in Per te's order. */
data class RouteWorksAlertItem(val works: RouteWorksNotice, val reason: RouteWorksReason, val dates: NoticeDates)

/**
 * The evening alert of [day] (tomorrow): every notice due, in Per te's order of the routes, and every route they name
 * in that order (the title names the first one).
 */
data class RouteWorksAlert(val day: LocalDate, val due: List<RouteWorksAlertItem>, val routeNames: List<String>) {
    /** The notices shown, at most [RouteWorksAlertPlanner.MAX_ITEMS]. */
    val items: List<RouteWorksAlertItem> get() = due.take(RouteWorksAlertPlanner.MAX_ITEMS)

    /** Notices due tomorrow beyond the ones shown: the notification counts them and sends to Per te. */
    val hidden: Int get() = due.size - items.size
}

/**
 * «Lavori di domani sulle tue tratte»: one run a day at [RUN_AT]; a works or interruption notice that names a saved
 * route and whose dates include tomorrow is notified once, however many days it covers: the evening before the first
 * of its days the check sees as tomorrow. At most one notification an evening; a notice without readable dates never
 * triggers it, and nothing is shown before [RUN_AT] or in the quiet hours (22–7).
 */
object RouteWorksAlertPlanner {
    val RUN_AT: LocalTime = LocalTime.of(19, 0)
    val QUIET_FROM: LocalTime = LocalTime.of(22, 0)
    val QUIET_UNTIL: LocalTime = LocalTime.of(7, 0)
    const val MAX_ITEMS = 3
    /** The routes read, as in Per te: the pinned ones first, in the saved order. */
    const val MAX_ROUTES = RouteWorksViewModel.MAX_ROUTES
    /** A notified notice is remembered until the day after its last date, but never longer than this. */
    const val MAX_KEEP_DAYS = 45L
    const val MAX_NOTIFIED = 200

    fun quiet(now: ZonedDateTime): Boolean = !now.toLocalTime().isBefore(QUIET_FROM) || now.toLocalTime().isBefore(QUIET_UNTIL)

    /** Only in the evening window, [RUN_AT] – [QUIET_FROM]: a run Android delays into the night notifies nothing. */
    fun mayNotify(now: ZonedDateTime): Boolean = !now.toLocalTime().isBefore(RUN_AT) && !quiet(now)

    /**
     * The next run: today at [RUN_AT] before it, else tomorrow. With [immediate] (the user has just turned the alert on)
     * a time inside this evening's window is now, so tonight's alert is not lost.
     */
    fun nextRunAt(now: ZonedDateTime, immediate: Boolean): ZonedDateTime {
        if (immediate && mayNotify(now)) return now
        val today = now.toLocalDate().atTime(RUN_AT).atZone(now.zone)
        return if (now.isBefore(today)) today else now.toLocalDate().plusDays(1).atTime(RUN_AT).atZone(now.zone)
    }

    /** The saved routes Per te reads for its works: the pinned ones first, in the saved order, at most [MAX_ROUTES]. */
    fun routes(saved: List<SavedRoute>): List<SavedRoute> =
        saved.filter(RouteShortcutPlan::eligible).distinctBy { it.id }.sortedByDescending { it.isPinned }.take(MAX_ROUTES)

    fun validOn(dates: NoticeDates?, day: LocalDate): Boolean = dates != null && !day.isBefore(dates.first) && !day.isAfter(dates.last)

    /** Whether this evening ([today]) already had its alert ([lastAlertDay]): one notification an evening at most. */
    fun alertedOn(lastAlertDay: LocalDate?, today: LocalDate): Boolean = lastAlertDay == today

    /** Whether [noticeId] was notified already: [notified] keeps it, as id → last day kept (epoch day), until that day. */
    fun notifiedBefore(notified: Map<String, Long>, noticeId: String, today: LocalDate): Boolean =
        notified[noticeId]?.let { it >= today.toEpochDay() } == true

    /** The last day a notice notified on [today] is remembered: the day after its last date, at most [MAX_KEEP_DAYS] ahead. */
    fun keepUntil(dates: NoticeDates, today: LocalDate): LocalDate = minOf(dates.last.plusDays(1), today.plusDays(MAX_KEEP_DAYS))

    /**
     * The alert of tomorrow from the notices matched to [routeIds] (Per te's order), or null when none is due: works and
     * interruptions only (never a strike), with dates that include tomorrow, never notified before. Each notice goes
     * under the first route it names; within a route the notices keep the matcher's order.
     */
    fun select(works: List<RouteWorksNotice>, routeIds: List<String>, today: LocalDate, notified: Map<String, Long>): RouteWorksAlert? {
        val tomorrow = today.plusDays(1)
        val rank = routeIds.withIndex().associate { it.value to it.index }
        val due = works
            .filter { !it.notice.mentionsStrike && !notifiedBefore(notified, it.notice.id, today) }
            .mapNotNull { matched ->
                val dates = matched.dates?.takeIf { validOn(it, tomorrow) } ?: return@mapNotNull null
                val reason = matched.reasons.filter { it.routeId in rank }.minByOrNull { rank.getValue(it.routeId) } ?: return@mapNotNull null
                RouteWorksAlertItem(matched, reason, dates)
            }
            .distinctBy { it.works.notice.id }
            .sortedBy { rank.getValue(it.reason.routeId) }
        if (due.isEmpty()) return null
        val routeNames = due.flatMap { it.works.reasons }
            .filter { it.routeId in rank }
            .sortedBy { rank.getValue(it.routeId) }
            .distinctBy { it.routeId }
            .map { it.routeName }
        return RouteWorksAlert(tomorrow, due, routeNames)
    }

    /**
     * [notified] with every notice of [alert] (also those beyond the ones shown, which the notification counts) until
     * its [keepUntil], without the entries whose day is past; at most [MAX_NOTIFIED], the ones kept longest first.
     */
    fun remember(notified: Map<String, Long>, alert: RouteWorksAlert, today: LocalDate): Map<String, Long> {
        val added = alert.due.associate { it.works.notice.id to keepUntil(it.dates, today).toEpochDay() }
        val kept = notified.filter { (id, until) -> id !in added && until >= today.toEpochDay() }.entries
            .sortedByDescending { it.value }
            .take((MAX_NOTIFIED - added.size).coerceAtLeast(0))
            .associate { it.key to it.value }
        return added.entries.take(MAX_NOTIFIED).associate { it.key to it.value } + kept
    }
}

/** The texts of the alert, from the resources or from the test catalogue. */
class RouteWorksAlertStrings(
    val locale: Locale,
    /** «Domani lavori su %1$s». */
    val title: String,
    /** «Domani lavori su %1$s e su altre %2$d tratte». */
    val titleMore: (route: String, others: Int) -> String,
    /** «L’avviso cita: %1$s». */
    val names: String,
    /** «%1$s — l’avviso cita: %2$s». */
    val routeNames: String,
    /** «la linea %1$s». */
    val line: String,
    /** «Data citata: %1$s». */
    val date: String,
    /** «Date citate: dal %1$s al %2$s». */
    val dates: String,
    /** «E altri %d avvisi in Per te». */
    val more: (count: Int) -> String,
    val dateFormatter: DateTimeFormatter
)

/** The notification texts of a [RouteWorksAlert]: the route in the title, then what each notice names and when. */
object RouteWorksAlertText {
    fun title(alert: RouteWorksAlert, strings: RouteWorksAlertStrings): String {
        val others = alert.routeNames.size - 1
        val first = alert.routeNames.first()
        return if (others <= 0) String.format(strings.locale, strings.title, first) else strings.titleMore(first, others)
    }

    /** «L’avviso cita: la linea Milano – Venezia, Brescia · Date citate: dal 14 nov al 16 nov»; the route first when several. */
    fun summary(item: RouteWorksAlertItem, alert: RouteWorksAlert, strings: RouteWorksAlertStrings): String {
        val reason = item.reason
        val named = (listOfNotNull(reason.line?.let { String.format(strings.locale, strings.line, it) }) + reason.stations).joinToString(", ")
        val names = if (alert.routeNames.size > 1) String.format(strings.locale, strings.routeNames, reason.routeName, named)
        else String.format(strings.locale, strings.names, named)
        val dates = item.dates
        val first = dates.first.format(strings.dateFormatter)
        val datesText = if (dates.first == dates.last) String.format(strings.locale, strings.date, first)
        else String.format(strings.locale, strings.dates, first, dates.last.format(strings.dateFormatter))
        return "$names · $datesText"
    }

    /** Every notice shown, its summary and then its own title; the count of the notices left to Per te closes it. */
    fun lines(alert: RouteWorksAlert, strings: RouteWorksAlertStrings): List<String> = buildList {
        alert.items.forEach { item -> add(summary(item, alert, strings) + "\n" + item.works.notice.title) }
        if (alert.hidden > 0) add(strings.more(alert.hidden))
    }
}

/** The notices already notified, as id → the last day they are kept (epoch day), and the day of the last alert. */
internal class RouteWorksAlertStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_route_works_alert_v1", Context.MODE_PRIVATE)

    fun notified(): Map<String, Long> = runCatching {
        val value = JSONObject(preferences.getString(KEY_NOTIFIED, null) ?: return emptyMap())
        value.keys().asSequence().take(RouteWorksAlertPlanner.MAX_NOTIFIED).associateWith { value.optLong(it) }
    }.getOrDefault(emptyMap())

    val lastAlertDay: LocalDate?
        get() = preferences.getString(KEY_LAST_ALERT, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The notices of this evening's alert and its day, together. */
    fun saveAlert(notified: Map<String, Long>, day: LocalDate) = preferences.edit {
        putString(KEY_NOTIFIED, JSONObject().apply { notified.entries.take(RouteWorksAlertPlanner.MAX_NOTIFIED).forEach { (id, until) -> put(id, until) } }.toString())
        putString(KEY_LAST_ALERT, day.toString())
    }

    private companion object {
        const val KEY_NOTIFIED = "notified"
        const val KEY_LAST_ALERT = "last_alert_day"
    }
}

/**
 * The works alert's chain of one-time works: one run a day at 19, with the network. Off by default; on only with the
 * setting, notifications allowed and RFI's feed enabled.
 */
object RouteWorksAlertScheduler {
    internal const val WORK = "binario-route-works-alert"
    private val networkConnected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** [keepPending] leaves a scheduled run alone (app start); otherwise the setting was just turned on or off. */
    fun reschedule(context: Context, keepPending: Boolean = false) {
        val workManager = WorkManager.getInstance(context)
        if (!LiveNotificationSettings(context).routeWorksAlertEnabled || !LiveNotifications.allowed(context) ||
            !BinarioDataGraph.routeWorksAlertNoticesService().hasNetworkAlertSources
        ) {
            workManager.cancelUniqueWork(WORK)
            return
        }
        enqueue(context, if (keepPending) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, fromWorker = false)
    }

    internal fun enqueue(context: Context, policy: ExistingWorkPolicy, fromWorker: Boolean) {
        val now = ZonedDateTime.now(RailTime.defaultZone)
        val minimum = if (fromWorker) Duration.ofMinutes(1) else Duration.ZERO
        val delay = Duration.between(now, RouteWorksAlertPlanner.nextRunAt(now, immediate = !fromWorker)).let { if (it < minimum) minimum else it }
        val request = OneTimeWorkRequestBuilder<RouteWorksAlertWorker>()
            .setConstraints(networkConnected)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }
}

class RouteWorksAlertWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val settings = LiveNotificationSettings(context)
        try {
            val now = ZonedDateTime.now(RailTime.defaultZone)
            if (settings.routeWorksAlertEnabled && LiveNotifications.allowed(context) && RouteWorksAlertPlanner.mayNotify(now)) alert(context, now.toLocalDate())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The feed is unreachable or unreadable: nothing is shown, tomorrow's run tries again.
        } finally {
            if (!isStopped && settings.routeWorksAlertEnabled) RouteWorksAlertScheduler.enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE, fromWorker = true)
        }
        Result.success()
    }

    private suspend fun alert(context: Context, today: LocalDate) {
        val store = RouteWorksAlertStore(context)
        if (RouteWorksAlertPlanner.alertedOn(store.lastAlertDay, today)) return
        val notified = store.notified()
        // The saved routes as the app last wrote them for its shortcuts; without any, no request at all.
        val routes = RouteWorksAlertPlanner.routes(RouteShortcutStore(context).routes())
        if (routes.isEmpty()) return
        val notices = BinarioDataGraph.routeWorksAlertNoticesService().general(includeTrenord = false).network
        if (notices.isEmpty()) return
        val tomorrow = today.plusDays(1)
        val worksRoutes = routes.map { route -> WorksRoute(route.id, route.name, RouteWorksStations.of(context, route, tomorrow)) }
        val works = RouteWorksMatcher(catalogue(context)).match(notices, worksRoutes, today)
        val alert = RouteWorksAlertPlanner.select(works, routes.map { it.id }, today, notified) ?: return
        val resources = context.resources
        val locale = resources.configuration.locales[0]
        val strings = RouteWorksAlertStrings(
            locale = locale,
            title = resources.getString(R.string.route_works_alert_title),
            titleMore = { route, others -> resources.getQuantityString(R.plurals.route_works_alert_title_more, others, route, others) },
            names = resources.getString(R.string.route_works_names),
            routeNames = resources.getString(R.string.route_works_route_names),
            line = resources.getString(R.string.route_works_line),
            date = resources.getString(R.string.route_works_date),
            dates = resources.getString(R.string.route_works_dates),
            more = { count -> resources.getQuantityString(R.plurals.route_works_alert_more, count, count) },
            dateFormatter = LocalizedDates.dayMonth(locale)
        )
        val notification = NotificationCompat.Builder(context, LiveChannel.ROUTE_WORKS.ensure(context))
            .setSmallIcon(R.drawable.ic_binario)
            .setContentTitle(RouteWorksAlertText.title(alert, strings))
            .setContentText(RouteWorksAlertText.summary(alert.items.first(), alert, strings))
            .setStyle(NotificationCompat.BigTextStyle().bigText(RouteWorksAlertText.lines(alert, strings).joinToString("\n\n")))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(LiveNotifications.openForYou(context, LiveNotifications.ROUTE_WORKS_ID))
            .build()
        // The matching can take a while: the quiet hours are checked again right before posting.
        if (!RouteWorksAlertPlanner.mayNotify(ZonedDateTime.now(RailTime.defaultZone))) return
        if (LiveNotifications.post(context, LiveNotifications.ROUTE_WORKS_ID, notification)) {
            store.saveAlert(RouteWorksAlertPlanner.remember(notified, alert, today), today)
        }
    }

    private fun catalogue(context: Context): List<Station> =
        runCatching { EmbeddedCuratedRailStationCatalog({ path -> context.assets.open(path) }).load() }.getOrDefault(emptyList())
}
