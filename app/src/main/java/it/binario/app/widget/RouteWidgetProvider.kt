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
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.binario.app.MainActivity
import it.binario.app.PersonalTravelStore
import it.binario.app.R
import it.binario.app.WidgetSurface
import it.binario.app.data.DataResult
import it.binario.app.data.DataConfidence
import it.binario.app.data.DataQualityBand
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.accessibleUpdateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RouteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        render(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_NEXT_PAGE -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetStateStore.changePage(context, widgetId, 1)
                refreshAll(context)
            }
            ACTION_PREVIOUS_PAGE -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetStateStore.changePage(context, widgetId, -1)
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
                if (configuration.matchesRoute(snapshot.route)) {
                    val page = WidgetStateStore.read(context, widgetId)?.page ?: 0
                    WidgetStateStore.saveSnapshot(context, widgetId, snapshot, page)
                }
            }
        }

        fun markMatchingRoutesUnavailable(context: Context, message: String) {
            WidgetConfigurationStore(context).all().forEach { (widgetId, configuration) ->
                if (configuration.targetType == WidgetTargetType.ROUTE) {
                    WidgetStateStore.markUnavailable(context, widgetId, message)
                }
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val mode = layoutMode(manager, widgetId)
            val views = RemoteViews(context.packageName, layoutResourceFor(mode))
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

            if (configuration.targetType == WidgetTargetType.STATION) {
                renderStationTarget(context, manager, widgetId, views, configuration, mode)
                return
            }
            if (configuration.targetType == WidgetTargetType.TRAIN) {
                renderTrainTarget(context, manager, widgetId, views, configuration, state, mode)
                return
            }
            if (!configuration.matchesRoute(state.route)) {
                renderNoSnapshot(context, manager, widgetId, views, configuration)
                return
            }

            val pageSize = mode.visibleRows
            val maxPage = (state.services.size - 1).coerceAtLeast(0) / pageSize
            val page = state.page.coerceIn(0, maxPage)
            if (page != state.page) WidgetStateStore.setPage(context, widgetId, page)
            val services = state.services.drop(page * pageSize).take(pageSize)

            val title = configuration.widgetTitle(state.routeLabel)
            views.setTextViewText(R.id.widget_route, title)
            views.setTextViewText(R.id.widget_meta, state.compactMeta("PAG ${page + 1}/${maxPage + 1}"))
            views.setViewVisibility(R.id.widget_column_header, if (mode.showColumnHeader) View.VISIBLE else View.GONE)
            views.setContentDescription(
                R.id.widget_route,
                "Apri Binario. $title. ${state.accessibleDataSummary()}. ${accessibleUpdateTime(state.retrievedAt)}"
            )
            rowIds.forEachIndexed { index, id ->
                val service = services.getOrNull(index)
                if (service == null) {
                    views.setViewVisibility(id, View.GONE)
                } else {
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setTextViewText(timeIds[index], service.expectedDeparture)
                    views.setTextViewText(destinationIds[index], service.destination)
                    views.setTextViewText(trainIds[index], "${service.operator.shortLabel} · ${service.category} ${service.number}")
                    views.setTextViewText(platformIds[index], service.platform?.let { "BIN $it" } ?: "—")
                    views.setTextViewText(statusIds[index], WidgetChangeHighlight.displayStatus(service, state.confidence))
                    views.setContentDescription(id, service.accessibleLabel(state.confidence))
                    views.setTextColor(statusIds[index], statusColor(context, service.status))
                    views.setInt(
                        id,
                        "setBackgroundResource",
                        if (WidgetChangeHighlight.shouldHighlight(service.status, state.confidence, state.freshness)) R.drawable.widget_row_variation else R.drawable.widget_row_background
                    )
                    views.setOnClickPendingIntent(id, openAppIntent(context, widgetId))
                }
            }
            views.setTextViewText(R.id.widget_direction, state.direction.label.uppercase())
            views.setTextViewText(R.id.widget_refresh, "↻")
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
            views.setTextViewText(R.id.widget_route, "CONFIGURA BINARIO")
            views.setTextViewText(R.id.widget_meta, "SCEGLI TRATTA, STAZIONE O TRENO")
            rowIds.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.widget_column_header, View.GONE)
            views.setTextViewText(R.id.widget_direction, "Configura")
            views.setTextViewText(R.id.widget_refresh, "Apri")
            views.setOnClickPendingIntent(R.id.widget_root, configureIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_direction, configureIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, openAppIntent(context, widgetId))
            manager.updateAppWidget(widgetId, views)
        }

        private fun renderNoSnapshot(context: Context, manager: AppWidgetManager, widgetId: Int, views: RemoteViews, configuration: WidgetDisplayConfiguration) {
            views.setTextViewText(R.id.widget_route, configuration.widgetTitle().uppercase())
            val failure = WidgetStateStore.lastFailure(context, widgetId)
            views.setTextViewText(
                R.id.widget_meta,
                failure?.takeIf { it.isNotBlank() }
                    ?: "${configuration.targetType.label.uppercase()} · DATI NON DISPONIBILI IN TEMPO REALE"
            )
            rowIds.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.widget_column_header, View.GONE)
            views.setTextViewText(R.id.widget_direction, "Modifica")
            views.setTextViewText(R.id.widget_refresh, "Apri")
            views.setContentDescription(
                R.id.widget_root,
                "${configuration.widgetTitle()}. Dati non disponibili in tempo reale. ${failure.orEmpty()}"
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_direction, configureIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, openAppIntent(context, widgetId))
            manager.updateAppWidget(widgetId, views)
        }

        private fun renderStationTarget(context: Context, manager: AppWidgetManager, widgetId: Int, views: RemoteViews, configuration: WidgetDisplayConfiguration, mode: WidgetLayoutMode) {
            views.setTextViewText(R.id.widget_route, configuration.widgetTitle().uppercase())
            views.setTextViewText(R.id.widget_meta, "STAZIONE SELEZIONATA · DATI NON DISPONIBILI IN TEMPO REALE")
            views.setViewVisibility(R.id.widget_column_header, if (mode.showColumnHeader) View.VISIBLE else View.GONE)
            rowIds.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(rowIds[0], View.VISIBLE)
            views.setTextViewText(timeIds[0], "—")
            views.setTextViewText(destinationIds[0], configuration.targetLabel)
            views.setTextViewText(trainIds[0], "MONITORAGGIO STAZIONE")
            views.setTextViewText(platformIds[0], "—")
            views.setTextViewText(statusIds[0], "NON LIVE")
            views.setContentDescription(rowIds[0], "Stazione ${configuration.targetLabel}. Dati non disponibili in tempo reale.")
            views.setTextViewText(R.id.widget_direction, "Modifica")
            views.setTextViewText(R.id.widget_refresh, "Apri")
            views.setOnClickPendingIntent(R.id.widget_direction, configureIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, openAppIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, widgetId))
            manager.updateAppWidget(widgetId, views)
        }

        private fun renderTrainTarget(context: Context, manager: AppWidgetManager, widgetId: Int, views: RemoteViews, configuration: WidgetDisplayConfiguration, state: WidgetState, mode: WidgetLayoutMode) {
            val service = state.services.firstOrNull { it.number.equals(configuration.targetId, ignoreCase = true) }
            if (service == null) {
                renderNoSnapshot(context, manager, widgetId, views, configuration)
                return
            }
            views.setTextViewText(R.id.widget_route, configuration.widgetTitle("TRENO ${service.number}").uppercase())
            views.setTextViewText(R.id.widget_meta, state.compactMeta())
            views.setViewVisibility(R.id.widget_column_header, if (mode.showColumnHeader) View.VISIBLE else View.GONE)
            rowIds.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(rowIds[0], View.VISIBLE)
            views.setTextViewText(timeIds[0], service.expectedDeparture)
            views.setTextViewText(destinationIds[0], service.destination)
            views.setTextViewText(trainIds[0], "${service.operator.shortLabel} · ${service.category} ${service.number}")
            views.setTextViewText(platformIds[0], service.platform?.let { "BIN $it" } ?: "—")
            views.setTextViewText(statusIds[0], WidgetChangeHighlight.displayStatus(service, state.confidence))
            views.setTextColor(statusIds[0], statusColor(context, service.status))
            views.setInt(
                rowIds[0],
                "setBackgroundResource",
                if (WidgetChangeHighlight.shouldHighlight(service.status, state.confidence, state.freshness)) R.drawable.widget_row_variation else R.drawable.widget_row_background
            )
            views.setContentDescription(rowIds[0], service.accessibleLabel)
            views.setTextViewText(R.id.widget_direction, "Modifica")
            views.setTextViewText(R.id.widget_refresh, "↻")
            views.setOnClickPendingIntent(R.id.widget_direction, configureIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, actionIntent(context, ACTION_REFRESH, widgetId))
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, widgetId))
            manager.updateAppWidget(widgetId, views)
        }

        private fun renderPersonalSurface(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            views: RemoteViews,
            surface: WidgetSurface
        ) {
            val personal = PersonalTravelStore(context)
            rowIds.forEach { id -> views.setViewVisibility(id, View.GONE) }
            views.setViewVisibility(R.id.widget_column_header, View.VISIBLE)
            when (surface) {
                WidgetSurface.ACTIVE_TRIP -> {
                    val trip = personal.activeTrip()
                    val stop = trip?.nextStop
                    views.setTextViewText(R.id.widget_route, "VIAGGIO ATTIVO")
                    if (stop == null) {
                        views.setTextViewText(R.id.widget_meta, "NESSUNA PROSSIMA FERMATA · STATO LOCALE")
                    } else {
                        views.setTextViewText(R.id.widget_meta, "TRENO ${trip.trainNumber} · STATO LOCALE")
                        views.setViewVisibility(rowIds[0], View.VISIBLE)
                        views.setTextViewText(timeIds[0], stop.expected)
                        views.setTextViewText(destinationIds[0], stop.station)
                        views.setTextViewText(trainIds[0], "PROSSIMA FERMATA")
                        views.setTextViewText(platformIds[0], stop.platform?.let { "BIN $it" } ?: "—")
                        views.setTextViewText(statusIds[0], "LOCALE")
                        views.setContentDescription(rowIds[0], "Viaggio attivo. Prossima fermata ${stop.station}, prevista ${stop.expected}.")
                    }
                    views.setTextViewText(R.id.widget_direction, "VIAGGIO")
                }
                WidgetSurface.STATION_MONITOR -> {
                    val monitor = personal.stationMonitors(emptyList()).firstOrNull()
                    views.setTextViewText(R.id.widget_route, "STAZIONE MONITORATA")
                    views.setTextViewText(R.id.widget_meta, monitor?.let { "${it.stationName.uppercase()} · ${it.mode.label.uppercase()}" } ?: "NESSUNA STAZIONE SALVATA")
                    views.setViewVisibility(rowIds[0], View.VISIBLE)
                    views.setTextViewText(timeIds[0], "—")
                    views.setTextViewText(destinationIds[0], monitor?.stationName ?: "Aggiungi una stazione")
                    views.setTextViewText(trainIds[0], "DATI NON LIVE")
                    views.setTextViewText(platformIds[0], "—")
                    views.setTextViewText(statusIds[0], "INFO")
                    views.setContentDescription(rowIds[0], "Monitoraggio stazione. Dati non disponibili in tempo reale.")
                    views.setTextViewText(R.id.widget_direction, "STAZIONE")
                }
                WidgetSurface.ACTIVE_ROUTE -> Unit
            }
            views.setTextViewText(R.id.widget_refresh, "Apri")
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, openAppIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_direction, openAppIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_previous_page, openAppIntent(context, widgetId))
            views.setOnClickPendingIntent(R.id.widget_next_page, openAppIntent(context, widgetId))
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

        private fun statusColor(context: Context, status: ServiceStatus): Int = when (status) {
            ServiceStatus.ON_TIME -> ContextCompat.getColor(context, R.color.widget_on_surface)
            ServiceStatus.DELAYED -> ContextCompat.getColor(context, R.color.widget_warning)
            ServiceStatus.CANCELLED -> ContextCompat.getColor(context, R.color.widget_error)
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
            val direction = previous?.direction ?: Direction.OUTBOUND
            when (val result = BinarioDataGraph.routeDataSource(applicationContext).loadRoute(route, direction)) {
                is DataResult.Success -> WidgetStateStore.saveSnapshot(applicationContext, widgetId, result.snapshot, previous?.page ?: 0)
                is DataResult.Failure -> {
                    failed = true
                    WidgetStateStore.markUnavailable(applicationContext, widgetId, result.message)
                }
            }
        }
        RouteWidgetProvider.refreshAll(applicationContext)
        if (failed) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork("binario-widget-refresh", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

data class WidgetService(
    val number: String,
    val operator: RailOperator,
    val category: String,
    val destination: String,
    val expectedDeparture: String,
    val status: ServiceStatus,
    val delayMinutes: Int,
    val platform: String?
) {
    val statusLabel: String get() = when (status) {
        ServiceStatus.ON_TIME -> "OK"
        ServiceStatus.DELAYED -> "+$delayMinutes"
        ServiceStatus.CANCELLED -> "SOPP."
    }
    fun accessibleLabel(confidence: DataConfidence): String {
        val operational = if (confidence == DataConfidence.STATIC_ONLY) "Orario programmato; stato operativo non verificato" else statusLabel
        return "Operatore ${operator.accessibleLabel}. $category $number verso $destination. Partenza prevista $expectedDeparture. $operational. ${platform?.let { "Binario $it" } ?: "Binario non disponibile"}."
    }
}

data class WidgetState(
    val route: SavedRoute,
    val direction: Direction,
    val services: List<WidgetService>,
    val freshness: Freshness,
    val confidence: DataConfidence,
    val providerName: String,
    val qualityScore: Int?,
    val qualityBand: DataQualityBand,
    val qualityWarnings: Set<SnapshotWarning>,
    val retrievedAt: Long,
    val page: Int
) {
    val routeLabel: String get() = route.displayName(direction)

    fun compactMeta(pageLabel: String? = null): String = listOfNotNull(
        providerName.ifBlank { null },
        confidence.widgetLabel(),
        qualityScore?.let { "Q$it" },
        freshness.widgetLabel(),
        qualityWarnings.firstOrNull()?.widgetLabel(),
        pageLabel
    ).joinToString(" · ")

    fun accessibleDataSummary(): String = buildString {
        append("Provider $providerName. ${freshness.accessibleLabel}. ${confidence.accessibleLabel}. ${qualityBand.accessibleLabel}")
        qualityScore?.let { append(", punteggio qualità $it su 100") }
        if (qualityWarnings.isNotEmpty()) append(". Avvisi: ${qualityWarnings.joinToString { it.accessibleLabel }}")
    }
}

private fun DataConfidence.widgetLabel(): String = when (this) {
    DataConfidence.OPERATOR_PRIMARY -> "OPERATORE"
    DataConfidence.INFRASTRUCTURE_PRIMARY -> "INFRASTRUTTURA"
    DataConfidence.OPEN_DATA -> "OPEN DATA"
    DataConfidence.LICENSED_AGGREGATE -> "AGGREGATO"
    DataConfidence.STATIC_ONLY -> "PROGRAMMATO"
    DataConfidence.CACHED -> "CACHE"
    DataConfidence.DEMONSTRATION -> "DEMO"
}

private fun Freshness.widgetLabel(): String = when (this) {
    Freshness.FRESH -> "AGGIORNATO"
    Freshness.AGING -> "IN AGGIORNAMENTO"
    Freshness.STALE -> "ULTIMO DATO"
    Freshness.UNAVAILABLE -> "LIVE NON DISPONIBILE"
}

private fun SnapshotWarning.widgetLabel(): String = when (this) {
    SnapshotWarning.SOURCE_CONFLICT -> "CONFLITTO FONTI"
    SnapshotWarning.PARTIAL_ROUTE_MATCH -> "TRATTA PARZIALE"
    SnapshotWarning.STALE_SOURCE -> "FONTE NON RECENTE"
    SnapshotWarning.STATIC_ONLY -> "SOLO PROGRAMMATO"
    SnapshotWarning.DEMONSTRATION -> "DEMO"
    SnapshotWarning.INCOMPLETE_SERVICE -> "CORSA INCOMPLETA"
    SnapshotWarning.IDENTITY_AMBIGUOUS -> "IDENTITÀ DA VERIFICARE"
    SnapshotWarning.DELAY_DIVERGENCE -> "RITARDI DIVERGENTI"
}

object WidgetStateStore {
    private const val PREFS = "binario_widget_state"
    private const val KEY_ROUTE = "route"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_LINES = "lines"
    private const val KEY_FRESHNESS = "freshness"
    private const val KEY_CONFIDENCE = "confidence"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_QUALITY_SCORE = "quality_score"
    private const val KEY_QUALITY_BAND = "quality_band"
    private const val KEY_QUALITY_WARNINGS = "quality_warnings"
    private const val KEY_RETRIEVED = "retrieved"
    private const val KEY_PAGE = "page"
    private const val KEY_FAILURE = "last_failure"

    fun saveSnapshot(context: Context, widgetId: Int, snapshot: RouteSnapshot, page: Int = 0) {
        val orderedServices = snapshot.services.sortedWith(compareBy<TrainService> { it.expectedDeparture }.thenBy { it.number })
        val lines = orderedServices.map { service ->
            listOf(
                service.number,
                service.operator.name,
                service.category,
                service.destination,
                service.expectedDeparture,
                service.status.name,
                service.delayMinutes.toString(),
                service.platform.orEmpty()
            ).joinToString(",") { encode(it) }
        }.joinToString(";")
        prefs(context).edit {
            putString(key(widgetId, KEY_ROUTE), encodeRoute(snapshot.route))
            putString(key(widgetId, KEY_DIRECTION), snapshot.direction.name)
            putString(key(widgetId, KEY_LINES), lines)
            putString(key(widgetId, KEY_FRESHNESS), snapshot.freshness.name)
            putString(key(widgetId, KEY_CONFIDENCE), snapshot.confidence.name)
            putString(key(widgetId, KEY_PROVIDER), snapshot.providerName)
            snapshot.qualityScore?.let { putInt(key(widgetId, KEY_QUALITY_SCORE), it) } ?: remove(key(widgetId, KEY_QUALITY_SCORE))
            putString(key(widgetId, KEY_QUALITY_BAND), snapshot.qualityBand.name)
            putString(key(widgetId, KEY_QUALITY_WARNINGS), snapshot.qualityWarnings.joinToString("|") { it.name })
            putLong(key(widgetId, KEY_RETRIEVED), snapshot.retrievedAtEpochMillis)
            putInt(key(widgetId, KEY_PAGE), page)
            remove(key(widgetId, KEY_FAILURE))
        }
    }

    fun read(context: Context, widgetId: Int): WidgetState? {
        val route = prefs(context).getString(key(widgetId, KEY_ROUTE), null)?.let(::decodeRoute) ?: return null
        val direction = runCatching {
            Direction.valueOf(prefs(context).getString(key(widgetId, KEY_DIRECTION), Direction.OUTBOUND.name) ?: Direction.OUTBOUND.name)
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
        val lines = prefs(context).getString(key(widgetId, KEY_LINES), "").orEmpty().split(";").mapNotNull(::decodeService)
        return WidgetState(
            route = route,
            direction = direction,
            services = lines,
            freshness = freshness,
            confidence = confidence,
            providerName = prefs(context).getString(key(widgetId, KEY_PROVIDER), "Provider non disponibile").orEmpty(),
            qualityScore = if (prefs(context).contains(key(widgetId, KEY_QUALITY_SCORE))) prefs(context).getInt(key(widgetId, KEY_QUALITY_SCORE), 0) else null,
            qualityBand = qualityBand,
            qualityWarnings = warnings,
            retrievedAt = prefs(context).getLong(key(widgetId, KEY_RETRIEVED), 0L),
            page = prefs(context).getInt(key(widgetId, KEY_PAGE), 0)
        )
    }

    fun changePage(context: Context, widgetId: Int, delta: Int) {
        val state = read(context, widgetId) ?: return
        val maxPage = (state.services.size - 1).coerceAtLeast(0) / 5
        setPage(context, widgetId, (state.page + delta).coerceIn(0, maxPage))
    }

    fun setPage(context: Context, widgetId: Int, page: Int) {
        prefs(context).edit { putInt(key(widgetId, KEY_PAGE), page.coerceAtLeast(0)) }
    }

    fun switchDirection(context: Context, widgetId: Int) {
        val state = read(context, widgetId) ?: return
        val next = if (state.direction == Direction.OUTBOUND) Direction.RETURN else Direction.OUTBOUND
        prefs(context).edit {
            putString(key(widgetId, KEY_DIRECTION), next.name)
            putInt(key(widgetId, KEY_PAGE), 0)
        }
    }

    fun markUnavailable(context: Context, widgetId: Int, message: String? = null) {
        prefs(context).edit {
            putString(key(widgetId, KEY_FRESHNESS), Freshness.UNAVAILABLE.name)
            message?.takeIf { it.isNotBlank() }?.let { putString(key(widgetId, KEY_FAILURE), it) }
        }
    }

    fun lastFailure(context: Context, widgetId: Int): String? = prefs(context).getString(key(widgetId, KEY_FAILURE), null)

    internal fun shouldDiscardLegacyDemo(confidence: DataConfidence): Boolean =
        confidence == DataConfidence.DEMONSTRATION

    fun clear(context: Context, widgetId: Int) {
        prefs(context).edit { listOf(KEY_ROUTE, KEY_DIRECTION, KEY_LINES, KEY_FRESHNESS, KEY_CONFIDENCE, KEY_PROVIDER, KEY_QUALITY_SCORE, KEY_QUALITY_BAND, KEY_QUALITY_WARNINGS, KEY_RETRIEVED, KEY_PAGE, KEY_FAILURE).forEach { remove(key(widgetId, it)) } }
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

    private fun decodeService(value: String): WidgetService? {
        val parts = value.split(",").map(::decode)
        if (parts.size != 8) return null
        return runCatching {
            WidgetService(
                number = parts[0],
                operator = RailOperator.valueOf(parts[1]),
                category = parts[2],
                destination = parts[3],
                expectedDeparture = parts[4],
                status = ServiceStatus.valueOf(parts[5]),
                delayMinutes = parts[6].toInt(),
                platform = parts[7].ifBlank { null }
            )
        }.getOrNull()
    }

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun decode(value: String): String = String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}
