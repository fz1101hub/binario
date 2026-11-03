package it.binario.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.binario.app.data.Direction
import it.binario.app.data.RailTime
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class WidgetKind { ROUTE, STATION, TRAINS }

/**
 * One widget instance as the scheduler sees it. [lastRefreshEpochMillis] is the later of the last
 * data it received (from any refresh, the app included) and the last automatic attempt.
 */
data class AutoRefreshCandidate(
    val kind: WidgetKind,
    val widgetId: Int,
    val settings: AutoRefreshSettings,
    val lastRefreshEpochMillis: Long,
    /** Route widgets with "Mostra il ritorno dalle": the next instant the automatic direction changes. */
    val nextDirectionBoundary: ZonedDateTime? = null,
    /** Set when the direction the route widget should show differs from the one it shows. */
    val pendingDirection: Direction? = null
)

/** What one run of the scheduler does for one widget. */
data class AutoRefreshAction(val candidate: AutoRefreshCandidate, val fetch: Boolean, val applyDirection: Direction?)

/**
 * The scheduling decisions, without Android: which widgets a run refreshes and when the next run
 * is needed. A widget is refreshed only inside its windows (or always, for the pre-settings
 * behaviour) and never sooner than its interval, at least fifteen minutes, after its last refresh.
 */
object WidgetAutoRefreshPlanner {
    /** Floor between two runs scheduled by a run, so that a refresh that records nothing cannot loop. */
    val MIN_RESCHEDULE: Duration = Duration.ofMinutes(1)

    fun plan(candidates: List<AutoRefreshCandidate>, now: ZonedDateTime): List<AutoRefreshAction> = candidates.mapNotNull { candidate ->
        val due = candidate.settings.isDue(candidate.lastRefreshEpochMillis, now)
        val direction = candidate.pendingDirection
        // The swap to the return at the chosen time loads the return trains once; the swap back at
        // midnight only changes the label, unless a refresh window is open anyway.
        val fetchForDirection = direction != null && (direction == Direction.RETURN || candidate.settings.isActiveAt(now))
        if (!due && direction == null) null
        else AutoRefreshAction(candidate, fetch = due || fetchForDirection, applyDirection = direction)
    }

    fun due(candidates: List<AutoRefreshCandidate>, now: ZonedDateTime): List<AutoRefreshCandidate> =
        plan(candidates, now).filter { it.fetch }.map { it.candidate }

    /** The next instant a run has something to do, or null when no widget needs automatic work. */
    fun nextRun(candidates: List<AutoRefreshCandidate>, now: ZonedDateTime): ZonedDateTime? = candidates.mapNotNull { candidate ->
        if (candidate.pendingDirection != null) return@mapNotNull now
        listOfNotNull(
            candidate.settings.nextDueAt(candidate.lastRefreshEpochMillis, now),
            candidate.nextDirectionBoundary
        ).minOrNull()
    }.minOrNull()

    fun delayUntil(next: ZonedDateTime, now: ZonedDateTime, minimum: Duration = Duration.ZERO): Duration {
        val delay = Duration.between(now, next)
        return if (delay < minimum) minimum else delay
    }
}

/** Last automatic attempt per widget, so a failing source is not asked again before the interval. */
object WidgetAutoRefreshStore {
    private const val PREFS = "binario_widget_auto_refresh"

    fun lastAttempt(context: Context, kind: WidgetKind, widgetId: Int): Long = prefs(context).getLong(key(kind, widgetId), 0L)

    fun recordAttempt(context: Context, kind: WidgetKind, widgetId: Int, epochMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(key(kind, widgetId), epochMillis) }
    }

    fun clear(context: Context, kind: WidgetKind, widgetId: Int) {
        prefs(context).edit { remove(key(kind, widgetId)) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(kind: WidgetKind, widgetId: Int) = "${kind.name.lowercase()}_$widgetId"
}

/**
 * The widget instances the launcher still shows. A configuration whose widget is gone (removed while the app could
 * not receive onDeleted, a launcher reset) is dropped, so that no refresh is planned or made for it.
 */
internal object WidgetInstances {
    fun orphans(stored: Collection<Int>, present: IntArray): Set<Int> = stored.toSet() - present.toSet()

    /** Removes the records of widgets the launcher no longer has; nothing is removed when Android cannot tell. */
    fun pruneOrphans(context: Context) {
        val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
        fun present(provider: Class<*>): IntArray? = runCatching { manager.getAppWidgetIds(ComponentName(context, provider)) }.getOrNull()
        present(RouteWidgetProvider::class.java)?.let { ids ->
            val store = WidgetConfigurationStore(context)
            orphans(store.all().map { it.first }, ids).forEach { widgetId ->
                store.remove(widgetId)
                WidgetStateStore.clear(context, widgetId)
                WidgetAutoRefreshStore.clear(context, WidgetKind.ROUTE, widgetId)
            }
        }
        present(StationWidgetProvider::class.java)?.let { ids ->
            val store = StationWidgetConfigurationStore(context)
            orphans(store.all().map { it.first }, ids).forEach { widgetId ->
                store.remove(widgetId)
                StationWidgetStateStore.clear(context, widgetId)
                WidgetAutoRefreshStore.clear(context, WidgetKind.STATION, widgetId)
            }
        }
        present(TrainsWidgetProvider::class.java)?.let { ids ->
            val store = TrainsWidgetConfigurationStore(context)
            orphans(store.all().map { it.first }, ids).forEach { widgetId ->
                store.remove(widgetId)
                WidgetAutoRefreshStore.clear(context, WidgetKind.TRAINS, widgetId)
            }
        }
    }
}

/** Reads the three widget configuration stores into scheduler candidates. */
internal object WidgetAutoRefreshRegistry {
    /**
     * A route widget the scheduler may refresh: one without stations (a configuration of the first versions that no
     * saved route resolves) is never loaded by the refresher, so planning it would re-run the scheduler every minute.
     */
    fun routeCandidate(
        widgetId: Int,
        configuration: WidgetDisplayConfiguration,
        lastRefreshEpochMillis: Long,
        nextDirectionBoundary: ZonedDateTime?,
        pendingDirection: Direction?
    ): AutoRefreshCandidate? = configuration.routeTarget()?.let {
        AutoRefreshCandidate(WidgetKind.ROUTE, widgetId, configuration.settings.autoRefresh, lastRefreshEpochMillis, nextDirectionBoundary, pendingDirection)
    }

    fun candidates(context: Context, now: ZonedDateTime): List<AutoRefreshCandidate> = buildList {
        WidgetInstances.pruneOrphans(context)
        WidgetConfigurationStore(context).all().forEach { (widgetId, configuration) ->
            val settings = configuration.settings
            val state = WidgetStateStore.readMetadata(context, widgetId)
            val returnFrom = settings.returnFrom
            val pending = returnFrom?.let {
                val effective = WidgetStateStore.effectiveDirection(context, widgetId, settings, now)
                effective.takeIf { direction -> direction != (WidgetStateStore.displayedDirection(context, widgetId) ?: Direction.OUTBOUND) }
            }
            routeCandidate(
                widgetId = widgetId,
                configuration = configuration,
                lastRefreshEpochMillis = maxOf(state, WidgetAutoRefreshStore.lastAttempt(context, WidgetKind.ROUTE, widgetId)),
                nextDirectionBoundary = returnFrom?.let { WidgetDirectionSchedule.nextBoundary(it, now) },
                pendingDirection = pending
            )?.let(::add)
        }
        StationWidgetConfigurationStore(context).all().forEach { (widgetId, configuration) ->
            val retrieved = StationWidgetStateStore.retrievedAt(context, widgetId)
            add(
                AutoRefreshCandidate(
                    kind = WidgetKind.STATION,
                    widgetId = widgetId,
                    settings = configuration.settings.autoRefresh,
                    lastRefreshEpochMillis = maxOf(retrieved, WidgetAutoRefreshStore.lastAttempt(context, WidgetKind.STATION, widgetId))
                )
            )
        }
        TrainsWidgetConfigurationStore(context).all().forEach { (widgetId, settings) ->
            add(
                AutoRefreshCandidate(
                    kind = WidgetKind.TRAINS,
                    widgetId = widgetId,
                    settings = settings.autoRefresh,
                    lastRefreshEpochMillis = WidgetAutoRefreshStore.lastAttempt(context, WidgetKind.TRAINS, widgetId)
                )
            )
        }
    }
}

/**
 * One scheduler for every widget: a single unique one-time work, re-enqueued by each run for the
 * next instant something is due. Outside the refresh windows nothing is scheduled at all, so the
 * phone is not woken up; with automatic refresh off and no direction swap, no work exists.
 */
object WidgetAutoRefreshScheduler {
    internal const val WORK = "binario-widget-auto-refresh"
    /** Periodic works of the versions before the settings: the scheduler replaces them. */
    private val legacyWorks = listOf("binario-widget-periodic-refresh", "binario-station-widget-periodic-refresh")
    private val networkConnected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Recomputes the next run after a configuration change, a deletion, a boot or an app update. */
    fun reschedule(context: Context) = schedule(context, fromWorker = false)

    internal fun schedule(context: Context, fromWorker: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!fromWorker) legacyWorks.forEach(workManager::cancelUniqueWork)
        val now = ZonedDateTime.now(RailTime.defaultZone)
        val next = WidgetAutoRefreshPlanner.nextRun(WidgetAutoRefreshRegistry.candidates(context, now), now)
        if (next == null) {
            // A run that finds nothing to schedule simply ends the chain.
            if (!fromWorker) workManager.cancelUniqueWork(WORK)
            return
        }
        val delay = WidgetAutoRefreshPlanner.delayUntil(next, now, if (fromWorker) WidgetAutoRefreshPlanner.MIN_RESCHEDULE else Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<WidgetAutoRefreshWorker>()
            .setConstraints(networkConnected)
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(WORK)
            .build()
        // From inside the running work, REPLACE would cancel the run itself: the next run is chained after it.
        workManager.enqueueUniqueWork(WORK, if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
    }
}

class WidgetAutoRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        try {
            val now = ZonedDateTime.now(RailTime.defaultZone)
            val actions = WidgetAutoRefreshPlanner.plan(WidgetAutoRefreshRegistry.candidates(context, now), now)
            var directionChanged = false
            actions.forEach { action ->
                action.applyDirection?.let {
                    WidgetStateStore.syncDirection(context, action.candidate.widgetId, now)
                    directionChanged = true
                }
            }
            val fetch = actions.filter { it.fetch }.groupBy({ it.candidate.kind }, { it.candidate.widgetId })
            fetch[WidgetKind.ROUTE]?.let { RouteWidgetRefresher.refresh(context, it.toSet()) }
            fetch[WidgetKind.STATION]?.let { StationWidgetRefresher.refresh(context, it.toSet()) }
            fetch[WidgetKind.TRAINS]?.let { TrainsWidgetRefresher.refresh(context, it.toSet()) }
            if (directionChanged && fetch[WidgetKind.ROUTE] == null) RouteWidgetProvider.refreshAll(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed refresh is recorded on the widget; the chain must go on regardless.
        } finally {
            if (!isStopped) WidgetAutoRefreshScheduler.schedule(context, fromWorker = true)
        }
        Result.success()
    }
}
