package it.binario.app.ui.journeys

import it.binario.app.ui.common.LocalizedDates
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.binario.app.BikeSharingNearbySection
import it.binario.app.R
import it.binario.app.TabularFigures
import it.binario.app.data.Station
import it.binario.app.data.StopRouteChange
import it.binario.app.journeys.JourneyContinuationRow
import it.binario.app.journeys.JourneyLegRow
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneySourceLabel
import it.binario.app.journeys.JourneyTransferRow
import it.binario.app.notifications.FollowJourneyNotificationControl
import it.binario.app.links.OfficialLinks
import it.binario.app.links.OperatorLinkKind
import it.binario.app.maps.OpenMapStationIndex
import it.binario.app.openExternalLink
import it.binario.app.timetables.TimetableShareFormatter
import it.binario.app.ui.common.BinarioCard
import it.binario.app.ui.common.HeaderOverlapBox
import it.binario.app.ui.common.InfoNoticeCard
import it.binario.app.ui.common.JourneyTimes
import it.binario.app.ui.common.Pill
import it.binario.app.ui.common.ScreenHeader
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.StationShortNames
import it.binario.app.ui.common.TonalButton
import it.binario.app.ui.common.WheelchairMark
import it.binario.app.ui.common.changesLabel
import it.binario.app.ui.common.co2Line
import it.binario.app.ui.common.ltrUnit
import it.binario.app.ui.common.rememberCo2
import it.binario.app.ui.detail.StopTimelineRow
import it.binario.app.ui.planner.journeyTimetableRow
import it.binario.app.ui.planner.startTextShare
import it.binario.app.ui.planner.timetableShareStrings

// Pianifica: the detail of an option of the journeys-with-changes search.

/**
 * A journey with changes: every leg on the green timeline of the train detail (departure and arrival of the leg),
 * each change as a block "Cambio a <stazione> · Attesa di N minuti", the train of every leg, and the source with the
 * scheduled-only notice. A journey with changes can be followed with the ongoing notification ("Segui con notifica"),
 * which also warns when a change is at risk; a direct option keeps opening the train detail from the results.
 *
 * @param origin / destination the searched stations, for the short header ("Roma → Torino"); the legs' own names
 *   are used when they are not known.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JourneyDetailScreen(
    modifier: Modifier,
    row: JourneyOptionRow,
    origin: Station? = null,
    destination: Station? = null,
    /** Catalogue positions for the CO2 estimate; null shows none. */
    stationIndex: OpenMapStationIndex? = null
) {
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val shareStrings = timetableShareStrings()
    val shareChooser = stringResource(R.string.share_solution_chooser)
    val solution = journeyTimetableRow(row, fetchedAt = 0L)
    val originName = origin?.name ?: row.legs.firstOrNull()?.fromLabel.orEmpty()
    val destinationName = destination?.name ?: row.legs.lastOrNull()?.toLabel.orEmpty()
    val dateLabel = row.departure.format(LocalizedDates.weekdayDayMonth(locale))
    // Trenitalia or Italo for the whole journey: their official site, opened without any prefilled search.
    val purchaseSite = OfficialLinks.purchaseSiteOf(row.legs.filter { JourneyLegKind.of(it.mode) != JourneyLegKind.WALK }.map { it.operatorName })
    val co2 = stationIndex?.let { rememberCo2(row, it) }
    val headline = if (origin != null && destination != null) {
        StationShortNames.route(origin, destination)
    } else {
        StationShortNames.route(row.legs.firstOrNull()?.fromLabel.orEmpty(), row.legs.lastOrNull()?.toLabel.orEmpty())
    }
    val times = listOfNotNull(
        ltrUnit("${row.departureTime} → ${row.arrivalTime}"),
        if (row.arrivalDayOffset > 0) stringResource(R.string.journey_arrival_next_day) else null,
        JourneyTimes.durationLabel(row.durationMinutes.toInt())
    ).joinToString(" · ")
    LazyColumn(modifier.fillMaxSize().testTag("journey-detail")) {
        item {
            ScreenHeader(
                title = ltrUnit(headline.short),
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
                            LegStop(time = leg.departureTime, station = leg.fromLabel, isFirst = true,
                                expected = leg.expectedDepartureTime, delay = leg.departureDelayMinutes, cancelled = leg.live?.cancelled == true)
                            leg.continuations.forEach { InSeatNote(it) }
                            LegStop(time = leg.arrivalTime, station = leg.toLabel, isFirst = false,
                                expected = leg.expectedArrivalTime, delay = leg.arrivalDelayMinutes, cancelled = leg.live?.cancelled == true)
                            row.transfers.firstOrNull { it.afterLegIndex == index }?.let { TransferBlock(it) }
                        }
                    }
                }
            }
        }
        // Shared bikes at the start and at the end of the journey, on request only.
        listOfNotNull(origin, destination).distinctBy { it.id }.forEach { station ->
            item(key = "bike-sharing-${station.id}") { BikeSharingNearbySection(station, Modifier.padding(top = 8.dp)) }
        }
        item {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = ScreenMargin, vertical = 8.dp).testTag("journey-actions"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TonalButton(
                    text = stringResource(R.string.action_share),
                    onClick = { startTextShare(context, TimetableShareFormatter.solution(solution, originName, destinationName, dateLabel, shareStrings), shareChooser) },
                    leadingIcon = Icons.Default.Share,
                    modifier = Modifier.testTag("journey-share")
                )
                purchaseSite?.let { site ->
                    TonalButton(
                        text = stringResource(R.string.official_site_open, site.name),
                        onClick = { site.url(OperatorLinkKind.BUY)?.let { openExternalLink(uriHandler, it) } },
                        trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                        modifier = Modifier.testTag("journey-official-site")
                    )
                }
            }
        }
        if (purchaseSite != null) {
            item { InfoNoticeCard(text = stringResource(R.string.official_site_note)) }
        }
        co2?.let { estimate ->
            item {
                InfoNoticeCard(
                    title = stringResource(R.string.co2_estimate_title),
                    text = co2Line(estimate) + "\n" + stringResource(R.string.co2_estimate_note),
                    modifier = Modifier.testTag("journey-co2")
                )
            }
        }
        if (row.scheduledOnly) {
            item { InfoNoticeCard(text = stringResource(R.string.journey_scheduled_only_notice), modifier = Modifier.padding(top = 8.dp)) }
        }
        if (row.changes > 0) {
            item { FollowJourneyNotificationControl(row) }
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
            if (leg.live?.cancelled == true) {
                Text(stringResource(R.string.run_status_cancelled), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            // The city network's own realtime feed and when it observed the run.
            val liveSource = leg.live?.sourceLabel
            val observed = leg.liveObservedTime
            if (liveSource != null && observed != null) {
                Text(stringResource(R.string.urban_realtime_source, liveSource, observed), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("journey-leg-realtime"))
            }
        }
        if (leg.wheelchairTrip) {
            WheelchairMark(stringResource(R.string.wheelchair_trip), Modifier.padding(end = 8.dp))
        }
        Text(
            JourneyTimes.durationLabel(leg.durationMinutes.toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "Brennero · Resta a bordo: il treno prosegue come REX 1826": no change, the same vehicle goes on. */
@Composable
private fun InSeatNote(continuation: JourneyContinuationRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 44.dp, end = 16.dp, top = 2.dp, bottom = 2.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("journey-in-seat"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.EventSeat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            continuation.stationLabel + " · " + stringResource(R.string.journey_in_seat_continuation, journeyContinuationLabel(continuation)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The departure ("Partenza") or arrival ("Arrivo") of a leg on the timeline of the train detail: the scheduled time,
 * and the expected one only when a live source published it for this stop (a cancelled run shows the stop as skipped).
 */
@Composable
private fun LegStop(time: String, station: String, isFirst: Boolean, expected: String? = null, delay: Int = 0, cancelled: Boolean = false) {
    StopTimelineRow(
        station = station,
        expected = expected ?: time,
        scheduled = time,
        delay = delay.coerceAtLeast(0),
        platform = null,
        completed = false,
        operationalStatusVerified = expected != null,
        routeChange = if (cancelled) StopRouteChange.SKIPPED else StopRouteChange.NONE,
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.journey_transfer_at, transfer.stationLabel) + " · " +
                        pluralStringResource(R.plurals.journey_transfer_wait, wait, wait),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    ltrUnit("${transfer.arrivalTime} → ${transfer.departureTime}"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TabularFigures),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TransferRiskChip(transfer.risk)
                if (transfer.expectedArrivalTime != null && transfer.expectedDepartureTime != null) {
                    Text(
                        stringResource(R.string.journey_transfer_live_times, transfer.expectedArrivalTime, transfer.expectedDepartureTime),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TabularFigures),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
