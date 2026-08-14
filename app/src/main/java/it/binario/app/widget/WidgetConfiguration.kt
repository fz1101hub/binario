package it.binario.app.widget

import android.content.Context
import android.util.Base64
import androidx.core.content.edit

enum class WidgetTargetType(val label: String) {
    ROUTE("Tratta"),
    STATION("Stazione"),
    TRAIN("Treno")
}

data class WidgetDisplayConfiguration(
    val targetType: WidgetTargetType,
    val targetId: String,
    val targetLabel: String
)

class WidgetConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_widget_configuration_v1", Context.MODE_PRIVATE)

    fun read(widgetId: Int): WidgetDisplayConfiguration? = preferences.getString("widget_$widgetId", null)?.let(::decode)

    fun save(widgetId: Int, configuration: WidgetDisplayConfiguration) {
        preferences.edit { putString("widget_$widgetId", encode(configuration)) }
    }

    fun remove(widgetId: Int) {
        preferences.edit { remove("widget_$widgetId") }
    }

    private fun encode(value: WidgetDisplayConfiguration): String = listOf(value.targetType.name, value.targetId, value.targetLabel)
        .joinToString("|") { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }

    private fun decode(value: String): WidgetDisplayConfiguration? = runCatching {
        val parts = value.split("|").map { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }
        if (parts.size != 3) null else WidgetDisplayConfiguration(WidgetTargetType.valueOf(parts[0]), parts[1], parts[2])
    }.getOrNull()
}
