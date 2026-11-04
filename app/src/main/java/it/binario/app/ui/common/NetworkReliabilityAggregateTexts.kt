package it.binario.app.ui.common

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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.data.reliability.HourBand
import it.binario.app.data.reliability.LateTrain
import it.binario.app.data.reliability.RoutePunctuality
import it.binario.app.data.reliability.StationPunctuality
import java.time.LocalDate

/**
 * The texts of the route and station aggregates of the network statistics, as functions of their numbers: resolved
 * from the app resources on screen ([rememberReliabilityAggregateStrings]) and from the resource files in the tests.
 */
class ReliabilityAggregateStrings(
    /** «Questa tratta: 83% in orario». */
    val routeOnTime: (percent: Int) -> String,
    /** «Arrivi in questa stazione: 78% in orario». */
    val stationOnTime: (percent: Int) -> String,
    /** «ritardo medio 2 min». */
    val meanDelay: (minutes: Int) -> String,
    /** «12 treni». */
    val trains: (count: Int) -> String,
    /** «109 corse osservate in 30 giorni». */
    val runsObserved: (runs: Int, days: Int) -> String,
    /** «Partenze più puntuali: 6–9 · meno puntuali: 18–21». */
    val hours: (best: String, worst: String) -> String,
    /** «6–9», the hours of a band. */
    val hourBand: (startHour: Int, endHour: Int) -> String,
    /** «REG 2153: in ritardo 12 volte su 28». */
    val lateTrain: (label: String, late: Int, total: Int) -> String,
    /** «REG 2153 da Milano Centrale», for two trains of the same number. */
    val trainFrom: (label: String, origin: String) -> String,
    /** The sentence TalkBack reads, which says the statistics are unofficial. */
    val spoken: (text: String) -> String
)

/** The lines of a route or station aggregate and the one sentence TalkBack reads for them. */
@Immutable
data class ReliabilityAggregateText(val lines: List<NetworkReliabilityLine>, val spoken: String)

/** The lines of the route and station aggregates, from their numbers. */
object ReliabilityAggregateLines {
    fun band(band: HourBand, strings: ReliabilityAggregateStrings): String = strings.hourBand(band.startHour, band.endHour)

    /**
     * «Questa tratta: 83% in orario · ritardo medio 2 min · 12 treni · 109 corse osservate in 30 giorni», then the best
     * and the worst departure hours when the statistics tell them apart, then [extra] lines (the weekday remark).
     */
    fun route(route: RoutePunctuality, strings: ReliabilityAggregateStrings, extra: List<NetworkReliabilityLine> = emptyList()): ReliabilityAggregateText {
        val lines = listOfNotNull(
            NetworkReliabilityLine(
                listOf(
                    strings.routeOnTime(route.onTimePercent),
                    strings.meanDelay(route.shownMeanDelayMinutes),
                    strings.trains(route.trains),
                    strings.runsObserved(route.samples, route.days)
                )
            ),
            if (route.bestBand != null && route.worstBand != null) {
                NetworkReliabilityLine(listOf(strings.hours(band(route.bestBand, strings), band(route.worstBand, strings))))
            } else null
        ) + extra
        return ReliabilityAggregateText(lines, strings.spoken(lines.joinToString(". ") { it.spokenText }))
    }

    /** «Arrivi in questa stazione: 78% in orario · ritardo medio 4 min · 41 treni · 1040 corse osservate in 30 giorni». */
    fun station(station: StationPunctuality, strings: ReliabilityAggregateStrings): NetworkReliabilityLine = NetworkReliabilityLine(
        listOf(
            strings.stationOnTime(station.onTimePercent),
            strings.meanDelay(station.shownMeanDelayMinutes),
            strings.trains(station.trains),
            strings.runsObserved(station.samples, station.days)
        )
    )

    /**
     * One line per train often late, «REG 2153: in ritardo 12 volte su 28»; two trains of the same number (other
     * origins) are told apart by the catalogue name of their origin when it is known.
     */
    fun lateTrains(trains: List<LateTrain>, strings: ReliabilityAggregateStrings, originName: (String) -> String?): List<String> {
        val repeated = trains.groupingBy { it.category + " " + it.number }.eachCount().filterValues { it > 1 }.keys
        return trains.map { train ->
            val label = listOf(train.category, train.number).filter(String::isNotBlank).joinToString(" ")
            val named = if (train.category + " " + train.number in repeated) originName(train.originCode)?.let { strings.trainFrom(label, it) } else null
            strings.lateTrain(named ?: label, train.late, train.samples)
        }
    }
}

@Composable
internal fun rememberReliabilityAggregateStrings(): ReliabilityAggregateStrings {
    val resources = LocalResources.current
    return remember(resources) {
        ReliabilityAggregateStrings(
            routeOnTime = { percent -> resources.getString(R.string.reliability_route_on_time, percent) },
            stationOnTime = { percent -> resources.getString(R.string.reliability_station_on_time, percent) },
            meanDelay = { minutes ->
                resources.getString(R.string.reliability_mean_delay, resources.getQuantityString(R.plurals.minutes_short, minutes, minutes))
            },
            trains = { count -> resources.getQuantityString(R.plurals.reliability_trains_count, count, count) },
            runsObserved = { runs, days ->
                resources.getQuantityString(R.plurals.reliability_runs_observed, runs, runs, resources.getQuantityString(R.plurals.reliability_days, days, days))
            },
            hours = { best, worst -> resources.getString(R.string.reliability_route_hours, best, worst) },
            hourBand = { start, end -> resources.getString(R.string.reliability_hour_band, start, end) },
            lateTrain = { label, late, total -> resources.getQuantityString(R.plurals.reliability_late_train, late, label, late, total) },
            trainFrom = { label, origin -> resources.getString(R.string.reliability_train_from, label, origin) },
            spoken = { text -> resources.getString(R.string.reliability_spoken, text) }
        )
    }
}

/**
 * The punctuality of a route under a list header (results, Partenze): its lines small and muted with the statistics
 * icon, then the attribution of the unofficial statistics, read by TalkBack as one sentence.
 */
@Composable
internal fun RoutePunctualityLines(text: ReliabilityAggregateText, windowEnd: LocalDate?, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val attribution = windowEnd?.let { networkReliabilityAttribution(it) }
    Row(
        modifier.clearAndSetSemantics { contentDescription = listOfNotNull(text.spoken, attribution).joinToString(". ") },
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Default.QueryStats, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            text.lines.forEach { line -> Text(line.text, style = MaterialTheme.typography.bodySmall, color = color) }
            attribution?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = color) }
        }
    }
}

/**
 * «Puntualità degli arrivi» of a station board: the arrivals of every observed train there, the trains most often
 * late, what "on time" means for them and the attribution. Plain text; nothing is said by colour alone.
 */
@Composable
internal fun StationPunctualityCard(
    station: StationPunctuality,
    windowEnd: LocalDate,
    originName: (String) -> String?,
    modifier: Modifier = Modifier
) {
    val strings = rememberReliabilityAggregateStrings()
    val summary = remember(station, strings) { ReliabilityAggregateLines.station(station, strings) }
    val late = remember(station, strings, originName) { ReliabilityAggregateLines.lateTrains(station.oftenLate, strings, originName) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 6.dp).testTag("station-punctuality"),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QueryStats, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.reliability_station_title),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                summary.text,
                modifier = Modifier.semantics { contentDescription = strings.spoken(summary.spokenText) },
                style = MaterialTheme.typography.bodyMedium
            )
            if (late.isNotEmpty()) {
                Text(stringResource(R.string.reliability_station_often_late), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                late.forEach { line -> Text(line, style = MaterialTheme.typography.bodyMedium) }
            }
            Text(stringResource(R.string.reliability_threshold_mixed_note), style = MaterialTheme.typography.bodySmall, color = muted)
            Text(networkReliabilityAttribution(windowEnd), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}
