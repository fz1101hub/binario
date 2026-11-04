package it.binario.app.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.data.Station
import it.binario.app.data.TrainService
import it.binario.app.data.reliability.DelayDistribution
import it.binario.app.data.reliability.NetworkReliabilityDetail
import it.binario.app.data.reliability.NetworkReliabilityStations
import it.binario.app.ui.common.networkReliabilityAttribution
import it.binario.app.ui.common.rememberNetworkReliability

/**
 * The network statistics of [service] for its detail, or null (no statistics, demonstration data, too few samples).
 * [stationLookup] binds the published stop names to the catalogue, whose railway codes the statistics use.
 */
@Composable
internal fun rememberNetworkReliabilityDetail(
    service: TrainService,
    stations: List<Station>,
    stationLookup: (String) -> Station?,
    isDemonstration: Boolean
): NetworkReliabilityDetail? {
    val stats = rememberNetworkReliability()
    val codes = remember(stations) { NetworkReliabilityStations(stations) }
    return remember(stats, service, codes, isDemonstration) {
        if (stats == null || isDemonstration) null else NetworkReliabilityDetail.of(stats, service, stationLookup, codes)
    }
}

/**
 * «Affidabilità ultimi 30 giorni» in the Info tab: on time and mean delay at the stop measured, days observed and
 * cancellations, the stop where the delay grows most, what "on time" means for this train and the attribution of the
 * unofficial statistics. Every line is a sentence of its own; nothing is said by colour alone.
 */
@Composable
internal fun NetworkReliabilityCard(detail: NetworkReliabilityDetail, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val arrival = detail.arrival
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).testTag("detail-network-reliability"),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QueryStats, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    pluralStringResource(R.plurals.reliability_title, detail.windowDays, detail.windowDays),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            if (arrival.cancelledEveryDay) {
                // Cancelled on every day observed: no delays to measure.
                Text(
                    pluralStringResource(R.plurals.reliability_cancelled_share, detail.daysCancelled, detail.daysCancelled, detail.daysObserved),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    pluralStringResource(R.plurals.reliability_detail_on_time, arrival.onTime, detail.arrivalName, arrival.onTime, arrival.samples),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(stringResource(R.string.punctuality_average_delay, minutes(arrival.shownMeanDelayMinutes)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.reliability_detail_days, detail.daysObserved, detail.daysCancelled), style = MaterialTheme.typography.bodyMedium)
                detail.distribution?.let { DelayDistributionBlock(it, detail.maxDelayMinutes) }
                    ?: detail.maxDelayMinutes?.let { Text(stringResource(R.string.reliability_max_delay, minutes(it)), style = MaterialTheme.typography.bodyMedium) }
            }
            val worst = detail.worstStop
            val worstName = detail.worstStopName
            if (worst != null && worstName != null) {
                Text(stringResource(R.string.punctuality_worst_stop, worstName, minutes(worst.increaseMinutes)), style = MaterialTheme.typography.bodyMedium)
            }
            if (!arrival.cancelledEveryDay) {
                Text(stringResource(R.string.reliability_threshold_note, minutes(arrival.onTimeLimitMinutes)), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Text(networkReliabilityAttribution(detail.windowEnd), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
private fun minutes(value: Int): String = pluralStringResource(R.plurals.minutes_short, value, value)

/** The texts of the delay distribution, as functions of their numbers (resources on screen, resource files in tests). */
class DelayDistributionStrings(
    val title: String,
    /** «In orario», «1–5 min», «6–15 min», «16–30 min», «Oltre 30 min», in band order. */
    val bands: List<String>,
    /** «40% · 12 volte» (plural on the count). */
    val share: (percent: Int, count: Int) -> String,
    /** «Ritardo massimo: 47 min». */
    val maxDelay: (minutes: Int) -> String
)

/** One band of the distribution as shown: its label and «40% · 12 volte». */
data class DelayDistributionRow(val label: String, val value: String, val count: Int)

/** The rows of the distribution and the single sentence TalkBack reads for the whole block. */
object DelayDistributionTexts {
    fun rows(distribution: DelayDistribution, strings: DelayDistributionStrings): List<DelayDistributionRow> {
        val percentages = distribution.percentages
        return distribution.counts.mapIndexed { index, count -> DelayDistributionRow(strings.bands[index], strings.share(percentages[index], count), count) }
    }

    /** «Distribuzione dei ritardi: In orario 40% · 12 volte, 1–5 min 27% · 8 volte, …. Ritardo massimo: 47 min». */
    fun spoken(rows: List<DelayDistributionRow>, maxDelayMinutes: Int?, strings: DelayDistributionStrings): String =
        listOfNotNull(
            strings.title + ": " + rows.joinToString(", ") { "${it.label} ${it.value}" },
            maxDelayMinutes?.let(strings.maxDelay)
        ).joinToString(". ")
}

@Composable
private fun rememberDelayDistributionStrings(): DelayDistributionStrings {
    val resources = LocalResources.current
    return remember(resources) {
        fun minutes(value: Int) = resources.getQuantityString(R.plurals.minutes_short, value, value)
        DelayDistributionStrings(
            title = resources.getString(R.string.reliability_distribution_title),
            bands = listOf(
                resources.getString(R.string.reliability_band_on_time),
                resources.getString(R.string.reliability_band_range, 1, minutes(5)),
                resources.getString(R.string.reliability_band_range, 6, minutes(15)),
                resources.getString(R.string.reliability_band_range, 16, minutes(30)),
                resources.getString(R.string.reliability_band_over, minutes(30))
            ),
            share = { percent, count -> resources.getQuantityString(R.plurals.reliability_band_share, count, percent, count) },
            maxDelay = { value -> resources.getString(R.string.reliability_max_delay, minutes(value)) }
        )
    }
}

/**
 * How the delays at the stop spread: a thin bar of the five bands (proportional, decorative) and one row per band
 * with its share and count, then the largest delay. TalkBack reads the whole block as one sentence; the rows say in
 * words what the colours of the bar show.
 */
@Composable
private fun DelayDistributionBlock(distribution: DelayDistribution, maxDelayMinutes: Int?) {
    val strings = rememberDelayDistributionStrings()
    val rows = remember(distribution, strings) { DelayDistributionTexts.rows(distribution, strings) }
    val spoken = remember(rows, maxDelayMinutes, strings) { DelayDistributionTexts.spoken(rows, maxDelayMinutes, strings) }
    val colors = bandColors()
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).testTag("detail-delay-distribution").clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(strings.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            rows.forEachIndexed { index, row ->
                if (row.count > 0) Box(Modifier.weight(row.count.toFloat()).fillMaxHeight().background(colors[index]))
            }
        }
        rows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colors[index]))
                Spacer(Modifier.width(8.dp))
                Text(row.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(row.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
        maxDelayMinutes?.let { Text(strings.maxDelay(it), style = MaterialTheme.typography.bodyMedium) }
    }
}

/** From on time to over 30 minutes: the theme's primary, then tertiary, then error, lighter for the milder band. */
@Composable
private fun bandColors(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    return listOf(scheme.primary, scheme.primary.copy(alpha = 0.5f), scheme.tertiary, scheme.error.copy(alpha = 0.55f), scheme.error)
}
