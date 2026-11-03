package it.binario.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.binario.app.MainActivity
import it.binario.app.R
import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import it.binario.app.resolve
import it.binario.app.uiMessage
import it.binario.app.updateTimeMessage
import it.binario.app.data.DataResult
import it.binario.app.data.DataConfidence
import it.binario.app.data.DataQualityBand
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.RailTime
import it.binario.app.data.RouteFailureReason
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.TrainDataSource
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class RouteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
        // Also sent after a reboot or an app update: the scheduler replaces the earlier periodic work.
        WidgetAutoRefreshScheduler.reschedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        render(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_NEXT_PAGE -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) changePage(context, widgetId, 1)
                refreshAll(context)
            }
            ACTION_PREVIOUS_PAGE -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) changePage(context, widgetId, -1)
                refreshAll(context)
            }
            ACTION_SWITCH_DIRECTION -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetStateStore.switchDirection(context, widgetId, ZonedDateTime.now(RailTime.defaultZone))
                }
                refreshAll(context)
                WidgetRefreshWorker.enqueue(context)
            }
            ACTION_REFRESH -> WidgetRefreshWorker.enqueue(context)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetConfigurationStore(context)
        appWidgetIds.forEach { widgetId ->
            store.remove(widgetId)
            WidgetStateStore.clear(context, widgetId)
            WidgetAutoRefreshStore.clear(context, WidgetKind.ROUTE, widgetId)
        }
        WidgetAutoRefreshScheduler.reschedule(context)
    }

    companion object {
        const val ACTION_REFRESH = "it.binario.app.widget.REFRESH"
        const val ACTION_NEXT_PAGE = "it.binario.app.widget.NEXT_PAGE"
        const val ACTION_PREVIOUS_PAGE = "it.binario.app.widget.PREVIOUS_PAGE"
        const val ACTION_SWITCH_DIRECTION = "it.binario.app.widget.SWITCH_DIRECTION"
        private val rowIds = intArrayOf(
            R.id.widget_row_0,
            R.id.widget_row_1,
            R.id.widget_row_2,
            R.id.widget_row_3,
            R.id.widget_row_4
        )
        private val timeIds = intArrayOf(R.id.widget_time_0, R.id.widget_time_1, R.id.widget_time_2, R.id.widget_time_3, R.id.widget_time_4)
        private val destinationIds = intArrayOf(R.id.widget_destination_0, R.id.widget_destination_1, R.id.widget_destination_2, R.id.widget_destination_3, R.id.widget_destination_4)
        private val trainIds = intArrayOf(R.id.widget_train_0, R.id.widget_train_1, R.id.widget_train_2, R.id.widget_train_3, R.id.widget_train_4)
        private val platformIds = intArrayOf(R.id.widget_platform_0, R.id.widget_platform_1, R.id.widget_platform_2, R.id.widget_platform_3, R.id.widget_platform_4)
        private val statusIds = intArrayOf(R.id.widget_status_0, R.id.widget_status_1, R.id.widget_status_2, R.id.widget_status_3, R.id.widget_status_4)

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, RouteWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { render(context, manager, it) }
        }

        /** Snapshots loaded by the app also feed the widgets showing the same route and direction. */
        fun saveSnapshotForMatchingRoutes(context: Context, snapshot: RouteSnapshot) {
            val rows = snapshot.services.map { WidgetServiceMapper.fromService(it, snapshot.retrievedAtEpochMillis) }
            WidgetConfigurationStore(context).all().forEach { (widgetId, configuration) ->
                if (configuration.matchesRouteDirection(snapshot.route, snapshot.direction, WidgetStateStore.displayedDirection(context, widgetId))) {
                    val rowOffset = WidgetStateStore.read(context, widgetId)?.rowOffset ?: 0
                    WidgetStateStore.saveRows(context, widgetId, snapshot, rows, configuration.settings, rowOffset)
                }
            }
        }

        /**
         * Gives the configurations of the first versions (a saved route id without stations) the stations of that
         * saved route, so that the widget loads again; the others stay as they are until reconfigured.
         */
        fun migrateLegacyConfigurations(context: Context, savedRoutes: List<SavedRoute>) {
            val store = WidgetConfigurationStore(context)
            val migrated = store.all().mapNotNull { (widgetId, configuration) ->
                WidgetConfigurationMigration.resolve(configuration, savedRoutes)?.let { widgetId to it }
            }
            if (migrated.isEmpty()) return
            migrated.forEach { (widgetId, configuration) -> store.save(widgetId, configuration) }
            refreshAll(context)
            WidgetAutoRefreshScheduler.reschedule(context)
        }

        fun markMatchingRouteDirectionUnavailable(context: Context, route: SavedRoute, direction: Direction, failure: UiMessage) {
            WidgetConfigurationStore(context).all().forEach { (widgetId, configuration) ->
                if (configuration.matchesRouteDirection(route, direction, WidgetStateStore.displayedDirection(context, widgetId))) {
                    WidgetStateStore.markUnavailable(context, widgetId, failure)
                }
            }
        }

        private fun changePage(context: Context, widgetId: Int, delta: Int) {
            val configuration = WidgetConfigurationStore(context).read(widgetId) ?: return
            val state = WidgetStateStore.read(context, widgetId) ?: return
            val mode = layoutMode(AppWidgetManager.getInstance(context), widgetId)
            val visibleCount = RouteWidgetPresenter.visible(state.services, System.currentTimeMillis(), configuration.settings).size
            WidgetStateStore.setRowOffset(context, widgetId, WidgetRowPagination.move(state.rowOffset, delta, visibleCount, mode.visibleRows))
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val mode = layoutMode(manager, widgetId)
            val views = RemoteViews(context.packageName, layoutResourceFor(mode))
            val strings = WidgetStrings.from(context)
            applyDensity(views, mode)
            views.setViewVisibility(R.id.widget_actions_container, if (mode == WidgetLayoutMode.COMPACT) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_empty, View.GONE)
            val configuration = WidgetConfigurationStore(context).read(widgetId)
            // A configuration of the first versions that no saved route resolves cannot load: it asks to be set up again.
            if (configuration == null || configuration.routeTarget() == null) {
                renderConfigurationRequired(context, manager, widgetId, views)
                return
            }
            val settings = configuration.settings
            val nowMillis = System.currentTimeMillis()
            // The automatic swap (and the end of a manual override) is applied whenever the widget is drawn.
            WidgetStateStore.syncDirection(context, widgetId, ZonedDateTime.now(RailTime.defaultZone))
            val state = WidgetStateStore.read(context, widgetId)
            views.setTextViewText(R.id.widget_updated, updatedAtHeader(context, state?.retrievedAt ?: 0L))
            if (state == null) {
                renderNoSnapshot(context, manager, widgetId, views, configuration)
                return
            }

            if (!configuration.matchesRoute(state.route) || state.snapshotDirection != state.direction) {
                renderNoSnapshot(context, manager, widgetId, views, configuration)
                return
            }

            val visible = RouteWidgetPresenter.visible(state.services, nowMillis, settings)
            val pageSize = mode.visibleRows
            val rowOffset = WidgetRowPagination.normalizedOffset(state.rowOffset, visible.size, pageSize)
            if (rowOffset != state.rowOffset) WidgetStateStore.setRowOffset(context, widgetId, rowOffset)
            val rows = visible.drop(rowOffset).take(pageSize)
            val freshness = if (state.freshness == Freshness.UNAVAILABLE) Freshness.UNAVAILABLE
            else WidgetFreshness.fromAge(state.retrievedAt, nowMillis)
            // "Whole day" keeps showing the cached timetable offline: without fresh data it is the timetable only.
            val timetableOnly = settings.timeRange == RouteTimeRange.WHOLE_DAY && freshness !in setOf(Freshness.FRESH, Freshness.AGING)
            val pageLabel = context.getString(
                R.string.widget_page_label,
                WidgetRowPagination.pageNumber(rowOffset, pageSize),
                WidgetRowPagination.pageCount(visible.size, pageSize)
            )

            val title = configuration.widgetTitle(state.routeLabel)
            views.setTextViewText(R.id.widget_route, title)
            views.setTextViewText(R.id.widget_meta, state.compactMeta(strings, pageLabel, freshness))
            views.setViewVisibility(R.id.widget_column_header, if (mode.showColumnHeader && rows.isNotEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_column_platform, if (settings.showPlatform) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
            views.setContentDescription(
                R.id.widget_route,
                context.getString(
                    R.string.widget_route_accessibility,
                    title,
                    state.accessibleDataSummary(strings, freshness),
                    updateTimeMessage(state.retrievedAt).resolve(context.resources)
                )
            )
            rowIds.forEachIndexed { index, id ->
                val row = rows.getOrNull(index)
                if (row == null) {
                    views.setViewVisibility(id, View.GONE)
                } else {
                    val service = row.service
                    val inTransitLabel = if (row.inTransit) {
                        service.arrivalLabel?.let { context.getString(R.string.widget_in_transit_arrival, it) } ?: context.getString(R.string.widget_in_transit)
                    } else null
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setTextViewText(timeIds[index], if (timetableOnly) service.scheduledDeparture else service.expectedDeparture)
                    views.setTextViewText(destinationIds[index], service.destination)
                    val trainLine = listOfNotNull(
                        inTransitLabel,
                        if (settings.showTrainName) "${service.operator.shortLabel} · ${strings.categoryLabels.getValue(service.category)} ${service.number}" else null
                    ).joinToString(" · ")
                    views.setTextViewText(trainIds[index], trainLine)
                    views.setViewVisibility(trainIds[index], if (trainLine.isBlank()) View.GONE else View.VISIBLE)
                    views.setTextViewText(platformIds[index], widgetPlatformText(context, service.platform, service.scheduledPlatform))
                    views.setTextColor(platformIds[index], widgetPlatformColor(context, service.platform, service.scheduledPlatform))
                    views.setViewVisibility(platformIds[index], if (settings.showPlatform) View.VISIBLE else View.GONE)
                    views.setTextViewText(
                        statusIds[index],
                        if (timetableOnly) strings.statusScheduled else WidgetChangeHighlight.displayStatus(service, state.confidence, strings)
                    )
                    views.setContentDescription(
                        id,
                        listOfNotNull(
                            service.accessibleLabel(strings),
                            inTransitLabel,
                            if (timetableOnly) strings.scheduledUnverified else null
                        ).joinToString(" ")
                    )
                    views.setTextColor(statusIds[index], statusColor(context, if (timetableOnly) ServiceStatus.ON_TIME else service.status))
                    views.setInt(
                        id,
                        "setBackgroundResource",
                        if (!timetableOnly && WidgetChangeHighlight.shouldHighlight(service.status, state.confidence, freshness, service.operationalStatusVerified)) R.drawable.widget_row_variation else R.drawable.widget_row_background
                    )
                    views.setOnClickPendingIntent(id, openAppIntent(context, widgetId))
                }
            }
            views.setTextViewText(R.id.widget_direction, strings.directionLabels.getValue(state.direction).uppercase())
            views.setTextViewText(R.id.widget_refresh, context.getString(R.string.widget_refresh_symbol))
            views.setOnClickPendingIntent(R.id.widget_previous_page, actionIntent(context, ACTION_PREVIOUS_PAGE, widgetId))
            views.setOnClickPendingIntent(R.id.widget_next_page, actionIntent(context, ACTION_NEXT_PAGE, widgetId))
            views.setOnClickPendingIntent(R.id.widget_direction, actionIntent(context, ACTION_SWITCH_DIRECTION, widgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, actionIntent(context, ACTION_REFRESH, widgetId))
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, widgetId))
            manager.updateAppWidget(widgetId, views)
        }

        private fun layoutMode(manager: AppWidgetManager, widgetId: Int): WidgetLayoutMode {
            val options = manager.getAppWidgetOptions(widgetId)
            return WidgetLayoutMode.fromSize(
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
            )
        }

        private fun layoutResourceFor(mode: WidgetLayoutMode): Int = R.layout.route_widget

        private fun applyDensity(views: RemoteViews, mode: WidgetLayoutMode) {
            views.setTextViewTextSize(R.id.widget_route, TypedValue.COMPLEX_UNIT_SP, mode.routeTextSizeSp)
            timeIds.forEach { views.setTextViewTextSize(it, TypedValue.COMPLEX_UNIT_SP, mode.rowTextSizeSp) }
            destinationIds.forEach { views.setTextViewTextSize(it, TypedValue.COMPLEX_UNIT_SP, mode.rowTextSizeSp) }
        }

        private fun renderConfigurationRequired(context: Context, manager: AppWidgetManager, widgetId: Int, views: RemoteViews) {
            views.setTextViewText(R.id.widget_route, context.getString(R.string.widget_configuration_title))
            views.setTextViewText(R.id.widget_meta, context.getString(R.string.widget_configuration_subtitle))
            views.setTextViewText(R.id.widget_updated, "")
            rowIds.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.widget_column_header, View.GONE)
            views.setTextViewText(R.id.widget_direction, context.getString(R.string.widget_configuration_action))
            views.setTextViewText(R.id.widget_refresh, context.getString(R.string.widget_open_action))
            views.setOnClickPendingIntent(R.id.widget_root, configureIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_direction, configureIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, openAppIntent(context, widgetId))
            manager.updateAppWidget(widgetId, views)
        }

        private fun renderNoSnapshot(context: Context, manager: AppWidgetManager, widgetId: Int, views: RemoteViews, configuration: WidgetDisplayConfiguration) {
            views.setTextViewText(R.id.widget_route, configuration.widgetTitle().uppercase())
            val failure = WidgetStateStore.lastFailure(context, widgetId)?.resolve(context.resources)
            views.setTextViewText(
                R.id.widget_meta,
                failure ?: context.getString(R.string.widget_unavailable_meta, context.getString(configuration.targetType.labelRes()).uppercase())
            )
            rowIds.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.widget_column_header, View.GONE)
            views.setTextViewText(R.id.widget_direction, context.getString(R.string.widget_configuration_action))
            views.setTextViewText(R.id.widget_refresh, context.getString(R.string.widget_refresh_symbol))
            views.setContentDescription(R.id.widget_refresh, context.getString(R.string.widget_refresh))
            views.setContentDescription(
                R.id.widget_root,
                context.getString(
                    R.string.widget_unavailable_accessibility,
                    configuration.widgetTitle(),
                    failure.orEmpty()
                )
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_direction, configureIntent(context, widgetId))
            // Without data (automatic refresh off, or a direction just swapped) the button loads it now.
            views.setOnClickPendingIntent(R.id.widget_refresh, actionIntent(context, ACTION_REFRESH, widgetId))
            manager.updateAppWidget(widgetId, views)
        }

        private fun actionIntent(context: Context, action: String, widgetId: Int): PendingIntent {
            val intent = Intent(context, RouteWidgetProvider::class.java).setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            return PendingIntent.getBroadcast(
                context,
                "$action-$widgetId".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openAppIntent(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            return PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun configureIntent(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, WidgetConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            return PendingIntent.getActivity(
                context,
                "configure-$widgetId".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun WidgetTargetType.labelRes(): Int = when (this) {
            WidgetTargetType.ROUTE -> R.string.widget_target_route
        }

        private fun statusColor(context: Context, status: ServiceStatus): Int = when (status) {
            ServiceStatus.ON_TIME -> ContextCompat.getColor(context, R.color.widget_on_surface)
            ServiceStatus.DELAYED -> ContextCompat.getColor(context, R.color.widget_warning)
            ServiceStatus.CANCELLED -> ContextCompat.getColor(context, R.color.widget_error)
        }
    }
}

/** "Aggiornato alle HH:mm" in the widget header, so that stale data is visible at a glance. */
internal fun updatedAtHeader(context: Context, retrievedAt: Long): String = if (retrievedAt <= 0L) {
    context.getString(R.string.widget_updated_header_never)
} else {
    context.getString(
        R.string.widget_updated_header,
        DateTimeFormatter.ofPattern("HH:mm").withZone(RailTime.defaultZone).format(Instant.ofEpochMilli(retrievedAt))
    )
}

/**
 * The refresh requested by the widget's button or by a saved configuration. It refreshes every route widget, as
 * before; automatic refreshes go through [WidgetAutoRefreshWorker], which refreshes only the widgets that are due.
 * A temporary failure is retried by [WidgetRefreshRetryPolicy] for the widgets that failed only.
 */
class WidgetRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val attempt = inputData.getInt(KEY_ATTEMPT, 1)
        val failed = RouteWidgetRefresher.refresh(context, WidgetRefreshRetryPolicy.decodeIds(inputData.getString(KEY_WIDGET_IDS)))
        if (failed.isNotEmpty() && !isStopped) {
            val settings = WidgetConfigurationStore(context).all()
                .filter { (widgetId, _) -> widgetId in failed }
                .associate { (widgetId, configuration) -> widgetId to configuration.settings.autoRefresh }
            WidgetRefreshRetryPolicy.retry(settings, attempt, ZonedDateTime.now(RailTime.defaultZone))?.let { retry ->
                // Chained after this run, under the same name: a tap on the button replaces it.
                enqueue(context, retry.widgetIds, attempt + 1, retry.delay.toMillis(), ExistingWorkPolicy.APPEND_OR_REPLACE)
            }
        }
        Result.success()
    }

    companion object {
        private const val ONE_TIME_WORK = "binario-widget-refresh"
        private const val KEY_WIDGET_IDS = "widget_ids"
        private const val KEY_ATTEMPT = "attempt"
        private val networkConnected = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueue(context: Context) = enqueue(context, null, 1, 0L, ExistingWorkPolicy.REPLACE)

        private fun enqueue(context: Context, widgetIds: Set<Int>?, attempt: Int, delayMillis: Long, policy: ExistingWorkPolicy) {
            val input = Data.Builder().putInt(KEY_ATTEMPT, attempt)
                .apply { widgetIds?.let { putString(KEY_WIDGET_IDS, WidgetRefreshRetryPolicy.encodeIds(it)) } }
                .build()
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(networkConnected)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(input)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK, policy, request)
        }

        /** Kept for callers of the earlier API: the scheduler decides now whether any refresh is due. */
        fun ensurePeriodicRefresh(context: Context) = WidgetAutoRefreshScheduler.reschedule(context)
    }
}

/** A loaded route with the rows of every page requested for the widget's time range. */
internal sealed interface RouteWidgetFetch {
    data class Loaded(val snapshot: RouteSnapshot, val rows: List<WidgetService>) : RouteWidgetFetch
    data class Failed(val reason: RouteFailureReason) : RouteWidgetFetch
}

/** Loads route widgets through the app's route source, with its order, priority and gating unchanged. */
internal object RouteWidgetRefresher {
    /** Refreshes the route widgets in [widgetIds], all of them when null. Returns the widgets that failed temporarily. */
    suspend fun refresh(context: Context, widgetIds: Set<Int>?): Set<Int> {
        val now = ZonedDateTime.now(RailTime.defaultZone)
        if (widgetIds == null) WidgetInstances.pruneOrphans(context)
        val widgets = WidgetConfigurationStore(context).all()
            .filter { (widgetId, _) -> widgetIds == null || widgetId in widgetIds }
            .mapNotNull { (widgetId, configuration) -> configuration.routeTarget()?.let { Triple(widgetId, configuration, it) } }
        if (widgets.isEmpty()) return emptySet()
        val source = BinarioDataGraph.routeDataSource(context)
        val fetched = mutableMapOf<String, RouteWidgetFetch>()
        val failed = mutableSetOf<Int>()
        widgets.forEach { (widgetId, configuration, route) ->
            WidgetAutoRefreshStore.recordAttempt(context, WidgetKind.ROUTE, widgetId, now.toInstant().toEpochMilli())
            val direction = WidgetStateStore.syncDirection(context, widgetId, now)
            val previous = WidgetStateStore.read(context, widgetId)
            val range = configuration.settings.timeRange
            // "Whole day" pages through the timetable only for the first refresh of the day.
            val storedDay = previous?.takeIf { it.direction == direction && it.snapshotDirection == direction && configuration.matchesRoute(it.route) }
                ?.services?.filter { it.serviceDate == now.toLocalDate() }.orEmpty()
            val allowPaging = range != RouteTimeRange.WHOLE_DAY || storedDay.isEmpty()
            val key = "${route.id}|${route.outwardOrigin.id}|${route.outwardDestination.id}|$direction|$range|$allowPaging"
            when (val result = fetched.getOrPut(key) { fetch(source, route, direction, range, now, allowPaging) }) {
                is RouteWidgetFetch.Loaded ->
                    WidgetStateStore.saveRows(context, widgetId, result.snapshot, result.rows, configuration.settings, previous?.rowOffset ?: 0)
                is RouteWidgetFetch.Failed -> {
                    if (WidgetRefreshRetryPolicy.isTransient(result.reason)) failed += widgetId
                    WidgetStateStore.markUnavailable(context, widgetId, result.reason.uiMessage())
                }
            }
        }
        RouteWidgetProvider.refreshAll(context)
        return failed
    }

    suspend fun fetch(
        source: TrainDataSource,
        route: SavedRoute,
        direction: Direction,
        range: RouteTimeRange,
        now: ZonedDateTime,
        allowPaging: Boolean = true
    ): RouteWidgetFetch {
        val first = when (val result = source.loadRoute(route, direction)) {
            is DataResult.Success -> result.snapshot
            is DataResult.Failure -> return RouteWidgetFetch.Failed(result.reason)
        }
        val rows = first.services.map { WidgetServiceMapper.fromService(it, first.retrievedAtEpochMillis) }.toMutableList()
        var pages = 0
        while (allowPaging) {
            val query = RouteWidgetPaging.nextQuery(range, now, rows.mapNotNull { it.departureEpochMillis }.maxOrNull(), pages) ?: break
            pages += 1
            val page = (source.loadRoute(route, direction, query) as? DataResult.Success)?.snapshot ?: break
            val known = rows.mapTo(hashSetOf()) { it.runKey }
            val extra = page.services.map { WidgetServiceMapper.fromService(it, page.retrievedAtEpochMillis) }.filter { it.runKey !in known }
            if (extra.isEmpty()) break
            rows += extra
        }
        return RouteWidgetFetch.Loaded(first, rows.sortedWith(compareBy<WidgetService, Long?>(nullsLast()) { it.departureEpochMillis }.thenBy { it.number }))
    }
}

data class WidgetService(
    val number: String,
    val operator: RailOperator,
    val category: TrainCategory,
    val destination: String,
    val expectedDeparture: String,
    val status: ServiceStatus,
    val delayMinutes: Int,
    val platform: String?,
    val operationalStatusVerified: Boolean,
    /** Timetable departure ("HH:mm"); rows saved before it existed repeat the expected time. */
    val scheduledDeparture: String = expectedDeparture,
    /** Departure instant: expected when verified, else scheduled. Null when the time cannot be read. */
    val departureEpochMillis: Long? = null,
    /** Arrival at the searched destination: expected when verified, else scheduled. */
    val arrivalEpochMillis: Long? = null,
    val arrivalLabel: String? = null,
    /** Group used by the train type exclusions; null when the data do not tell. */
    val typeGroup: TrainTypeGroup? = WidgetTrainTypes.groupOf(category, operator),
    val serviceDate: LocalDate? = null,
    /** Timetable platform when the source moved the train ("BIN 7 era 5"); null otherwise or unknown. */
    val scheduledPlatform: String? = null
) {
    /** One run of the day: the same number at the same scheduled departure. */
    val runKey: String get() = "$number@$scheduledDeparture"

    fun statusLabel(strings: WidgetStrings): String = when (status) {
        ServiceStatus.ON_TIME -> strings.statusOnTime
        ServiceStatus.DELAYED -> strings.format(strings.statusDelayed, delayMinutes)
        ServiceStatus.CANCELLED -> strings.statusCancelled
    }
    fun accessibleLabel(strings: WidgetStrings): String {
        val operational = when {
            !operationalStatusVerified -> strings.scheduledUnverified
            status == ServiceStatus.DELAYED && delayMinutes > 0 -> "${strings.statusLabels.getValue(status)} ${strings.format(strings.delayMinutes, delayMinutes)}"
            else -> strings.statusLabels.getValue(status)
        }
        return strings.format(
            strings.serviceAccessibility,
            operator.accessibleLabel,
            strings.categoryLabels.getValue(category),
            number,
            destination,
            expectedDeparture,
            operational,
            strings.platformSpoken(platform, scheduledPlatform)
        )
    }
}

data class WidgetState(
    val route: SavedRoute,
    /** Direzione a cui appartengono le righe persistite. */
    val snapshotDirection: Direction,
    /** Direzione selezionata nell'istanza; può non avere ancora uno snapshot. */
    val direction: Direction,
    val services: List<WidgetService>,
    val freshness: Freshness,
    val confidence: DataConfidence,
    val providerName: String,
    val qualityScore: Int?,
    val qualityBand: DataQualityBand,
    val qualityWarnings: Set<SnapshotWarning>,
    val retrievedAt: Long,
    val rowOffset: Int
) {
    val routeLabel: String get() = route.displayName(direction)

    fun compactMeta(strings: WidgetStrings, pageLabel: String? = null, freshnessAtRender: Freshness = freshness, updatedAt: String? = null): String = listOfNotNull(
        providerName.ifBlank { null },
        strings.confidenceLabels.getValue(confidence),
        qualityScore?.let { strings.format(strings.qualityScore, it) },
        strings.freshnessLabels.getValue(freshnessAtRender),
        qualityWarnings.firstOrNull()?.let { strings.warningLabels.getValue(it) },
        updatedAt,
        pageLabel
    ).joinToString(" · ")

    fun accessibleDataSummary(strings: WidgetStrings, freshnessAtRender: Freshness = freshness): String = buildString {
        append(
            strings.format(
                strings.dataAccessibility,
                providerName,
                strings.freshnessAccessibleLabels.getValue(freshnessAtRender),
                strings.confidenceAccessibleLabels.getValue(confidence),
                strings.qualityBandLabels.getValue(qualityBand)
            )
        )
        qualityScore?.let { append(strings.format(strings.qualityScoreAccessibility, it)) }
        if (qualityWarnings.isNotEmpty()) {
            append(strings.format(strings.warningsAccessibility, qualityWarnings.joinToString { strings.warningAccessibleLabels.getValue(it) }))
        }
    }
}

object WidgetStateStore {
    private const val PREFS = "binario_widget_state"
    private const val KEY_ROUTE = "route"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_SNAPSHOT_DIRECTION = "snapshot_direction"
    private const val KEY_LINES = "lines"
    private const val KEY_FRESHNESS = "freshness"
    private const val KEY_CONFIDENCE = "confidence"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_QUALITY_SCORE = "quality_score"
    private const val KEY_QUALITY_BAND = "quality_band"
    private const val KEY_QUALITY_WARNINGS = "quality_warnings"
    private const val KEY_RETRIEVED = "retrieved"
    private const val KEY_ROW_OFFSET = "row_offset"
    private const val KEY_LEGACY_PAGE = "page"
    private const val KEY_FAILURE = "last_failure"
    private const val KEY_MANUAL_DIRECTION = "manual_direction"
    private const val KEY_MANUAL_UNTIL = "manual_direction_until"

    /** Saves a snapshot as rows, sorted by departure; "whole day" merges them into the stored rows of the day. */
    fun saveSnapshot(context: Context, widgetId: Int, snapshot: RouteSnapshot, rowOffset: Int = 0) {
        val rows = snapshot.services.map { WidgetServiceMapper.fromService(it, snapshot.retrievedAtEpochMillis) }
        saveRows(context, widgetId, snapshot, rows, RouteWidgetSettings.LEGACY, rowOffset)
    }

    fun saveRows(context: Context, widgetId: Int, snapshot: RouteSnapshot, rows: List<WidgetService>, settings: RouteWidgetSettings, rowOffset: Int = 0) {
        val today = Instant.ofEpochMilli(snapshot.retrievedAtEpochMillis).atZone(RailTime.defaultZone).toLocalDate()
        val ordered = if (settings.timeRange == RouteTimeRange.WHOLE_DAY) {
            val stored = read(context, widgetId)
                ?.takeIf { it.route.outwardOrigin.id == snapshot.route.outwardOrigin.id && it.route.outwardDestination.id == snapshot.route.outwardDestination.id && it.snapshotDirection == snapshot.direction }
                ?.services.orEmpty()
            RouteWidgetDayCache.merge(stored, rows, today)
        } else {
            rows.sortedWith(compareBy<WidgetService, Long?>(nullsLast()) { it.departureEpochMillis }.thenBy { it.expectedDeparture }.thenBy { it.number })
        }
        val lines = ordered.joinToString(";") { encodeService(it) }
        val hasDisplayedDirection = prefs(context).contains(key(widgetId, KEY_DIRECTION))
        prefs(context).edit {
            putString(key(widgetId, KEY_ROUTE), encodeRoute(snapshot.route))
            putString(key(widgetId, KEY_SNAPSHOT_DIRECTION), snapshot.direction.name)
            if (!hasDisplayedDirection) putString(key(widgetId, KEY_DIRECTION), snapshot.direction.name)
            putString(key(widgetId, KEY_LINES), lines)
            putString(key(widgetId, KEY_FRESHNESS), snapshot.freshness.name)
            putString(key(widgetId, KEY_CONFIDENCE), snapshot.confidence.name)
            putString(key(widgetId, KEY_PROVIDER), snapshot.providerName)
            snapshot.qualityScore?.let { putInt(key(widgetId, KEY_QUALITY_SCORE), it) } ?: remove(key(widgetId, KEY_QUALITY_SCORE))
            putString(key(widgetId, KEY_QUALITY_BAND), snapshot.qualityBand.name)
            putString(key(widgetId, KEY_QUALITY_WARNINGS), snapshot.qualityWarnings.joinToString("|") { it.name })
            putLong(key(widgetId, KEY_RETRIEVED), snapshot.retrievedAtEpochMillis)
            putInt(key(widgetId, KEY_ROW_OFFSET), rowOffset.coerceAtLeast(0))
            remove(key(widgetId, KEY_LEGACY_PAGE))
            remove(key(widgetId, KEY_FAILURE))
        }
    }

    /** Instant of the last data the widget received, 0 when it has none. */
    fun readMetadata(context: Context, widgetId: Int): Long = prefs(context).getLong(key(widgetId, KEY_RETRIEVED), 0L)

    fun read(context: Context, widgetId: Int): WidgetState? {
        val route = prefs(context).getString(key(widgetId, KEY_ROUTE), null)?.let(::decodeRoute) ?: return null
        val direction = displayedDirection(context, widgetId) ?: Direction.OUTBOUND
        val snapshotDirection = runCatching {
            Direction.valueOf(
                prefs(context).getString(key(widgetId, KEY_SNAPSHOT_DIRECTION), null)
                    ?: prefs(context).getString(key(widgetId, KEY_DIRECTION), Direction.OUTBOUND.name)
                    ?: Direction.OUTBOUND.name
            )
        }.getOrDefault(Direction.OUTBOUND)
        val freshness = runCatching {
            Freshness.valueOf(prefs(context).getString(key(widgetId, KEY_FRESHNESS), Freshness.UNAVAILABLE.name) ?: Freshness.UNAVAILABLE.name)
        }.getOrDefault(Freshness.UNAVAILABLE)
        val confidence = runCatching {
            DataConfidence.valueOf(prefs(context).getString(key(widgetId, KEY_CONFIDENCE), DataConfidence.DEMONSTRATION.name) ?: DataConfidence.DEMONSTRATION.name)
        }.getOrDefault(DataConfidence.DEMONSTRATION)
        if (shouldDiscardLegacyDemo(confidence)) {
            clear(context, widgetId)
            return null
        }
        val qualityBand = runCatching {
            DataQualityBand.valueOf(prefs(context).getString(key(widgetId, KEY_QUALITY_BAND), DataQualityBand.UNKNOWN.name) ?: DataQualityBand.UNKNOWN.name)
        }.getOrDefault(DataQualityBand.UNKNOWN)
        val warnings = prefs(context).getString(key(widgetId, KEY_QUALITY_WARNINGS), "").orEmpty()
            .split("|")
            .mapNotNull { encoded -> runCatching { SnapshotWarning.valueOf(encoded) }.getOrNull() }
            .toSet()
        val retrievedAt = prefs(context).getLong(key(widgetId, KEY_RETRIEVED), 0L)
        val lines = prefs(context).getString(key(widgetId, KEY_LINES), "").orEmpty().split(";").mapNotNull { decodeService(it, confidence, retrievedAt) }
        return WidgetState(
            route = route,
            snapshotDirection = snapshotDirection,
            direction = direction,
            services = lines,
            freshness = freshness,
            confidence = confidence,
            providerName = prefs(context).getString(key(widgetId, KEY_PROVIDER), context.getString(R.string.widget_default_provider)).orEmpty(),
            qualityScore = if (prefs(context).contains(key(widgetId, KEY_QUALITY_SCORE))) prefs(context).getInt(key(widgetId, KEY_QUALITY_SCORE), 0) else null,
            qualityBand = qualityBand,
            qualityWarnings = warnings,
            retrievedAt = retrievedAt,
            rowOffset = prefs(context).getInt(key(widgetId, KEY_ROW_OFFSET), 0)
        )
    }

    fun displayedDirection(context: Context, widgetId: Int): Direction? = runCatching {
            Direction.valueOf(prefs(context).getString(key(widgetId, KEY_DIRECTION), Direction.OUTBOUND.name) ?: Direction.OUTBOUND.name)
        }.getOrNull()

    fun changePage(context: Context, widgetId: Int, delta: Int, visibleRows: Int) {
        val state = read(context, widgetId) ?: return
        setRowOffset(context, widgetId, WidgetRowPagination.move(state.rowOffset, delta, state.services.size, visibleRows))
    }

    fun setRowOffset(context: Context, widgetId: Int, rowOffset: Int) {
        prefs(context).edit { putInt(key(widgetId, KEY_ROW_OFFSET), rowOffset.coerceAtLeast(0)) }
    }

    /**
     * The manual direction button. With an automatic swap configured, the choice holds until the next
     * swap boundary (the chosen time or midnight); then the automatic direction applies again.
     */
    fun switchDirection(context: Context, widgetId: Int, now: ZonedDateTime) {
        val next = if (displayedDirection(context, widgetId) == Direction.OUTBOUND) Direction.RETURN else Direction.OUTBOUND
        val returnFrom = WidgetConfigurationStore(context).read(widgetId)?.settings?.returnFrom
        prefs(context).edit {
            putString(key(widgetId, KEY_DIRECTION), next.name)
            putInt(key(widgetId, KEY_ROW_OFFSET), 0)
            if (returnFrom != null) {
                putString(key(widgetId, KEY_MANUAL_DIRECTION), next.name)
                putLong(key(widgetId, KEY_MANUAL_UNTIL), WidgetDirectionSchedule.nextBoundary(returnFrom, now).toInstant().toEpochMilli())
            } else {
                remove(key(widgetId, KEY_MANUAL_DIRECTION))
                remove(key(widgetId, KEY_MANUAL_UNTIL))
            }
        }
    }

    /** The direction the widget should show now, from its settings and any manual override still valid. */
    fun effectiveDirection(context: Context, widgetId: Int, settings: RouteWidgetSettings, now: ZonedDateTime): Direction {
        val manual = prefs(context).getString(key(widgetId, KEY_MANUAL_DIRECTION), null)?.let { name -> Direction.entries.firstOrNull { it.name == name } }
        return WidgetDirectionSchedule.effectiveDirection(
            returnFrom = settings.returnFrom,
            storedDirection = displayedDirection(context, widgetId) ?: Direction.OUTBOUND,
            manualDirection = manual,
            manualUntilEpochMillis = prefs(context).getLong(key(widgetId, KEY_MANUAL_UNTIL), 0L),
            now = now
        )
    }

    /** Applies the automatic direction (if configured) and returns the direction to show and load. */
    fun syncDirection(context: Context, widgetId: Int, now: ZonedDateTime): Direction {
        val stored = displayedDirection(context, widgetId) ?: Direction.OUTBOUND
        val settings = WidgetConfigurationStore(context).read(widgetId)?.settings ?: return stored
        if (settings.returnFrom == null) return stored
        val effective = effectiveDirection(context, widgetId, settings, now)
        val manualExpired = now.toInstant().toEpochMilli() >= prefs(context).getLong(key(widgetId, KEY_MANUAL_UNTIL), 0L)
        if (effective != stored || manualExpired) {
            prefs(context).edit {
                if (effective != stored) {
                    putString(key(widgetId, KEY_DIRECTION), effective.name)
                    putInt(key(widgetId, KEY_ROW_OFFSET), 0)
                }
                if (manualExpired) {
                    remove(key(widgetId, KEY_MANUAL_DIRECTION))
                    remove(key(widgetId, KEY_MANUAL_UNTIL))
                }
            }
        }
        return effective
    }

    fun markUnavailable(context: Context, widgetId: Int, failure: UiMessage? = null) {
        prefs(context).edit {
            putString(key(widgetId, KEY_FRESHNESS), Freshness.UNAVAILABLE.name)
            failure?.let { putString(key(widgetId, KEY_FAILURE), encodeFailure(it)) }
        }
    }

    /** Null for the free text persisted by earlier versions, which the generic unavailable label replaces. */
    fun lastFailure(context: Context, widgetId: Int): UiMessage? = prefs(context).getString(key(widgetId, KEY_FAILURE), null)?.let(::decodeFailure)

    /** Failures persist as message key plus ISO arguments, so the widget renders them in the current language. */
    internal fun encodeFailure(failure: UiMessage): String =
        (listOf(failure.key.name) + failure.arguments.map { it.toString() }).joinToString(",") { encode(it) }

    internal fun decodeFailure(value: String): UiMessage? = runCatching {
        val parts = value.split(",").map(::decode)
        UiMessage(
            UiMessageKey.valueOf(parts.first()),
            parts.drop(1).map { argument ->
                runCatching { LocalDate.parse(argument) }.getOrNull()
                    ?: runCatching { LocalTime.parse(argument) }.getOrNull()
                    ?: argument
            }
        )
    }.getOrNull()

    internal fun shouldDiscardLegacyDemo(confidence: DataConfidence): Boolean =
        confidence == DataConfidence.DEMONSTRATION

    fun clear(context: Context, widgetId: Int) {
        prefs(context).edit { listOf(KEY_ROUTE, KEY_DIRECTION, KEY_SNAPSHOT_DIRECTION, KEY_LINES, KEY_FRESHNESS, KEY_CONFIDENCE, KEY_PROVIDER, KEY_QUALITY_SCORE, KEY_QUALITY_BAND, KEY_QUALITY_WARNINGS, KEY_RETRIEVED, KEY_ROW_OFFSET, KEY_LEGACY_PAGE, KEY_FAILURE, KEY_MANUAL_DIRECTION, KEY_MANUAL_UNTIL).forEach { remove(key(widgetId, it)) } }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(widgetId: Int, suffix: String): String = "widget_${widgetId}_$suffix"

    private fun encodeRoute(route: SavedRoute): String = listOf(
        route.id,
        route.name,
        route.outwardOrigin.id,
        route.outwardOrigin.name,
        route.outwardOrigin.city,
        route.outwardDestination.id,
        route.outwardDestination.name,
        route.outwardDestination.city
    ).joinToString(",") { encode(it) }

    private fun decodeRoute(value: String): SavedRoute? {
        val parts = value.split(",").map(::decode)
        if (parts.size != 8) return null
        return SavedRoute(
            id = parts[0],
            name = parts[1],
            outwardOrigin = Station(parts[2], parts[3], parts[4]),
            outwardDestination = Station(parts[5], parts[6], parts[7])
        )
    }

    internal fun encodeService(service: WidgetService): String = listOf(
        service.number,
        service.operator.name,
        service.category.name,
        service.destination,
        service.expectedDeparture,
        service.status.name,
        service.delayMinutes.toString(),
        service.platform.orEmpty(),
        service.operationalStatusVerified.toString(),
        service.scheduledDeparture,
        service.departureEpochMillis?.toString().orEmpty(),
        service.arrivalEpochMillis?.toString().orEmpty(),
        service.arrivalLabel.orEmpty(),
        service.typeGroup?.name.orEmpty(),
        service.serviceDate?.toString().orEmpty(),
        service.scheduledPlatform.orEmpty()
    ).joinToString(",") { encode(it) }

    /**
     * Rows of earlier versions have 8 or 9 fields: their departure instant is read from the expected
     * time around the snapshot's retrieval and their type group from category and operator.
     */
    internal fun decodeService(value: String, confidence: DataConfidence, retrievedAt: Long): WidgetService? {
        val parts = runCatching { value.split(",").map(::decode) }.getOrNull() ?: return null
        // 16 fields since the timetable platform; 15 before it.
        if (parts.size !in setOf(8, 9, 15, 16)) return null
        return runCatching {
            val operator = RailOperator.valueOf(parts[1])
            val category = decodeCategory(parts[2])
            val expected = parts[4]
            val extended = parts.size >= 15
            val legacyDeparture = if (extended || retrievedAt <= 0L) null else WidgetClock.resolve(expected, retrievedAt)
            WidgetService(
                number = parts[0],
                operator = operator,
                category = category,
                destination = parts[3],
                expectedDeparture = expected,
                status = ServiceStatus.valueOf(parts[5]),
                delayMinutes = parts[6].toInt(),
                platform = parts[7].ifBlank { null },
                operationalStatusVerified = parts.getOrNull(8)?.toBooleanStrictOrNull()
                    ?: (confidence !in setOf(DataConfidence.STATIC_ONLY, DataConfidence.DEMONSTRATION)),
                scheduledDeparture = if (extended) parts[9].ifBlank { expected } else expected,
                departureEpochMillis = if (extended) parts[10].toLongOrNull() else legacyDeparture,
                arrivalEpochMillis = if (extended) parts[11].toLongOrNull() else null,
                arrivalLabel = if (extended) parts[12].ifBlank { null } else null,
                typeGroup = if (extended) TrainTypeGroup.entries.firstOrNull { it.name == parts[13] } else WidgetTrainTypes.groupOf(category, operator),
                serviceDate = if (extended) runCatching { LocalDate.parse(parts[14]) }.getOrNull()
                else legacyDeparture?.let { Instant.ofEpochMilli(it).atZone(RailTime.defaultZone).toLocalDate() },
                scheduledPlatform = parts.getOrNull(15)?.ifBlank { null }
            )
        }.getOrNull()
    }

    /** Earlier versions persisted the Italian category label; it maps back to its enum constant. */
    private fun decodeCategory(value: String): TrainCategory =
        TrainCategory.entries.firstOrNull { it.name == value || it.accessibleLabel == value } ?: TrainCategory.OTHER

    // java.util.Base64 (API 26+) writes the same padded, unwrapped alphabet that android.util.Base64.NO_WRAP
    // persisted before, and unlike the framework class it runs in JVM unit tests (the SDK stub throws).
    private fun encode(value: String): String = java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String = String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
}
