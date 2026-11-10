package im.binario.companion.binario

import android.content.Context
import android.content.ComponentName
import android.text.format.DateUtils
import im.binario.companion.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

internal object BinarioWidgetPresentation {
  /** Journeys are Italian trains: the app saves and shows their times as Italian wall-clock time. */
  const val TRAVEL_TIME_ZONE = "Europe/Rome"

  /** True when both instants fall on the same calendar day in Italy, whatever the phone's time zone. */
  fun sameTravelDay(first: Long, second: Long): Boolean {
    val zone = java.util.TimeZone.getTimeZone(TRAVEL_TIME_ZONE)
    val firstDay = java.util.Calendar.getInstance(zone).apply { timeInMillis = first }
    val secondDay = java.util.Calendar.getInstance(zone).apply { timeInMillis = second }
    return firstDay.get(java.util.Calendar.YEAR) == secondDay.get(java.util.Calendar.YEAR) &&
      firstDay.get(java.util.Calendar.DAY_OF_YEAR) == secondDay.get(java.util.Calendar.DAY_OF_YEAR)
  }

  /**
   * DateUtils flags of the departure label: the time alone on the same Italian day, otherwise also the
   * abbreviated weekday and date.
   */
  fun departureFlags(scheduledAt: Long, now: Long): Int =
    if (sameTravelDay(scheduledAt, now)) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY

  /**
   * Delay until the widget's state next changes: the departure, after which the journey is no
   * longer shown, or the payload expiry, whichever comes first. Null when neither is ahead.
   */
  fun nextTransitionDelayMillis(plannedJourneyAtUtcMs: Long?, expiresAtUtcMs: Long?, now: Long = System.currentTimeMillis()): Long? =
    listOfNotNull(plannedJourneyAtUtcMs, expiresAtUtcMs).filter { it > now }.minOrNull()?.let { it - now }
}

class BinarioStatusWidget : GlanceAppWidget() {
  /**
   * The departure as an Italian clock time (with the date when it is not today in Italy), the time the
   * user typed in the app, whatever the phone's time zone. A widget is redrawn only by the app or by
   * WorkManager, so a relative "in 10 min" would stay frozen between redraws.
   */
  private fun departureLabel(context: Context, scheduledAt: Long, now: Long = System.currentTimeMillis()): String {
    if (scheduledAt <= now) return context.getString(R.string.binario_unavailable)
    val flags = BinarioWidgetPresentation.departureFlags(scheduledAt, now)
    val formatter = java.util.Formatter(StringBuilder(), java.util.Locale.getDefault())
    return DateUtils.formatDateRange(context, formatter, scheduledAt, scheduledAt, flags, BinarioWidgetPresentation.TRAVEL_TIME_ZONE).toString()
  }
  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val state = BinarioWidgetStore.read(context)
    val voiceActivity = ComponentName(context, im.binario.companion.MainActivity::class.java)
    provideContent {
      Column(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF071525))).padding(16.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
      ) {
        Text(if (state.plannedJourneyTitle != null) context.getString(R.string.binario_next_journey) else "BINARIO", style = TextStyle(color = ColorProvider(Color(0xFF9EDBFF))))
        Text(state.plannedJourneyTitle ?: state.title, style = TextStyle(color = ColorProvider(Color.White)))
        Text(if (state.plannedJourneyAtUtcMs != null) departureLabel(context, state.plannedJourneyAtUtcMs) else state.subtitle, style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
        // One format string per language keeps the separator spacing: Android trims a trailing space of an unquoted resource.
        if (state.plannedJourneyTitle != null) Text(state.plannedReminderMinutes?.let { context.getString(R.string.binario_reminder_and_status, it, context.getString(R.string.binario_scheduled_not_realtime)) } ?: context.getString(R.string.binario_scheduled_not_realtime), style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
        Text(context.getString(R.string.binario_open_binario), style = TextStyle(color = ColorProvider(Color(0xFF9EDBFF))), modifier = GlanceModifier.clickable(onClick = actionStartActivity(voiceActivity)))
      }
    }
  }
}

class BinarioWidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    BinarioStatusWidget().updateAll(applicationContext)
    return Result.success()
  }

  companion object {
    private const val uniqueWorkName = "binario-widget-refresh-v1"
    private const val transitionWorkName = "binario-widget-transition-v1"
    private const val refreshMinutes = 15L

    /** One redraw at the next state change, so the departed journey does not wait for the periodic refresh. */
    fun scheduleTransition(context: Context, plannedJourneyAtUtcMs: Long?, expiresAtUtcMs: Long?) {
      val workManager = WorkManager.getInstance(context.applicationContext)
      val delay = BinarioWidgetPresentation.nextTransitionDelayMillis(plannedJourneyAtUtcMs, expiresAtUtcMs)
      if (delay == null) {
        workManager.cancelUniqueWork(transitionWorkName)
        return
      }
      val request = OneTimeWorkRequestBuilder<BinarioWidgetRefreshWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
      workManager.enqueueUniqueWork(transitionWorkName, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedule(context: Context) {
      val request = PeriodicWorkRequestBuilder<BinarioWidgetRefreshWorker>(refreshMinutes, TimeUnit.MINUTES).build()
      WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
        uniqueWorkName,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
      )
    }

    fun cancel(context: Context) {
      WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueWorkName)
      WorkManager.getInstance(context.applicationContext).cancelUniqueWork(transitionWorkName)
    }
  }
}

class BinarioStatusWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = BinarioStatusWidget()

  override fun onEnabled(context: Context) {
    super.onEnabled(context)
    BinarioWidgetRefreshWorker.schedule(context)
  }

  override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
    super.onUpdate(context, appWidgetManager, appWidgetIds)
    BinarioWidgetRefreshWorker.schedule(context)
  }

  override fun onDisabled(context: Context) {
    BinarioWidgetRefreshWorker.cancel(context)
    super.onDisabled(context)
  }
}
