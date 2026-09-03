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

class BinarioStatusWidget : GlanceAppWidget() {
  private fun countdownLabel(scheduledAt: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = kotlin.math.ceil((scheduledAt - now).toDouble() / 60_000).toInt().coerceAtLeast(0)
    if (minutes < 60) return "Tra $minutes min"
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (remainder == 0) "Tra $hours h" else "Tra $hours h $remainder min"
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
        Text(if (state.plannedJourneyAtUtcMs != null) countdownLabel(state.plannedJourneyAtUtcMs) else state.subtitle, style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
        if (state.plannedJourneyTitle != null) Text("${state.plannedReminderMinutes?.let { "Promemoria $it min · " } ?: ""}Orario programmato, non realtime", style = TextStyle(color = ColorProvider(Color(0xFFD5E4F1))))
        Text("Apri Binario e detta", style = TextStyle(color = ColorProvider(Color(0xFF9EDBFF))), modifier = GlanceModifier.clickable(onClick = actionStartActivity(voiceActivity)))
      }
    }
  }
}

class BinarioStatusWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = BinarioStatusWidget()
}
