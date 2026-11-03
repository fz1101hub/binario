package it.binario.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.data.reliability.NetworkReliabilityRepository
import it.binario.app.data.reliability.NetworkReliabilityStats
import it.binario.app.data.reliability.StopReliability
import it.binario.app.journeys.ConnectionNetworkReliability
import it.binario.app.journeys.JourneyNetworkReliability
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The texts of the network statistics lines, as functions of their numbers: resolved from the app resources on screen
 * ([rememberNetworkReliabilityStrings]) and from the resource files in the JVM tests.
 */
class NetworkReliabilityStrings(
    /** «In orario 27 volte su 30» (plural on the first number). */
    val onTime: (onTime: Int, total: Int) -> String,
    /** «REG 2153 in orario 20 volte su 30». */
    val legOnTime: (label: String, onTime: Int, total: Int) -> String,
    /** «ritardo medio 3 min». */
    val meanDelay: (minutes: Int) -> String,
    /** «soppresso 1 volta». */
    val cancelled: (count: Int) -> String,
    /** «Coincidenza a Milano Centrale riuscita 25 volte su 30». */
    val connection: (station: String, successes: Int, total: Int) -> String,
    /** «Soppresso 12 volte su 12», for a train cancelled on every day observed. */
    val cancelledShare: (cancelled: Int, days: Int) -> String,
    /** «REG 2153 soppresso 12 volte su 12». */
    val legCancelledShare: (label: String, cancelled: Int, days: Int) -> String,
    /** The sentence TalkBack reads, which says the statistics are unofficial. */
    val spoken: (text: String) -> String
)

/** One line of a card: its parts, shown with « · » and read with commas. */
@Immutable
data class NetworkReliabilityLine(val parts: List<String>) {
    val text: String get() = parts.joinToString(" · ")
    val spokenText: String get() = parts.joinToString(", ")
}

/** The lines of a result card and the single sentence TalkBack reads for them. */
@Immutable
data class NetworkReliabilityText(val lines: List<NetworkReliabilityLine>, val spoken: String)

/** The lines of the result cards, from the statistics of a row. */
object NetworkReliabilityLines {
    /**
     * A train at the stop where it is left; [label] names it when a journey has more than one train. A train cancelled
     * on every day observed has only its cancellations, never an on-time count.
     */
    fun train(stop: StopReliability, strings: NetworkReliabilityStrings, label: String? = null): NetworkReliabilityLine =
        if (stop.cancelledEveryDay) NetworkReliabilityLine(
            listOf(
                if (label == null) strings.cancelledShare(stop.daysCancelled, stop.daysObserved)
                else strings.legCancelledShare(label, stop.daysCancelled, stop.daysObserved)
            )
        ) else NetworkReliabilityLine(
            listOfNotNull(
                if (label == null) strings.onTime(stop.onTime, stop.samples) else strings.legOnTime(label, stop.onTime, stop.samples),
                strings.meanDelay(stop.shownMeanDelayMinutes),
                stop.daysCancelled.takeIf { it > 0 }?.let(strings.cancelled)
            )
        )

    fun connection(connection: ConnectionNetworkReliability, strings: NetworkReliabilityStrings): NetworkReliabilityLine =
        NetworkReliabilityLine(listOf(strings.connection(connection.stationLabel, connection.odds.successes, connection.odds.samples)))

    /** The weakest train leg (named when there are several) and the weakest change. */
    fun journey(journey: JourneyNetworkReliability, legLabel: (Int) -> String, strings: NetworkReliabilityStrings): List<NetworkReliabilityLine> {
        val weakest = journey.weakestLeg
        return listOfNotNull(
            train(weakest.stop, strings, legLabel(weakest.legIndex).takeIf { journey.legs.size > 1 }),
            journey.weakestConnection?.let { connection(it, strings) }
        )
    }

    fun text(lines: List<NetworkReliabilityLine>, strings: NetworkReliabilityStrings): NetworkReliabilityText =
        NetworkReliabilityText(lines, strings.spoken(lines.joinToString(". ") { it.spokenText }))
}

/** The embedded network statistics, read once off the main thread; null while reading, when absent or disabled. */
@Composable
internal fun rememberNetworkReliability(): NetworkReliabilityStats? {
    val context = LocalContext.current
    val stats by produceState(NetworkReliabilityRepository.cached(), context) { value = NetworkReliabilityRepository.load(context) }
    return stats
}

@Composable
internal fun rememberNetworkReliabilityStrings(): NetworkReliabilityStrings {
    val resources = LocalResources.current
    return remember(resources) {
        NetworkReliabilityStrings(
            onTime = { count, total -> resources.getQuantityString(R.plurals.reliability_on_time_count, count, count, total) },
            legOnTime = { label, count, total -> resources.getQuantityString(R.plurals.reliability_leg_on_time_count, count, label, count, total) },
            meanDelay = { minutes ->
                resources.getString(R.string.reliability_mean_delay, resources.getQuantityString(R.plurals.minutes_short, minutes, minutes))
            },
            cancelled = { count -> resources.getQuantityString(R.plurals.reliability_cancelled_count, count, count) },
            connection = { station, count, total -> resources.getQuantityString(R.plurals.reliability_connection_count, count, station, count, total) },
            cancelledShare = { count, days -> resources.getQuantityString(R.plurals.reliability_cancelled_share, count, count, days) },
            legCancelledShare = { label, count, days -> resources.getQuantityString(R.plurals.reliability_leg_cancelled_share, count, label, count, days) },
            spoken = { text -> resources.getString(R.string.reliability_spoken, text) }
        )
    }
}

/** «Statistiche non ufficiali di Binario su dati ViaggiaTreno, aggiornate al 9 nov 2026» ([windowEnd], the last day). */
@Composable
internal fun networkReliabilityAttribution(windowEnd: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(R.string.reliability_attribution, windowEnd.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))
}

/**
 * The reliability lines of a result card, small and muted under the trains, with the statistics icon; plain text
 * that never relies on colour. The card reads them in its own description.
 */
@Composable
internal fun NetworkReliabilityLinesText(lines: List<NetworkReliabilityLine>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.QueryStats, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            lines.forEach { line -> Text(line.text, style = MaterialTheme.typography.labelMedium, color = color) }
        }
    }
}
