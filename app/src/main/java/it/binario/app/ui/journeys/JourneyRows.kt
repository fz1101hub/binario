package it.binario.app.ui.journeys

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.binario.app.R
import it.binario.app.TabularFigures
import it.binario.app.data.core.LegMode
import it.binario.app.journeys.JourneyLegRow
import it.binario.app.journeys.JourneyLegVehicle
import it.binario.app.journeys.JourneyOptionRow
import it.binario.app.journeys.JourneySourceLabel
import it.binario.app.ui.common.ChangesBadge
import it.binario.app.ui.common.JourneyTimes
import it.binario.app.ui.common.changesLabel
import it.binario.app.ui.common.journeyDurationSpoken
import it.binario.app.ui.common.localizedLabel

/**
 * How a leg is drawn and named. Modes the app does not know yet fall back to [OTHER] (line and number as published),
 * and a scheduled bus mode of the timetable packs (`BUS`, `*_BUS`) is drawn as a bus without changing this file.
 */
internal enum class JourneyLegKind {
    TRAIN, BUS, REPLACEMENT_BUS, WALK, OTHER;

    companion object {
        fun of(mode: LegMode): JourneyLegKind = when (mode) {
            LegMode.TRAIN -> TRAIN
            LegMode.REPLACEMENT_BUS -> REPLACEMENT_BUS
            LegMode.WALK -> WALK
            else -> ofModeName(mode.name)
        }

        /** A mode known only by name: a bus when the name says so, otherwise a generic public transport leg. */
        internal fun ofModeName(name: String): JourneyLegKind = if (name == "BUS" || name.endsWith("_BUS")) BUS else OTHER
    }
}

internal val JourneyLegKind.icon: ImageVector
    get() = when (this) {
        JourneyLegKind.TRAIN -> Icons.Default.Train
        JourneyLegKind.BUS, JourneyLegKind.REPLACEMENT_BUS -> Icons.Default.DirectionsBus
        JourneyLegKind.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
        JourneyLegKind.OTHER -> Icons.Default.DirectionsTransit
    }

/**
 * "REGIONALE 2153", "RE 24617", "Bus sostitutivo 123", "Autobus B12 4417", "A piedi": the published line or category
 * and the number of a leg; never empty.
 */
@Composable
internal fun journeyLegLabel(leg: JourneyLegRow): String {
    val line = leg.lineLabel?.trim()?.takeIf(String::isNotEmpty)
    val number = leg.number?.trim()?.takeIf(String::isNotEmpty)
    return when (JourneyLegKind.of(leg.mode)) {
        JourneyLegKind.TRAIN -> listOfNotNull(line ?: leg.trainCategory.localizedLabel(), number).joinToString(" ")
        JourneyLegKind.REPLACEMENT_BUS -> listOfNotNull(stringResource(R.string.journey_leg_replacement_bus), line, number).joinToString(" ")
        JourneyLegKind.BUS -> {
            val label = stringResource(
                when (leg.vehicle) {
                    JourneyLegVehicle.BUS_TRENITALIA -> R.string.journey_leg_bus_trenitalia
                    JourneyLegVehicle.FRECCIALINK -> R.string.journey_leg_freccialink
                    JourneyLegVehicle.ITALOBUS -> R.string.journey_leg_italobus
                    else -> R.string.journey_leg_bus
                }
            )
            // The published line often repeats the brand ("ItaloBus ItaloBus 1060"): keep it only when it adds something.
            val extraLine = line?.takeUnless { it.replace(" ", "").equals(label.replace(" ", ""), ignoreCase = true) }
            listOfNotNull(label, extraLine, number).joinToString(" ")
        }
        JourneyLegKind.WALK -> stringResource(R.string.journey_leg_walk)
        JourneyLegKind.OTHER -> listOfNotNull(line, number).joinToString(" ").ifEmpty { leg.trainCategory.localizedLabel() }
    }
}

/** "REGIONALE 2153 › RE 24617": every leg on board, in order (walks between stations are left out). */
@Composable
internal fun journeyTrainsLabel(row: JourneyOptionRow): String = row.legs
    .filter { JourneyLegKind.of(it.mode) != JourneyLegKind.WALK }
    .map { journeyLegLabel(it) }
    .joinToString(" › ")

/** The localized source of a journey option: the offline timetable label, or the provider's own name. */
@Composable
internal fun journeySourceLabel(row: JourneyOptionRow): String =
    if (row.sourceLabel == JourneySourceLabel.OFFLINE_TIMETABLE) stringResource(R.string.journey_source_offline) else row.sourceName

/**
 * An option of the journeys-with-changes search, in the same card as the direct trains: times, scheduled duration,
 * the trains of each leg, the source that computed it, the changes badge and "Binario —" (the timetable gives no
 * platforms). Read as `journey_option_accessibility`.
 */
@Composable
internal fun JourneyResultCard(
    row: JourneyOptionRow,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    val locale = LocalConfiguration.current.locales[0]
    val from = row.legs.firstOrNull()?.fromLabel.orEmpty()
    val to = row.legs.lastOrNull()?.toLabel.orEmpty()
    val source = journeySourceLabel(row)
    val trains = journeyTrainsLabel(row)
    val nextDay = if (row.arrivalDayOffset > 0) stringResource(R.string.journey_arrival_next_day) else null
    val description = listOfNotNull(
        stringResource(
            R.string.journey_option_accessibility,
            row.departureTime, from, row.arrivalTime, to, journeyDurationSpoken(row.durationMinutes), changesLabel(row.changes)
        ),
        nextDay,
        trains,
        source,
        if (selected) stringResource(R.string.accessibility_row_selected) else null
    ).joinToString(". ")
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onOpen(); true }
            },
        onClick = onOpen,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 1.dp,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${row.departureTime} → ${row.arrivalTime}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = TabularFigures)
                )
                Text(
                    JourneyTimes.durationLabel(row.durationMinutes.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            nextDay?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                trains.uppercase(locale),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp
            )
            Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ChangesBadge(row.changes, Modifier.weight(1f))
                Text(
                    stringResource(R.string.platform_label, "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
