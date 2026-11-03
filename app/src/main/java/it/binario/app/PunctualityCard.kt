package it.binario.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * train number: average delay, share on time and over 15 minutes, the stop where the delay grows
 * most, the distribution of the delays and the per-date history. Nothing here is fetched or
 * shared: the history lives only in local preferences (and in the user's own backup file).
 */
@Composable
internal fun PunctualityCard(stats: PunctualityStats, onClear: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var showAll by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.punctuality_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.punctuality_based_on_device), style = MaterialTheme.typography.bodySmall, color = muted)
            Text(pluralStringResource(R.plurals.punctuality_observation_count, stats.count, stats.count), style = MaterialTheme.typography.bodySmall, color = muted)
            Text(
                stats.averageDelayMinutes?.let { minutes ->
                    stringResource(R.string.punctuality_average_delay, pluralStringResource(R.plurals.minutes_short, minutes, minutes))
                } ?: stringResource(R.string.punctuality_average_delay_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(stringResource(R.string.punctuality_on_time_share, stats.onTimePercent), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.punctuality_late_share, stats.latePercent), style = MaterialTheme.typography.bodyMedium)
            if (stats.cancelledCount > 0) {
                Text(stringResource(R.string.punctuality_cancelled_count, stats.cancelledCount), style = MaterialTheme.typography.bodyMedium)
            }
            stats.worstStop?.let { stop ->
                Text(
                    stringResource(
                        R.string.punctuality_worst_stop,
                        stop.station,
                        pluralStringResource(R.plurals.minutes_short, stop.averageIncreaseMinutes, stop.averageIncreaseMinutes)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (stats.distribution.isNotEmpty()) {
                Text(stringResource(R.string.punctuality_distribution_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                val largest = stats.distribution.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                PunctualityBucket.entries.forEach { bucket ->
                    DistributionBar(bucketLabel(bucket), stats.distribution[bucket] ?: 0, largest, bucketColor(bucket))
                }
            }
            Text(stringResource(R.string.punctuality_recent_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            val history = if (showAll) stats.history else stats.history.take(HISTORY_COLLAPSED)
            history.forEach { observation ->
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
            if (!showAll && stats.history.size > HISTORY_COLLAPSED) {
                TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.punctuality_history_show_all)) }
            }
            Text(stringResource(R.string.punctuality_local_note), style = MaterialTheme.typography.labelSmall, color = muted)
            TextButton(onClick = onClear) { Text(stringResource(R.string.punctuality_clear)) }
        }
    }
}

/** One bar of the distribution: label, a bar proportional to the largest bucket and the count. */
@Composable
private fun DistributionBar(label: String, count: Int, largest: Int, color: Color) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, modifier = Modifier.width(112.dp), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).height(10.dp)) {
            if (count > 0) {
                Box(
                    Modifier.fillMaxWidth(count.toFloat() / largest)
                        .height(10.dp)
                        .background(color, RoundedCornerShape(4.dp))
                )
            }
        }
        Text(count.toString(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun bucketLabel(bucket: PunctualityBucket): String = stringResource(
    when (bucket) {
        PunctualityBucket.ON_TIME -> R.string.punctuality_bucket_on_time
        PunctualityBucket.UP_TO_15 -> R.string.punctuality_bucket_up_to_15
        PunctualityBucket.UP_TO_30 -> R.string.punctuality_bucket_up_to_30
        PunctualityBucket.OVER_30 -> R.string.punctuality_bucket_over_30
        PunctualityBucket.CANCELLED -> R.string.punctuality_bucket_cancelled
    }
)

@Composable
private fun bucketColor(bucket: PunctualityBucket): Color = when (bucket) {
    PunctualityBucket.ON_TIME -> MaterialTheme.colorScheme.primary
    PunctualityBucket.UP_TO_15 -> MaterialTheme.colorScheme.tertiary
    PunctualityBucket.UP_TO_30, PunctualityBucket.OVER_30 -> MaterialTheme.colorScheme.error
    PunctualityBucket.CANCELLED -> MaterialTheme.colorScheme.outline
}

private const val HISTORY_COLLAPSED = 7
