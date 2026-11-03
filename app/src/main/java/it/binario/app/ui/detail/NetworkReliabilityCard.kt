package it.binario.app.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.data.Station
import it.binario.app.data.TrainService
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
