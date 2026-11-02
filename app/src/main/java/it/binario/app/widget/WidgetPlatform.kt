package it.binario.app.widget

import android.content.Context
import androidx.core.content.ContextCompat
import it.binario.app.R
import it.binario.app.data.PlatformChange

// The platform column of the route, station and trains widgets: "BIN 7", or "BIN 7 / era 5" in the widget
// attention colour when the source moved the train away from the timetable platform.

/** Visible text of the platform column; "—" without a platform. */
internal fun widgetPlatformText(context: Context, actual: String?, scheduled: String?): String {
    val shown = actual?.trim()?.takeIf(String::isNotEmpty) ?: return "—"
    val change = PlatformChange.of(shown, scheduled)
    return if (change != null) context.getString(R.string.widget_platform_changed, change.actual, change.scheduled)
    else context.getString(R.string.widget_platform_short, shown)
}

/** The attention colour (AA on the widget rows) for a changed platform, the row text colour otherwise. */
internal fun widgetPlatformColor(context: Context, actual: String?, scheduled: String?): Int =
    ContextCompat.getColor(context, if (PlatformChange.of(actual, scheduled) != null) R.color.widget_warning else R.color.widget_on_surface)
