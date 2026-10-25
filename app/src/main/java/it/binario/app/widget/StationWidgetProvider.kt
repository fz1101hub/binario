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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.binario.app.MainActivity
import it.binario.app.R
import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import it.binario.app.data.RailTime
import it.binario.app.data.Station
import it.binario.app.data.core.BoardKind
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.repository.StationBoardAggregateResult
import it.binario.app.data.repository.StationBoardAggregateService
import it.binario.app.resolve
import it.binario.app.updateTimeMessage
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Home-screen station board: the next departures of one station chosen per widget instance. */
class StationWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
        StationWidgetRefreshWorker.ensurePeriodicRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        render(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) StationWidgetRefreshWorker.enqueue(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = StationWidgetConfigurationStore(context)
        appWidgetIds.forEach { widgetId ->
            store.remove(widgetId)
            StationWidgetStateStore.clear(context, widgetId)
        }
    }

    override fun onDisabled(context: Context) {
        // The last station widget is gone: no periodic board request may outlive it.
        StationWidgetRefreshWorker.cancel(context)
    }

    companion object {
        const val ACTION_REFRESH = "it.binario.app.widget.STATION_REFRESH"
        private val rowIds = intArrayOf(R.id.station_widget_row_0, R.id.station_widget_row_1, R.id.station_widget_row_2, R.id.station_widget_row_3)
        private val timeIds = intArrayOf(R.id.station_widget_time_0, R.id.station_widget_time_1, R.id.station_widget_time_2, R.id.station_widget_time_3)
        private val destinationIds = intArrayOf(R.id.station_widget_destination_0, R.id.station_widget_destination_1, R.id.station_widget_destination_2, R.id.station_widget_destination_3)
        private val trainIds = intArrayOf(R.id.station_widget_train_0, R.id.station_widget_train_1, R.id.station_widget_train_2, R.id.station_widget_train_3)
        private val platformIds = intArrayOf(R.id.station_widget_platform_0, R.id.station_widget_platform_1, R.id.station_widget_platform_2, R.id.station_widget_platform_3)
        private val statusIds = intArrayOf(R.id.station_widget_status_0, R.id.station_widget_status_1, R.id.station_widget_status_2, R.id.station_widget_status_3)

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, StationWidgetProvider::class.java)).forEach { render(context, manager, it) }
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val mode = layoutMode(manager, widgetId)
            val views = RemoteViews(context.packageName, R.layout.station_widget)
            val strings = StationWidgetStrings.from(context)
            views.setTextViewTextSize(R.id.station_widget_title, TypedValue.COMPLEX_UNIT_SP, mode.routeTextSizeSp)
            timeIds.forEach { views.setTextViewTextSize(it, TypedValue.COMPLEX_UNIT_SP, mode.rowTextSizeSp) }
            destinationIds.forEach { views.setTextViewTextSize(it, TypedValue.COMPLEX_UNIT_SP, mode.rowTextSizeSp) }
            views.setTextViewText(R.id.station_widget_refresh, context.getString(R.string.widget_refresh_symbol))
            views.setOnClickPendingIntent(R.id.station_widget_refresh, refreshIntent(context, widgetId))

            val station = StationWidgetConfigurationStore(context).read(widgetId)
            if (station == null) {
                views.setTextViewText(R.id.station_widget_title, context.getString(R.string.station_widget_configuration_title))
                views.setTextViewText(R.id.station_widget_meta, context.getString(R.string.station_widget_configuration_subtitle))
                views.setContentDescription(R.id.station_widget_title, context.getString(R.string.station_widget_configuration_subtitle))
                showRows(views, emptyList(), null, context)
                views.setViewVisibility(R.id.station_widget_empty, View.GONE)
                views.setViewVisibility(R.id.station_widget_column_header, View.GONE)
                views.setOnClickPendingIntent(R.id.station_widget_root, configureIntent(context, widgetId))
                views.setOnClickPendingIntent(R.id.station_widget_refresh, configureIntent(context, widgetId))
                manager.updateAppWidget(widgetId, views)
                return
            }

            views.setTextViewText(R.id.station_widget_title, station.name)
            views.setOnClickPendingIntent(R.id.station_widget_root, openAppIntent(context, widgetId))
            val snapshot = StationWidgetStateStore.read(context, widgetId)?.takeIf { it.stationId == station.id }
            if (snapshot == null) {
                val failure = StationWidgetStateStore.lastFailure(context, widgetId)?.resolve(context.resources)
                val meta = failure ?: context.getString(R.string.station_widget_waiting)
                views.setTextViewText(R.id.station_widget_meta, meta)
                views.setContentDescription(R.id.station_widget_title, context.getString(R.string.widget_unavailable_accessibility, station.name, meta))
                showRows(views, emptyList(), null, context)
                views.setViewVisibility(R.id.station_widget_column_header, View.GONE)
                views.setViewVisibility(R.id.station_widget_empty, View.GONE)
                manager.updateAppWidget(widgetId, views)
                return
            }

            val now = System.currentTimeMillis()
            val freshness = snapshot.freshnessAt(now)
            val updatedAt = updatedAtLabel(context, snapshot.retrievedAt)
            val departures = StationWidgetPresenter.upcoming(snapshot.departures, freshness, now, mode.visibleRows)
            views.setTextViewText(R.id.station_widget_meta, StationWidgetPresenter.meta(snapshot, freshness, updatedAt, strings))
            views.setContentDescription(
                R.id.station_widget_title,
                StationWidgetPresenter.titleAccessibility(snapshot, freshness, updateTimeMessage(snapshot.retrievedAt).resolve(context.resources), strings)
            )
            views.setViewVisibility(R.id.station_widget_column_header, if (mode.showColumnHeader && departures.isNotEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.station_widget_empty, if (departures.isEmpty()) View.VISIBLE else View.GONE)
            showRows(views, departures.map { StationWidgetPresenter.row(it, freshness, strings) }, openAppIntent(context, widgetId), context)
            manager.updateAppWidget(widgetId, views)
        }

        private fun showRows(views: RemoteViews, rows: List<StationWidgetRow>, onOpen: PendingIntent?, context: Context) {
            rowIds.forEachIndexed { index, id ->
                val row = rows.getOrNull(index)
                if (row == null) {
                    views.setViewVisibility(id, View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(id, View.VISIBLE)
                views.setTextViewText(timeIds[index], row.time)
                views.setTextViewText(destinationIds[index], row.destination)
                views.setTextViewText(trainIds[index], row.train)
                views.setTextViewText(platformIds[index], row.platform)
                views.setTextViewText(statusIds[index], row.status)
                views.setTextColor(statusIds[index], ContextCompat.getColor(context, row.emphasis.colorRes()))
                views.setContentDescription(id, row.accessibility)
                onOpen?.let { views.setOnClickPendingIntent(id, it) }
            }
        }

        private fun StationWidgetEmphasis.colorRes(): Int = when (this) {
            StationWidgetEmphasis.NORMAL -> R.color.widget_on_surface
            StationWidgetEmphasis.WARNING -> R.color.widget_warning
            StationWidgetEmphasis.ERROR -> R.color.widget_error
        }

        private fun layoutMode(manager: AppWidgetManager, widgetId: Int): WidgetLayoutMode {
            val options = manager.getAppWidgetOptions(widgetId)
            return WidgetLayoutMode.fromSize(
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
            )
        }

        private fun refreshIntent(context: Context, widgetId: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            "station-refresh-$widgetId".hashCode(),
            Intent(context, StationWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /**
         * The app has no deep link to a station board, so a tap opens the app. The launcher
         * action keeps this PendingIntent distinct from the route widget's, whose extras carry
         * its route.
         */
        private fun openAppIntent(context: Context, widgetId: Int): PendingIntent = PendingIntent.getActivity(
            context,
            "station-open-$widgetId".hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun configureIntent(context: Context, widgetId: Int): PendingIntent = PendingIntent.getActivity(
            context,
            "station-configure-$widgetId".hashCode(),
            Intent(context, StationWidgetConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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

/** Loads the board once per configured station; widgets showing the same station share the request. */
class StationWidgetRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val widgets = StationWidgetConfigurationStore(applicationContext).all()
        if (widgets.isEmpty()) return@withContext Result.success()
        val service = StationBoardAggregateService(BinarioDataGraph.stationBoardDisplayProviders())
        var failed = false
        widgets.groupBy { (_, station) -> station.id }.values.forEach { group ->
            val station = group.first().second
            val result = if (service.hasProviders) service.load(station, BoardKind.DEPARTURES) else null
            if (result is StationBoardAggregateResult.Success) {
                val snapshot = StationWidgetSnapshot.fromBoard(result.board, Instant.now())
                group.forEach { (widgetId, _) -> StationWidgetStateStore.save(applicationContext, widgetId, snapshot) }
            } else {
                // Without any active board source a retry cannot help; a failed request can.
                if (service.hasProviders) failed = true
                group.forEach { (widgetId, _) ->
                    StationWidgetStateStore.markUnavailable(applicationContext, widgetId, UiMessage(UiMessageKey.STATION_BOARD_UNAVAILABLE))
                }
            }
        }
        StationWidgetProvider.refreshAll(applicationContext)
        if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val ONE_TIME_WORK = "binario-station-widget-refresh"
        private const val PERIODIC_WORK = "binario-station-widget-periodic-refresh"
        private val networkConnected = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<StationWidgetRefreshWorker>()
                .setConstraints(networkConnected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.REPLACE, request)
        }

        /** Same cadence as the route widget: never more often than every fifteen minutes. */
        fun ensurePeriodicRefresh(context: Context) {
            val request = PeriodicWorkRequestBuilder<StationWidgetRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConnected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK)
        }
    }
}

/** The station of each widget instance, independent of the app's favourites or catalogue. */
class StationWidgetConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_station_widget_configuration_v1", Context.MODE_PRIVATE)

    fun read(widgetId: Int): Station? = preferences.getString(key(widgetId), null)?.let(StationWidgetCodec::decodeStation)

    fun all(): List<Pair<Int, Station>> = preferences.all.mapNotNull { (key, value) ->
        val widgetId = key.removePrefix("widget_").toIntOrNull() ?: return@mapNotNull null
        val station = (value as? String)?.let(StationWidgetCodec::decodeStation) ?: return@mapNotNull null
        widgetId to station
    }

    fun save(widgetId: Int, station: Station) {
        preferences.edit { putString(key(widgetId), StationWidgetCodec.encodeStation(station)) }
    }

    fun remove(widgetId: Int) {
        preferences.edit { remove(key(widgetId)) }
    }

    private fun key(widgetId: Int): String = "widget_$widgetId"
}

object StationWidgetStateStore {
    private const val PREFS = "binario_station_widget_state"
    private const val KEY_STATION_ID = "station_id"
    private const val KEY_STATION_NAME = "station_name"
    private const val KEY_DEPARTURES = "departures"
    private const val KEY_PROVIDERS = "providers"
    private const val KEY_RETRIEVED = "retrieved"
    private const val KEY_UNAVAILABLE = "unavailable"
    private const val KEY_FAILURE = "last_failure"
    private val keys = listOf(KEY_STATION_ID, KEY_STATION_NAME, KEY_DEPARTURES, KEY_PROVIDERS, KEY_RETRIEVED, KEY_UNAVAILABLE, KEY_FAILURE)

    fun save(context: Context, widgetId: Int, snapshot: StationWidgetSnapshot) {
        prefs(context).edit {
            putString(key(widgetId, KEY_STATION_ID), snapshot.stationId)
            putString(key(widgetId, KEY_STATION_NAME), snapshot.stationName)
            putString(key(widgetId, KEY_DEPARTURES), StationWidgetCodec.encodeDepartures(snapshot.departures))
            putString(key(widgetId, KEY_PROVIDERS), StationWidgetCodec.encodeNames(snapshot.providerNames))
            putLong(key(widgetId, KEY_RETRIEVED), snapshot.retrievedAt)
            remove(key(widgetId, KEY_UNAVAILABLE))
            remove(key(widgetId, KEY_FAILURE))
        }
    }

    fun read(context: Context, widgetId: Int): StationWidgetSnapshot? {
        val preferences = prefs(context)
        val stationId = preferences.getString(key(widgetId, KEY_STATION_ID), null) ?: return null
        return StationWidgetSnapshot(
            stationId = stationId,
            stationName = preferences.getString(key(widgetId, KEY_STATION_NAME), null).orEmpty(),
            departures = StationWidgetCodec.decodeDepartures(preferences.getString(key(widgetId, KEY_DEPARTURES), null).orEmpty()),
            providerNames = StationWidgetCodec.decodeNames(preferences.getString(key(widgetId, KEY_PROVIDERS), null).orEmpty()),
            retrievedAt = preferences.getLong(key(widgetId, KEY_RETRIEVED), 0L),
            unavailable = preferences.getBoolean(key(widgetId, KEY_UNAVAILABLE), false)
        )
    }

    /** Keeps the rows for context; the render then drops every live claim. */
    fun markUnavailable(context: Context, widgetId: Int, failure: UiMessage) {
        prefs(context).edit {
            putBoolean(key(widgetId, KEY_UNAVAILABLE), true)
            putString(key(widgetId, KEY_FAILURE), WidgetStateStore.encodeFailure(failure))
        }
    }

    fun lastFailure(context: Context, widgetId: Int): UiMessage? =
        prefs(context).getString(key(widgetId, KEY_FAILURE), null)?.let(WidgetStateStore::decodeFailure)

    fun clear(context: Context, widgetId: Int) {
        prefs(context).edit { keys.forEach { remove(key(widgetId, it)) } }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(widgetId: Int, suffix: String): String = "widget_${widgetId}_$suffix"
}
