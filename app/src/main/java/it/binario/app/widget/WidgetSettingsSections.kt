package it.binario.app.widget

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import it.binario.app.R
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle

// Settings sections shared by the three widget configure screens. Every option is a whole row of at
// least 48 dp with a role (radio, checkbox, switch), so TalkBack reads label and state together.

@Composable
internal fun WidgetSettingsCard(title: String, description: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
internal fun WidgetChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit, description: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun WidgetCheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun WidgetSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, description: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A time shown as a button that opens the system time picker (24-hour, as railway times). */
@Composable
internal fun WidgetTimeButton(label: String, accessibilityLabel: String, time: LocalTime, onTimeChange: (LocalTime) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { TimePickerDialog(context, { _, hour, minute -> onTimeChange(LocalTime.of(hour, minute)) }, time.hour, time.minute, true).show() },
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = accessibilityLabel }
    ) { Text(label) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutoRefreshSection(value: AutoRefreshSettings, onChange: (AutoRefreshSettings) -> Unit) {
    WidgetSettingsCard(stringResource(R.string.widget_settings_auto_refresh_title)) {
        WidgetChoiceRow(
            stringResource(R.string.widget_settings_auto_refresh_off),
            value.mode == AutoRefreshMode.OFF,
            { onChange(value.copy(mode = AutoRefreshMode.OFF)) },
            stringResource(R.string.widget_settings_auto_refresh_off_description)
        )
        WidgetChoiceRow(
            stringResource(R.string.widget_settings_auto_refresh_windows),
            value.mode == AutoRefreshMode.WINDOWS,
            { onChange(value.copy(mode = AutoRefreshMode.WINDOWS, windows = value.windows.ifEmpty { listOf(TimeWindow.DEFAULT_MORNING) }, days = value.days.ifEmpty { DayPreset.WEEKDAYS.days })) },
            stringResource(R.string.widget_settings_auto_refresh_windows_description)
        )
        WidgetChoiceRow(
            stringResource(R.string.widget_settings_auto_refresh_always),
            value.mode == AutoRefreshMode.ALWAYS,
            { onChange(value.copy(mode = AutoRefreshMode.ALWAYS, interval = RefreshInterval.MINUTES_15)) },
            stringResource(R.string.widget_settings_auto_refresh_always_description)
        )
        if (value.mode == AutoRefreshMode.WINDOWS) WindowSettings(value, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WindowSettings(value: AutoRefreshSettings, onChange: (AutoRefreshSettings) -> Unit) {
    Column {
        Text(stringResource(R.string.widget_settings_interval_title), modifier = Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        RefreshInterval.entries.forEach { interval ->
            WidgetChoiceRow(
                pluralStringResource(R.plurals.widget_settings_interval_minutes, interval.minutes, interval.minutes),
                value.interval == interval,
                { onChange(value.copy(interval = interval)) }
            )
        }

        Text(stringResource(R.string.widget_settings_days_title), modifier = Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        val preset = DayPreset.of(value.days)
        WidgetChoiceRow(stringResource(R.string.widget_settings_days_weekdays), preset == DayPreset.WEEKDAYS, { onChange(value.copy(days = DayPreset.WEEKDAYS.days)) })
        WidgetChoiceRow(stringResource(R.string.widget_settings_days_every_day), preset == DayPreset.EVERY_DAY, { onChange(value.copy(days = DayPreset.EVERY_DAY.days)) })
        WidgetChoiceRow(stringResource(R.string.widget_settings_days_custom), preset == DayPreset.CUSTOM, {
            if (preset != DayPreset.CUSTOM) onChange(value.copy(days = value.days - DayOfWeek.FRIDAY))
        })
        if (preset == DayPreset.CUSTOM) {
            val locale = LocalConfiguration.current.locales[0]
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val selected = day in value.days
                    val name = day.getDisplayName(TextStyle.FULL, locale)
                    FilterChip(
                        selected = selected,
                        onClick = { onChange(value.copy(days = if (selected) value.days - day else value.days + day)) },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = name }
                    )
                }
            }
            if (value.days.isEmpty()) {
                Text(stringResource(R.string.widget_settings_days_none), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(stringResource(R.string.widget_settings_windows_title), modifier = Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge)
        val windows = value.effectiveWindows.ifEmpty { listOf(TimeWindow.DEFAULT_MORNING) }
        windows.forEachIndexed { index, window ->
            val number = index + 1
            val start = window.start.format(HH_MM)
            val end = window.end.format(HH_MM)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.widget_settings_window_label, number), modifier = Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodyMedium)
                WidgetTimeButton(
                    stringResource(R.string.widget_settings_window_from, start),
                    stringResource(R.string.widget_settings_window_from_accessibility, number, start),
                    window.start
                ) { time -> onChange(value.copy(windows = windows.replace(index, window.copy(start = time)))) }
                WidgetTimeButton(
                    stringResource(R.string.widget_settings_window_to, end),
                    stringResource(R.string.widget_settings_window_to_accessibility, number, end),
                    window.end
                ) { time -> onChange(value.copy(windows = windows.replace(index, window.copy(end = time)))) }
            }
        }
        if (windows.size < AutoRefreshSettings.MAX_WINDOWS) {
            TextButton(onClick = { onChange(value.copy(windows = windows + TimeWindow.DEFAULT_EVENING)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.widget_settings_window_add))
            }
        } else {
            TextButton(onClick = { onChange(value.copy(windows = windows.take(1))) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.widget_settings_window_remove))
            }
        }
        Text(stringResource(R.string.widget_settings_window_midnight_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TrainTypeExclusionSection(excluded: Set<TrainTypeGroup>, onChange: (Set<TrainTypeGroup>) -> Unit) {
    WidgetSettingsCard(stringResource(R.string.widget_settings_exclude_title), stringResource(R.string.widget_settings_exclude_description)) {
        TrainTypeGroup.entries.forEach { group ->
            val hidden = group in excluded
            WidgetCheckRow(stringResource(group.labelRes()), hidden) { checked -> onChange(if (checked) excluded + group else excluded - group) }
        }
    }
}

private fun TrainTypeGroup.labelRes(): Int = when (this) {
    TrainTypeGroup.HIGH_SPEED -> R.string.widget_settings_type_high_speed
    TrainTypeGroup.INTERCITY_NIGHT -> R.string.widget_settings_type_intercity
    TrainTypeGroup.REGIONAL_SUBURBAN -> R.string.widget_settings_type_regional
    TrainTypeGroup.ITALO -> R.string.widget_settings_type_italo
    TrainTypeGroup.REPLACEMENT_BUS -> R.string.widget_settings_type_bus
    TrainTypeGroup.INTERNATIONAL -> R.string.widget_settings_type_international
}

private fun <T> List<T>.replace(index: Int, value: T): List<T> = mapIndexed { position, item -> if (position == index) value else item }
