package it.binario.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import it.binario.app.FavouriteTrainMatcher
import it.binario.app.FavouriteTrainStore
import it.binario.app.MainActivity
import it.binario.app.R
import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.KnownProviderIds
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.data.repository.TrainNumberQuery
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext

/**
 * "I miei treni": the favourite trains saved with the heart of the train detail, each with its next
 * station or last detection, delay, platform and cancellation or variation. A tap opens the train.
 */
class TrainsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
        WidgetAutoRefreshScheduler.reschedule(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        render(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_REFRESH -> TrainsWidgetRefreshWorker.enqueue(context)
            ACTION_NEXT_PAGE, ACTION_PREVIOUS_PAGE -> if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                changePage(context, widgetId, if (intent.action == ACTION_NEXT_PAGE) 1 else -1)
                render(context, AppWidgetManager.getInstance(context), widgetId)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = TrainsWidgetConfigurationStore(context)
        appWidgetIds.forEach { widgetId ->
            store.remove(widgetId)
            WidgetAutoRefreshStore.clear(context, WidgetKind.TRAINS, widgetId)
        }
        WidgetAutoRefreshScheduler.reschedule(context)
    }

    companion object {
        const val ACTION_REFRESH = "it.binario.app.widget.TRAINS_REFRESH"
        const val ACTION_NEXT_PAGE = "it.binario.app.widget.TRAINS_NEXT_PAGE"
        const val ACTION_PREVIOUS_PAGE = "it.binario.app.widget.TRAINS_PREVIOUS_PAGE"
        /** Extras of the row tap: MainActivity opens that train's detail (number search, then the matching run). */
        const val EXTRA_TRAIN_NUMBER = "it.binario.app.extra.FAVOURITE_TRAIN_NUMBER"
        const val EXTRA_TRAIN_ORIGIN = "it.binario.app.extra.FAVOURITE_TRAIN_ORIGIN"
        const val ACTION_OPEN_TRAIN = "it.binario.app.action.OPEN_FAVOURITE_TRAIN"

        private val rowIds = intArrayOf(R.id.trains_widget_row_0, R.id.trains_widget_row_1, R.id.trains_widget_row_2, R.id.trains_widget_row_3, R.id.trains_widget_row_4)
        private val titleIds = intArrayOf(R.id.trains_widget_title_0, R.id.trains_widget_title_1, R.id.trains_widget_title_2, R.id.trains_widget_title_3, R.id.trains_widget_title_4)
        private val detailIds = intArrayOf(R.id.trains_widget_detail_0, R.id.trains_widget_detail_1, R.id.trains_widget_detail_2, R.id.trains_widget_detail_3, R.id.trains_widget_detail_4)
        private val platformIds = intArrayOf(R.id.trains_widget_platform_0, R.id.trains_widget_platform_1, R.id.trains_widget_platform_2, R.id.trains_widget_platform_3, R.id.trains_widget_platform_4)
        private val statusIds = intArrayOf(R.id.trains_widget_status_0, R.id.trains_widget_status_1, R.id.trains_widget_status_2, R.id.trains_widget_status_3, R.id.trains_widget_status_4)

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, TrainsWidgetProvider::class.java)).forEach { render(context, manager, it) }
        }

        /** The favourites changed in the app: names are redrawn at once and a newly saved train is looked up. */
        fun onFavouritesChanged(context: Context, lookUpNewTrains: Boolean) {
            runCatching {
                refreshAll(context)
                if (lookUpNewTrains && TrainsWidgetConfigurationStore(context).all().isNotEmpty()) TrainsWidgetRefreshWorker.enqueue(context)
            }
            // The notice checks and the punctuality recording follow the favourite trains too.
            it.binario.app.notifications.LiveNotificationScheduling.onFavouritesChanged(context)
        }

        private fun visibleRows(manager: AppWidgetManager, widgetId: Int): Int {
            val options = manager.getAppWidgetOptions(widgetId)
            return WidgetLayoutMode.fromSize(
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300),
                options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
            ).visibleRows
        }

        private fun visibleTrains(context: Context, settings: TrainsWidgetSettings, now: Long): Pair<List<it.binario.app.ServiceMonitor>, Map<String, FavouriteTrainStatus>> {
            val monitors = FavouriteTrainStore(context).all()
            val statuses = FavouriteTrainStatusStore.all(context, monitors.map { it.number })
            val strings = TrainsWidgetStrings.from(context)
            val visible = TrainsWidgetPresenter.visible(monitors, statuses, settings, now, strings.locale) { strings.format(strings.trainNumber, it) }
            return visible to statuses
        }

        private fun changePage(context: Context, widgetId: Int, delta: Int) {
            val store = TrainsWidgetConfigurationStore(context)
            val settings = store.read(widgetId) ?: return
            val count = visibleTrains(context, settings, System.currentTimeMillis()).first.size
            store.setRowOffset(widgetId, WidgetRowPagination.move(store.rowOffset(widgetId), delta, count, visibleRows(AppWidgetManager.getInstance(context), widgetId)))
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.trains_widget)
            val store = TrainsWidgetConfigurationStore(context)
            val settings = store.read(widgetId)
            views.setTextViewText(R.id.trains_widget_refresh, context.getString(R.string.widget_refresh_symbol))
            views.setOnClickPendingIntent(R.id.trains_widget_refresh, actionIntent(context, ACTION_REFRESH, widgetId))
            views.setViewVisibility(R.id.trains_widget_empty, View.GONE)
            if (settings == null) {
                views.setTextViewText(R.id.trains_widget_header_title, context.getString(R.string.trains_widget_name))
                views.setTextViewText(R.id.trains_widget_meta, context.getString(R.string.trains_widget_configuration_subtitle))
                views.setTextViewText(R.id.trains_widget_updated, "")
                rowIds.forEach { views.setViewVisibility(it, View.GONE) }
                views.setViewVisibility(R.id.trains_widget_column_header, View.GONE)
                views.setOnClickPendingIntent(R.id.trains_widget_root, configureIntent(context, widgetId))
                views.setOnClickPendingIntent(R.id.trains_widget_refresh, configureIntent(context, widgetId))
                manager.updateAppWidget(widgetId, views)
                return
            }
            val now = System.currentTimeMillis()
            val strings = TrainsWidgetStrings.from(context)
            val title = settings.displayName.trim().ifBlank { context.getString(R.string.trains_widget_name) }
            views.setTextViewText(R.id.trains_widget_header_title, title)
            views.setOnClickPendingIntent(R.id.trains_widget_root, openAppIntent(context, widgetId))

            val allMonitors = FavouriteTrainStore(context).all()
            val (visible, statuses) = visibleTrains(context, settings, now)
            // «83% in orario» from the embedded statistics; the first draw of a process may come without it.
            val reliabilityStats = if (settings.showReliability) WidgetReliabilityStats.cachedOrLoad(context, withCatalogue = true) { refreshAll(it) } else null
            val reliabilityStrings = WidgetReliabilityStrings.from(context)
            val lastUpdate = statuses.values.maxOfOrNull { it.retrievedAt } ?: 0L
            views.setTextViewText(R.id.trains_widget_updated, updatedAtHeader(context, lastUpdate))
            val pageSize = visibleRows(manager, widgetId)
            val offset = WidgetRowPagination.normalizedOffset(store.rowOffset(widgetId), visible.size, pageSize)
            if (offset != store.rowOffset(widgetId)) store.setRowOffset(widgetId, offset)
            val page = visible.drop(offset).take(pageSize)
            val hidden = allMonitors.size - visible.size
            views.setTextViewText(
                R.id.trains_widget_meta,
                listOfNotNull(
                    context.resources.getQuantityString(R.plurals.trains_widget_count, allMonitors.size, allMonitors.size),
                    if (hidden > 0) context.resources.getQuantityString(R.plurals.trains_widget_hidden_count, hidden, hidden) else null,
                    if (visible.size > pageSize) context.getString(
                        R.string.widget_page_label,
                        WidgetRowPagination.pageNumber(offset, pageSize),
                        WidgetRowPagination.pageCount(visible.size, pageSize)
                    ) else null
                ).joinToString(" · ")
            )
            views.setContentDescription(R.id.trains_widget_header_title, context.getString(R.string.trains_widget_title_accessibility, title, updatedAtHeader(context, lastUpdate)))
            views.setViewVisibility(R.id.trains_widget_column_header, if (page.isNotEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.trains_widget_column_platform, if (settings.showPlatform) View.VISIBLE else View.GONE)
            if (page.isEmpty()) {
                views.setViewVisibility(R.id.trains_widget_empty, View.VISIBLE)
                views.setTextViewText(
                    R.id.trains_widget_empty,
                    context.getString(if (allMonitors.isEmpty()) R.string.trains_widget_empty else R.string.trains_widget_none_running)
                )
            }
            rowIds.forEachIndexed { index, id ->
                val monitor = page.getOrNull(index)
                if (monitor == null) {
                    views.setViewVisibility(id, View.GONE)
                    return@forEachIndexed
                }
                val status = statuses[monitor.number.uppercase(Locale.ROOT)]
                val reliability = reliabilityStats?.let { stats ->
                    ReliabilityMarks.favouriteRow(stats, monitor.number, monitor.origin.ifBlank { status?.origin.orEmpty() }, CatalogueStationCodes::cachedCode, CatalogueStationCodes.loaded)
                }?.let { WidgetReliabilityText.of(it, reliabilityStrings) }
                val row = TrainsWidgetPresenter.row(monitor, status, settings, now, strings, reliability)
                views.setViewVisibility(id, View.VISIBLE)
                views.setTextViewText(titleIds[index], row.title)
                views.setTextViewText(detailIds[index], row.detail)
                views.setViewVisibility(detailIds[index], if (row.detail.isBlank()) View.GONE else View.VISIBLE)
                views.setTextViewText(platformIds[index], row.platform)
                views.setTextColor(platformIds[index], ContextCompat.getColor(context, if (row.platformChanged) R.color.widget_warning else R.color.widget_on_surface))
                views.setViewVisibility(platformIds[index], if (settings.showPlatform) View.VISIBLE else View.GONE)
                views.setTextViewText(statusIds[index], row.status)
                views.setTextColor(statusIds[index], ContextCompat.getColor(context, row.emphasis.colorRes()))
                views.setContentDescription(id, row.accessibility)
                views.setOnClickPendingIntent(id, openTrainIntent(context, widgetId, row.number, row.origin))
            }
            val paged = visible.size > pageSize
            views.setViewVisibility(R.id.trains_widget_previous_page, if (paged) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.trains_widget_next_page, if (paged) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(R.id.trains_widget_previous_page, actionIntent(context, ACTION_PREVIOUS_PAGE, widgetId))
            views.setOnClickPendingIntent(R.id.trains_widget_next_page, actionIntent(context, ACTION_NEXT_PAGE, widgetId))
            manager.updateAppWidget(widgetId, views)
        }

        private fun TrainsRowEmphasis.colorRes(): Int = when (this) {
            TrainsRowEmphasis.NORMAL -> R.color.widget_on_surface
            TrainsRowEmphasis.EARLY -> R.color.widget_early
            TrainsRowEmphasis.WARNING -> R.color.widget_warning
            TrainsRowEmphasis.ERROR -> R.color.widget_error
            TrainsRowEmphasis.MUTED -> R.color.widget_on_surface_muted
        }

        private fun actionIntent(context: Context, action: String, widgetId: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            "trains-$action-$widgetId".hashCode(),
            Intent(context, TrainsWidgetProvider::class.java).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun openAppIntent(context: Context, widgetId: Int): PendingIntent = PendingIntent.getActivity(
            context,
            "trains-open-$widgetId".hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun openTrainIntent(context: Context, widgetId: Int, number: String, origin: String): PendingIntent = PendingIntent.getActivity(
            context,
            "trains-train-$widgetId-$number".hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_TRAIN)
                .putExtra(EXTRA_TRAIN_NUMBER, number)
                .putExtra(EXTRA_TRAIN_ORIGIN, origin)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun configureIntent(context: Context, widgetId: Int): PendingIntent = PendingIntent.getActivity(
            context,
            "trains-configure-$widgetId".hashCode(),
            Intent(context, TrainsWidgetConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Looks up the runs of the favourite trains with the app's train-number search (its sources and their order unchanged),
 * a few trains at a time. The statuses are shared by all trains widgets, so one refresh counts for every instance. An
 * automatic refresh ([widgetIds] given) asks only for the trains near their run, as [TrainsWidgetLookupPlanner] says.
 */
internal object TrainsWidgetRefresher {
    private const val PARALLEL_LOOKUPS = 3

    suspend fun refresh(context: Context, widgetIds: Set<Int>?): Boolean {
        if (widgetIds == null) WidgetInstances.pruneOrphans(context)
        val widgets = TrainsWidgetConfigurationStore(context).all()
        if (widgets.none { (widgetId, _) -> widgetIds == null || widgetId in widgetIds }) return true
        val attemptAt = System.currentTimeMillis()
        widgets.forEach { (widgetId, _) -> WidgetAutoRefreshStore.recordAttempt(context, WidgetKind.TRAINS, widgetId, attemptAt) }
        val monitors = FavouriteTrainStore(context).all()
        FavouriteTrainStatusStore.retainOnly(context, monitors.map { it.number })
        val statuses = FavouriteTrainStatusStore.all(context, monitors.map { it.number })
        val now = Instant.ofEpochMilli(attemptAt)
        val lookups = monitors.mapNotNull { monitor ->
            val status = statuses[monitor.number.uppercase(Locale.ROOT)]
            TrainsWidgetLookupPlanner.lookupDate(monitor, status, now, automatic = widgetIds != null)?.let { date ->
                Triple(monitor, date, if (TrainsWidgetLookupPlanner.mayBeItalo(status)) emptySet<SourceId>() else setOf(KnownProviderIds.ITALO_STATUS))
            }
        }
        if (lookups.isEmpty()) {
            TrainsWidgetProvider.refreshAll(context)
            return true
        }
        val search = BinarioDataGraph.trainNumberSearchService(context)
        val results = lookups.chunked(PARALLEL_LOOKUPS).flatMap { chunk ->
            coroutineScope {
                chunk.map { (monitor, date, skipping) ->
                    async {
                        val update = runCatching { search.search(TrainNumberQuery(monitor.number, date), skipping).lastOrNull() }.getOrNull()
                        val matches = update?.matches.orEmpty()
                        val service = FavouriteTrainMatcher.pick(matches.map { it.service }, monitor)
                        val match = service?.let { picked -> matches.firstOrNull { it.service === picked } }
                        monitor to match
                    }
                }.awaitAll()
            }
        }
        var failed = false
        results.forEach { (monitor, match) ->
            if (match != null) {
                FavouriteTrainStatusStore.save(
                    context,
                    FavouriteTrainStatusMapper.fromService(match.service, match.snapshot.providerName, match.snapshot.retrievedAtEpochMillis)
                )
            } else {
                failed = true
                FavouriteTrainStatusStore.markStale(context, monitor.number)
            }
        }
        TrainsWidgetProvider.refreshAll(context)
        return !failed
    }
}

/** The refresh button of the trains widget. */
class TrainsWidgetRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        TrainsWidgetRefresher.refresh(applicationContext, null)
        // A train not found today is shown as last known and stale; retrying would not find it either.
        Result.success()
    }

    companion object {
        private const val ONE_TIME_WORK = "binario-trains-widget-refresh"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrainsWidgetRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
