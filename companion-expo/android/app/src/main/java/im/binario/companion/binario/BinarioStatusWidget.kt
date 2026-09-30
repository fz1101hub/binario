package im.binario.companion.binario

import android.content.Context
import android.content.ComponentName
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
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

internal object BinarioWidgetPresentation {
  fun countdownLabel(scheduledAt: Long, now: Long = System.currentTimeMillis()): String {
    if (scheduledAt <= now) return "Dati non disponibili in tempo reale."
    val minutes = kotlin.math.ceil((scheduledAt - now).toDouble() / 60_000).toLong()
    if (minutes < 60) return "Tra $minutes min"
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0L) "Tra $hours h" else "Tra $hours h $remainder min"
  }
}

class BinarioStatusWidget : GlanceAppWidget() {
  private fun countdownLabel(context: Context, scheduledAt: Long, now: Long = System.currentTimeMillis()): String {
    if (scheduledAt <= now) return context.getString(R.string.binario_unavailable)
    val minutes = kotlin.math.ceil((scheduledAt - now).toDouble() / 60_000).toInt()
    if (minutes < 60) return context.getString(R.string.binario_countdown_minutes, minutes)
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) context.getString(R.string.binario_countdown_hours, hours) else context.getString(R.string.binario_countdown_hours_minutes, hours, remainder)
  }
  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val state = BinarioWidgetStore.read(context)
    val voiceActivity = ComponentName(context, im.binario.companion.MainActivity::class.java)
    provideContent {
      Column(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(Color(0xFF071525))).padding(16.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
      ) {
        Text(if (state.plannedJourneyTitle != null) "PROSSIMA TRATTA · PROGRAMMATA" else "BINARIO", style = TextStyle(color = ColorProvider(Color(0xFF9EDBFF))))
        Text(state.plannedJourneyTitle ?: state.title, style = TextStyle(color = ColorProvider(Color.White)))
        Text(if (state.plannedJourneyAtUtcMs != null) countdownLabel(context, state.plannedJourneyAtUtcMs) else state.subtitle, style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
        if (state.plannedJourneyTitle != null) Text("${state.plannedReminderMinutes?.let { context.getString(R.string.binario_reminder, it) } ?: ""}${context.getString(R.string.binario_scheduled_not_realtime)}", style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
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
    private const val refreshMinutes = 15L

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
