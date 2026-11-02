package it.binario.app.ui.journeys

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.binario.app.BinarioTheme
import it.binario.app.R
import it.binario.app.TabularFigures
import it.binario.app.data.core.LegMode
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
import it.binario.app.ui.common.journeyLegLabel
import it.binario.app.ui.common.journeySourceLabel

// Pianifica: the detail of an option of the journeys-with-changes search.

/**
 * A journey with changes: the legs on the green timeline of the train detail, each change as a block "Cambio a
 * <stazione>" with the wait, the train of every leg, and the source with the scheduled-only notice. The app does
 * not follow or monitor multi-train journeys, so there is no "Segui questo viaggio" here.
 */
@Composable
internal fun JourneyDetailScreen(modifier: Modifier, row: JourneyOptionRow) {
    val locale = LocalConfiguration.current.locales[0]
    val from = row.legs.firstOrNull()?.fromLabel.orEmpty()
    val to = row.legs.lastOrNull()?.toLabel.orEmpty()
    val headline = StationShortNames.route(from, to)
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
                if (row.scheduledOnly) {
                    Pill(stringResource(R.string.service_status_scheduled_badge))
                }
            }
        }
        item {
            HeaderOverlapBox {
                BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
                    Column(Modifier.padding(vertical = 12.dp).testTag("journey-timeline")) {
                        row.legs.forEachIndexed { index, leg ->
                            LegHeader(leg)
                            JourneyStopRow(leg.departureTime, leg.fromLabel, stringResource(R.string.stop_role_departure), isFirst = true, isLast = false)
                            JourneyStopRow(leg.arrivalTime, leg.toLabel, stringResource(R.string.stop_role_arrival), isFirst = false, isLast = true)
                            row.transfers.getOrNull(index)?.let { TransferBlock(it) }
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

/** The train of a leg (or the replacement bus), its operator and the scheduled duration of the leg. */
@Composable
private fun LegHeader(leg: JourneyLegRow) {
    val locale = LocalConfiguration.current.locales[0]
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (leg.mode == LegMode.REPLACEMENT_BUS) Icons.Default.DirectionsBus else Icons.Default.Train,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(journeyLegLabel(leg).uppercase(locale), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            leg.operatorName?.takeIf(String::isNotBlank)?.let {
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

/** A departure or arrival of a leg on the green line, as in the train detail. */
@Composable
private fun JourneyStopRow(time: String, station: String, role: String, isFirst: Boolean, isLast: Boolean) {
    val lineColor = BinarioTheme.colors.timeline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = TIMELINE_START.toPx() + TIMELINE_WIDTH.toPx() / 2
                val dotCenter = TIMELINE_TOP.toPx() + TIMELINE_DOT_SLOT.toPx() / 2
                val stroke = 3.dp.toPx()
                if (!isFirst) drawLine(lineColor, Offset(x, 0f), Offset(x, dotCenter), stroke)
                if (!isLast) drawLine(lineColor, Offset(x, dotCenter), Offset(x, size.height), stroke)
            }
            .padding(start = TIMELINE_START, end = 16.dp, top = TIMELINE_TOP, bottom = TIMELINE_TOP)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.width(TIMELINE_WIDTH).height(TIMELINE_DOT_SLOT), contentAlignment = Alignment.Center) {
            Surface(Modifier.size(16.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(3.dp, lineColor)) {}
        }
        Spacer(Modifier.width(10.dp))
        Text(
            time,
            modifier = Modifier.widthIn(min = 56.dp).padding(end = 12.dp),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TabularFigures),
            fontWeight = FontWeight.Bold
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(station, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Cambio a Milano Centrale · Attesa di 10 minuti", with the arrival and the next departure. */
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

private val TIMELINE_START = 16.dp
private val TIMELINE_WIDTH = 24.dp
private val TIMELINE_TOP = 10.dp
private val TIMELINE_DOT_SLOT = 24.dp
