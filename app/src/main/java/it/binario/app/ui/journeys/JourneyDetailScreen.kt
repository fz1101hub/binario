package it.binario.app.ui.journeys

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.binario.app.R
import it.binario.app.TabularFigures
import it.binario.app.data.Station
import it.binario.app.data.StopRouteChange
import it.binario.app.journeys.JourneyLegRow
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneySourceLabel
import it.binario.app.journeys.JourneyTransferRow
import it.binario.app.ui.common.BinarioCard
import it.binario.app.ui.common.HeaderOverlapBox
import it.binario.app.ui.common.InfoNoticeCard
import it.binario.app.ui.common.JourneyTimes
import it.binario.app.ui.common.Pill
import it.binario.app.ui.common.ScreenHeader
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.StationShortNames
import it.binario.app.ui.common.changesLabel
import it.binario.app.ui.detail.StopTimelineRow

// Pianifica: the detail of an option of the journeys-with-changes search.

/**
 * A journey with changes: every leg on the green timeline of the train detail (departure and arrival of the leg),
 * each change as a block "Cambio a <stazione> · Attesa di N minuti", the train of every leg, and the source with the
 * scheduled-only notice. The app does not follow or monitor multi-train journeys, so there is no "Segui questo
 * viaggio" here; a direct option keeps opening the train detail from the results.
 *
 * @param origin / destination the searched stations, for the short header ("Roma → Torino"); the legs' own names
 *   are used when they are not known.
 */
@Composable
internal fun JourneyDetailScreen(modifier: Modifier, row: JourneyOptionRow, origin: Station? = null, destination: Station? = null) {
    val locale = LocalConfiguration.current.locales[0]
    val headline = if (origin != null && destination != null) {
        StationShortNames.route(origin, destination)
    } else {
        StationShortNames.route(row.legs.firstOrNull()?.fromLabel.orEmpty(), row.legs.lastOrNull()?.toLabel.orEmpty())
    }
    val times = listOfNotNull(
        "${row.departureTime} → ${row.arrivalTime}",
        if (row.arrivalDayOffset > 0) stringResource(R.string.journey_arrival_next_day) else null,
        JourneyTimes.durationLabel(row.durationMinutes.toInt())
    ).joinToString(" · ")
    LazyColumn(modifier.fillMaxSize().testTag("journey-detail")) {
        item {
            ScreenHeader(
                title = headline.short,
                titleDescription = headline.full,
                overline = changesLabel(row.changes).uppercase(locale),
                subtitle = times,
                bottomPadding = 12.dp
            ) {
                if (row.scheduledOnly) Pill(stringResource(R.string.service_status_scheduled_badge))
            }
        }
        item {
            HeaderOverlapBox {
                BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                    Column(Modifier.padding(vertical = 12.dp).testTag("journey-timeline")) {
                        row.legs.forEachIndexed { index, leg ->
                            LegHeader(leg)
                            LegStop(time = leg.departureTime, station = leg.fromLabel, isFirst = true)
                            LegStop(time = leg.arrivalTime, station = leg.toLabel, isFirst = false)
                            row.transfers.firstOrNull { it.afterLegIndex == index }?.let { TransferBlock(it) }
                        }
                    }
                }
            }
        }
        if (row.scheduledOnly) {
            item { InfoNoticeCard(text = stringResource(R.string.journey_scheduled_only_notice), modifier = Modifier.padding(top = 8.dp)) }
        }
        item {
            InfoNoticeCard(
                title = stringResource(R.string.journey_source_title),
                text = journeySourceLabel(row),
                modifier = Modifier.testTag("journey-source")
            )
        }
        if (row.sourceLabel == JourneySourceLabel.OFFLINE_TIMETABLE) {
            item { InfoNoticeCard(text = stringResource(R.string.journey_coverage_notice)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The train of a leg (or bus, or walk), its operator and the scheduled duration of the leg. */
@Composable
private fun LegHeader(leg: JourneyLegRow) {
    val locale = LocalConfiguration.current.locales[0]
    val kind = JourneyLegKind.of(leg.mode)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(journeyLegLabel(leg).uppercase(locale), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            leg.operatorName?.takeIf { it.isNotBlank() && kind != JourneyLegKind.WALK }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            JourneyTimes.durationLabel(leg.durationMinutes.toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The departure ("Partenza") or arrival ("Arrivo") of a leg on the timeline of the train detail; times are scheduled. */
@Composable
private fun LegStop(time: String, station: String, isFirst: Boolean) {
    StopTimelineRow(
        station = station,
        expected = time,
        scheduled = time,
        delay = 0,
        platform = null,
        completed = false,
        operationalStatusVerified = false,
        routeChange = StopRouteChange.NONE,
        onSetAlarm = null,
        isFirst = isFirst,
        isLast = !isFirst
    )
}

/** "Cambio a Milano Centrale · Attesa di 15 minuti", with the arrival and the next departure. */
@Composable
private fun TransferBlock(transfer: JourneyTransferRow) {
    val wait = transfer.waitMinutes.coerceAtLeast(0).toInt()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("journey-transfer"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SyncAlt, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.journey_transfer_at, transfer.stationLabel) + " · " +
                        pluralStringResource(R.plurals.journey_transfer_wait, wait, wait),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${transfer.arrivalTime} → ${transfer.departureTime}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TabularFigures),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
