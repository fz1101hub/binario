package it.binario.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Puntualità" card of the train detail, from the observations this device recorded for the
 * train number. Nothing here is fetched or shared: the history lives only in local preferences.
 */
@Composable
internal fun PunctualityCard(stats: PunctualityStats, onClear: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.punctuality_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(pluralStringResource(R.plurals.punctuality_observation_count, stats.count, stats.count), style = MaterialTheme.typography.bodySmall, color = muted)
            Text(stringResource(R.string.punctuality_on_time_share, stats.onTimePercent), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                stats.averageDelayMinutes?.let { minutes ->
                    stringResource(R.string.punctuality_average_delay, pluralStringResource(R.plurals.minutes_short, minutes, minutes))
                } ?: stringResource(R.string.punctuality_average_delay_unavailable),
                style = MaterialTheme.typography.bodyMedium
            )
            if (stats.cancelledCount > 0) {
                Text(stringResource(R.string.punctuality_cancelled_count, stats.cancelledCount), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.punctuality_recent_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            stats.recent.forEach { observation ->
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(observation.serviceDate.format(dateFormatter), style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            observation.cancelled -> stringResource(R.string.run_status_cancelled)
                            observation.delayMinutes > 0 -> stringResource(R.string.delay_minutes, observation.delayMinutes)
                            else -> stringResource(R.string.operational_on_time)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(stringResource(R.string.punctuality_local_note), style = MaterialTheme.typography.labelSmall, color = muted)
            TextButton(onClick = onClear) { Text(stringResource(R.string.punctuality_clear)) }
        }
    }
}
