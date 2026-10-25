package it.binario.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.binario.app.MainActivity
import it.binario.app.R
import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import it.binario.app.labelRes
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
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class RouteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
        WidgetRefreshWorker.ensurePeriodicRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        render(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_NEXT_PAGE -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val mode = layoutMode(AppWidgetManager.getInstance(context), widgetId)
                    WidgetStateStore.changePage(context, widgetId, 1, mode.visibleRows)
                }
                refreshAll(context)
            }
            ACTION_PREVIOUS_PAGE -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val mode = layoutMode(AppWidgetManager.getInstance(context), widgetId)
                    WidgetStateStore.changePage(context, widgetId, -1, mode.visibleRows)
                }
                refreshAll(context)
            }
            ACTION_SWITCH_DIRECTION -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetStateStore.switchDirection(context, widgetId)
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
        }
        WidgetRefreshWorker.ensurePeriodicRefresh(context)
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

        fun saveSnapshotForMatchingRoutes(context: Context, snapshot: RouteSnapshot) {
            WidgetConfigurationStore(context).all().forEach { (widgetId, configuration) ->
                if (configuration.matchesRouteDirection(snapshot.route, snapshot.direction, WidgetStateStore.displayedDirection(context, widgetId))) {
                    val rowOffset = WidgetStateStore.read(context, widgetId)?.rowOffset ?: 0
                    WidgetStateStore.saveSnapshot(context, widgetId, snapshot, rowOffset)
                }
            }
        }

        fun markMatchingRouteDirectionUnavailable(context: Context, route: SavedRoute, direction: Direction, failure: UiMessage) {
            WidgetConfigurationStore(context).all().forEach { (widgetId, configuration) ->
                if (configuration.matchesRouteDirection(route, direction, WidgetStateStore.displayedDirection(context, widgetId))) {
                    WidgetStateStore.markUnavailable(context, widgetId, failure)
                }
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val mode = layoutMode(manager, widgetId)
            val views = RemoteViews(context.packageName, layoutResourceFor(mode))
            val strings = WidgetStrings.from(context)
            applyDensity(views, mode)
            views.setViewVisibility(R.id.widget_actions_container, if (mode == WidgetLayoutMode.COMPACT) View.GONE else View.VISIBLE)
            val configuration = WidgetConfigurationStore(context).read(widgetId)
            if (configuration == null) {
                renderConfigurationRequired(context, manager, widgetId, views)
                return
            }
            val state = WidgetStateStore.read(context, widgetId)
            if (state == null) {
                renderNoSnapshot(context, manager, widgetId, views, configuration)
                return
            }

            if (!configuration.matchesRoute(state.route) || state.snapshotDirection != state.direction) {
                renderNoSnapshot(context, manager, widgetId, views, configuration)
                return
            }

            val pageSize = mode.visibleRows
            val rowOffset = WidgetRowPagination.normalizedOffset(state.rowOffset, state.services.size, pageSize)
            if (rowOffset != state.rowOffset) WidgetStateStore.setRowOffset(context, widgetId, rowOffset)
            val services = state.services.drop(rowOffset).take(pageSize)
            val freshness = if (state.freshness == Freshness.UNAVAILABLE) Freshness.UNAVAILABLE
            else WidgetFreshness.fromAge(state.retrievedAt)
            val pageLabel = context.getString(
                R.string.widget_page_label,
                WidgetRowPagination.pageNumber(rowOffset, pageSize),
                WidgetRowPagination.pageCount(state.services.size, pageSize)
            )

            val title = configuration.widgetTitle(state.routeLabel)
            views.setTextViewText(R.id.widget_route, title)
            views.setTextViewText(R.id.widget_meta, state.compactMeta(strings, pageLabel, freshness, updatedAtLabel(context, state.retrievedAt)))
            views.setViewVisibility(R.id.widget_column_header, if (mode.showColumnHeader) View.VISIBLE else View.GONE)
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
                val service = services.getOrNull(index)
                if (service == null) {
                    views.setViewVisibility(id, View.GONE)
                } else {
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setTextViewText(timeIds[index], service.expectedDeparture)
                    views.setTextViewText(destinationIds[index], service.destination)
                    views.setTextViewText(trainIds[index], "${service.operator.shortLabel} · ${strings.categoryLabels.getValue(service.category)} ${service.number}")
                    views.setTextViewText(platformIds[index], service.platform?.let { context.getString(R.string.widget_platform_short, it) } ?: "—")
                    views.setTextViewText(statusIds[index], WidgetChangeHighlight.displayStatus(service, state.confidence, strings))
                    views.setContentDescription(id, service.accessibleLabel(strings))
                    views.setTextColor(statusIds[index], statusColor(context, service.status))
                    views.setInt(
                        id,
                        "setBackgroundResource",
                        if (WidgetChangeHighlight.shouldHighlight(service.status, state.confidence, freshness, service.operationalStatusVerified)) R.drawable.widget_row_variation else R.drawable.widget_row_background
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
            views.setTextViewText(R.id.widget_refresh, context.getString(R.string.widget_open_action))
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
            views.setOnClickPendingIntent(R.id.widget_refresh, openAppIntent(context, widgetId))
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

        private fun updatedAtLabel(context: Context, retrievedAt: Long): String = if (retrievedAt <= 0L) {
            context.getString(R.string.widget_never_updated)
        } else {
            context.getString(
                R.string.widget_updated_at,
                DateTimeFormatter.ofPattern("HH:mm").withZone(RailTime.defaultZone).format(Instant.ofEpochMilli(retrievedAt))
            )
        }
    }
}

class WidgetRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val routeWidgets = WidgetConfigurationStore(applicationContext).all()
            .mapNotNull { (widgetId, configuration) -> configuration.routeTarget()?.let { widgetId to it } }
        if (routeWidgets.isEmpty()) return@withContext Result.success()
        var failed = false
        routeWidgets.forEach { (widgetId, route) ->
            val previous = WidgetStateStore.read(applicationContext, widgetId)
            val direction = WidgetStateStore.displayedDirection(applicationContext, widgetId) ?: Direction.OUTBOUND
            when (val result = BinarioDataGraph.routeDataSource(applicationContext).loadRoute(route, direction)) {
                is DataResult.Success -> WidgetStateStore.saveSnapshot(applicationContext, widgetId, result.snapshot, previous?.rowOffset ?: 0)
                is DataResult.Failure -> {
                    failed = true
                    WidgetStateStore.markUnavailable(applicationContext, widgetId, result.reason.uiMessage())
                }
            }
        }
        RouteWidgetProvider.refreshAll(applicationContext)
        if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val ONE_TIME_WORK = "binario-widget-refresh"
        private const val PERIODIC_WORK = "binario-widget-periodic-refresh"
        private val networkConnected = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(networkConnected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.REPLACE, request)
        }

        fun ensurePeriodicRefresh(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConnected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
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
    val operationalStatusVerified: Boolean
) {
    fun statusLabel(strings: WidgetStrings): String = when (status) {
        ServiceStatus.ON_TIME -> strings.statusOnTime
        ServiceStatus.DELAYED -> strings.format(strings.statusDelayed, delayMinutes)
        ServiceStatus.CANCELLED -> strings.statusCancelled
    }
    fun accessibleLabel(strings: WidgetStrings): String {
        val operational = when {
            !operationalStatusVerified -> strings.scheduledUnverified
            delayMinutes > 0 -> "${strings.statusLabels.getValue(status)} ${strings.format(strings.delayMinutes, delayMinutes)}"
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
            platform?.let { strings.format(strings.platform, it) } ?: strings.platformUnavailable
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

    fun saveSnapshot(context: Context, widgetId: Int, snapshot: RouteSnapshot, rowOffset: Int = 0) {
        val orderedServices = snapshot.services.sortedWith(compareBy<TrainService> { it.expectedDeparture }.thenBy { it.number })
        val lines = orderedServices.map { service ->
            listOf(
                service.number,
                service.operator.name,
                service.trainCategory.name,
                service.destination,
                service.expectedDeparture,
                service.status.name,
                service.delayMinutes.toString(),
                service.platform.orEmpty(),
                service.operationalStatusVerified.toString()
            ).joinToString(",") { encode(it) }
        }.joinToString(";")
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
        val lines = prefs(context).getString(key(widgetId, KEY_LINES), "").orEmpty().split(";").mapNotNull { decodeService(it, confidence) }
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
            retrievedAt = prefs(context).getLong(key(widgetId, KEY_RETRIEVED), 0L),
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

    fun switchDirection(context: Context, widgetId: Int) {
        val next = if (displayedDirection(context, widgetId) == Direction.OUTBOUND) Direction.RETURN else Direction.OUTBOUND
        prefs(context).edit {
            putString(key(widgetId, KEY_DIRECTION), next.name)
            putInt(key(widgetId, KEY_ROW_OFFSET), 0)
        }
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
        prefs(context).edit { listOf(KEY_ROUTE, KEY_DIRECTION, KEY_SNAPSHOT_DIRECTION, KEY_LINES, KEY_FRESHNESS, KEY_CONFIDENCE, KEY_PROVIDER, KEY_QUALITY_SCORE, KEY_QUALITY_BAND, KEY_QUALITY_WARNINGS, KEY_RETRIEVED, KEY_ROW_OFFSET, KEY_LEGACY_PAGE, KEY_FAILURE).forEach { remove(key(widgetId, it)) } }
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

    private fun decodeService(value: String, confidence: DataConfidence): WidgetService? {
        val parts = value.split(",").map(::decode)
        if (parts.size !in setOf(8, 9)) return null
        return runCatching {
            WidgetService(
                number = parts[0],
                operator = RailOperator.valueOf(parts[1]),
                category = decodeCategory(parts[2]),
                destination = parts[3],
                expectedDeparture = parts[4],
                status = ServiceStatus.valueOf(parts[5]),
                delayMinutes = parts[6].toInt(),
                platform = parts[7].ifBlank { null },
                operationalStatusVerified = parts.getOrNull(8)?.toBooleanStrictOrNull()
                    ?: (confidence !in setOf(DataConfidence.STATIC_ONLY, DataConfidence.DEMONSTRATION))
            )
        }.getOrNull()
    }

    /** Earlier versions persisted the Italian category label; it maps back to its enum constant. */
    private fun decodeCategory(value: String): TrainCategory =
        TrainCategory.entries.firstOrNull { it.name == value || it.accessibleLabel == value } ?: TrainCategory.OTHER

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun decode(value: String): String = String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}
