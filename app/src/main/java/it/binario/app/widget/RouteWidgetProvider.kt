package it.binario.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Base64
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
import it.binario.app.R
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailDataRepository
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.accessibleUpdateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RouteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_NEXT_PAGE -> {
                WidgetStateStore.changePage(context, 1)
                refreshAll(context)
            }
            ACTION_PREVIOUS_PAGE -> {
                WidgetStateStore.changePage(context, -1)
                refreshAll(context)
            }
            ACTION_SWITCH_DIRECTION -> {
                WidgetStateStore.switchDirection(context)
                refreshAll(context)
                WidgetRefreshWorker.enqueue(context)
            }
            ACTION_REFRESH -> WidgetRefreshWorker.enqueue(context)
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
        private val platformIds = intArrayOf(R.id.widget_platform_0, R.id.widget_platform_1, R.id.widget_platform_2, R.id.widget_platform_3, R.id.widget_platform_4)
        private val statusIds = intArrayOf(R.id.widget_status_0, R.id.widget_status_1, R.id.widget_status_2, R.id.widget_status_3, R.id.widget_status_4)

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, RouteWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { render(context, manager, it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val state = WidgetStateStore.read(context)
            val views = RemoteViews(context.packageName, R.layout.route_widget)
            if (state == null) {
                views.setTextViewText(R.id.widget_route, context.getString(R.string.widget_empty_title))
                views.setTextViewText(R.id.widget_meta, context.getString(R.string.widget_empty_subtitle))
                rowIds.forEach { id -> views.setViewVisibility(id, View.GONE) }
                views.setViewVisibility(R.id.widget_column_header, View.GONE)
                views.setTextViewText(R.id.widget_direction, "Configura")
                views.setTextViewText(R.id.widget_refresh, "Apri")
                views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, widgetId))
                views.setOnClickPendingIntent(R.id.widget_refresh, openAppIntent(context, widgetId))
                manager.updateAppWidget(widgetId, views)
                return
            }

            val pageSize = 5
            val maxPage = (state.services.size - 1).coerceAtLeast(0) / pageSize
            val page = state.page.coerceIn(0, maxPage)
            if (page != state.page) WidgetStateStore.setPage(context, page)
            val services = state.services.drop(page * pageSize).take(pageSize)

            views.setTextViewText(R.id.widget_route, state.routeLabel)
            views.setTextViewText(
                R.id.widget_meta,
                "${state.freshness.accessibleLabel} · ${accessibleUpdateTime(state.retrievedAt)} · pagina ${page + 1} di ${maxPage + 1}"
            )
            views.setViewVisibility(R.id.widget_column_header, View.VISIBLE)
            views.setContentDescription(
                R.id.widget_route,
                "Apri Binario. ${state.routeLabel}. ${state.freshness.accessibleLabel}. ${accessibleUpdateTime(state.retrievedAt)}"
            )
            rowIds.forEachIndexed { index, id ->
                val service = services.getOrNull(index)
                if (service == null) {
                    views.setViewVisibility(id, View.GONE)
                } else {
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setTextViewText(timeIds[index], service.expectedDeparture)
                    views.setTextViewText(destinationIds[index], "${service.destination}\n${service.operator.shortLabel} · ${service.category} ${service.number}")
                    views.setTextViewText(platformIds[index], service.platform?.let { "Bin. $it" } ?: "—")
                    views.setTextViewText(statusIds[index], service.statusLabel)
                    views.setContentDescription(id, service.accessibleLabel)
                    views.setTextColor(statusIds[index], statusColor(context, service.status))
                    views.setOnClickPendingIntent(id, openAppIntent(context, widgetId))
                }
            }
            views.setTextViewText(R.id.widget_direction, "${state.direction.label} ↔")
            views.setTextViewText(R.id.widget_refresh, "Aggiorna")
            views.setOnClickPendingIntent(R.id.widget_previous_page, actionIntent(context, ACTION_PREVIOUS_PAGE, widgetId))
            views.setOnClickPendingIntent(R.id.widget_next_page, actionIntent(context, ACTION_NEXT_PAGE, widgetId))
            views.setOnClickPendingIntent(R.id.widget_direction, actionIntent(context, ACTION_SWITCH_DIRECTION, widgetId))
            views.setOnClickPendingIntent(R.id.widget_refresh, actionIntent(context, ACTION_REFRESH, widgetId))
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, widgetId))
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

        private fun statusColor(context: Context, status: ServiceStatus): Int = when (status) {
            ServiceStatus.ON_TIME -> ContextCompat.getColor(context, R.color.widget_on_surface)
            ServiceStatus.DELAYED -> ContextCompat.getColor(context, R.color.widget_warning)
            ServiceStatus.CANCELLED -> ContextCompat.getColor(context, R.color.widget_error)
        }
    }
}

class WidgetRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val previous = WidgetStateStore.read(applicationContext) ?: return@withContext Result.success()
        when (val result = RailDataRepository().loadRoute(previous.route, previous.direction)) {
            is DataResult.Success -> {
                WidgetStateStore.saveSnapshot(applicationContext, result.snapshot, previous.page)
                RouteWidgetProvider.refreshAll(applicationContext)
                Result.success()
            }
            is DataResult.Failure -> {
                WidgetStateStore.markUnavailable(applicationContext)
                RouteWidgetProvider.refreshAll(applicationContext)
                Result.retry()
            }
        }
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
        ServiceStatus.ON_TIME -> "In orario"
        ServiceStatus.DELAYED -> "+$delayMinutes min"
        ServiceStatus.CANCELLED -> "Soppresso"
    }
    val accessibleLabel: String
        get() = "Operatore ${operator.accessibleLabel}. $category $number verso $destination. Partenza prevista $expectedDeparture. $statusLabel. ${platform?.let { "Binario $it" } ?: "Binario non disponibile"}."
}

data class WidgetState(
    val route: SavedRoute,
    val direction: Direction,
    val services: List<WidgetService>,
    val freshness: Freshness,
    val retrievedAt: Long,
    val page: Int
) {
    val routeLabel: String get() = route.displayName(direction)
}

object WidgetStateStore {
    private const val PREFS = "binario_widget_state"
    private const val KEY_ROUTE = "route"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_LINES = "lines"
    private const val KEY_FRESHNESS = "freshness"
    private const val KEY_RETRIEVED = "retrieved"
    private const val KEY_PAGE = "page"

    fun saveSnapshot(context: Context, snapshot: RouteSnapshot, page: Int = 0) {
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
            putString(KEY_ROUTE, encodeRoute(snapshot.route))
            putString(KEY_DIRECTION, snapshot.direction.name)
            putString(KEY_LINES, lines)
            putString(KEY_FRESHNESS, snapshot.freshness.name)
            putLong(KEY_RETRIEVED, snapshot.retrievedAtEpochMillis)
            putInt(KEY_PAGE, page)
        }
    }

    fun read(context: Context): WidgetState? {
        val route = prefs(context).getString(KEY_ROUTE, null)?.let(::decodeRoute) ?: return null
        val direction = runCatching {
            Direction.valueOf(prefs(context).getString(KEY_DIRECTION, Direction.OUTBOUND.name) ?: Direction.OUTBOUND.name)
        }.getOrDefault(Direction.OUTBOUND)
        val freshness = runCatching {
            Freshness.valueOf(prefs(context).getString(KEY_FRESHNESS, Freshness.UNAVAILABLE.name) ?: Freshness.UNAVAILABLE.name)
        }.getOrDefault(Freshness.UNAVAILABLE)
        val lines = prefs(context).getString(KEY_LINES, "").orEmpty().split(";").mapNotNull(::decodeService)
        return WidgetState(route, direction, lines, freshness, prefs(context).getLong(KEY_RETRIEVED, 0L), prefs(context).getInt(KEY_PAGE, 0))
    }

    fun changePage(context: Context, delta: Int) {
        val state = read(context) ?: return
        val maxPage = (state.services.size - 1).coerceAtLeast(0) / 5
        setPage(context, (state.page + delta).coerceIn(0, maxPage))
    }

    fun setPage(context: Context, page: Int) {
        prefs(context).edit { putInt(KEY_PAGE, page.coerceAtLeast(0)) }
    }

    fun switchDirection(context: Context) {
        val state = read(context) ?: return
        val next = if (state.direction == Direction.OUTBOUND) Direction.RETURN else Direction.OUTBOUND
        prefs(context).edit {
            putString(KEY_DIRECTION, next.name)
            putInt(KEY_PAGE, 0)
        }
    }

    fun markUnavailable(context: Context) {
        prefs(context).edit { putString(KEY_FRESHNESS, Freshness.UNAVAILABLE.name) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
