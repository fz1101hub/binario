package it.binario.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Accessible
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.data.Station
import it.binario.app.data.core.StationId
import it.binario.app.data.journey.StaticDayDepartures
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.data.repository.BinarioDataGraph
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

// Marks taken from the static timetable of the packs: "Ultimo di oggi" and the wheelchair symbol.

/** "Ultimo di oggi": the last departure of the day in the static timetable (never moved by live delays). */
@Composable
internal fun LastOfDayChip(modifier: Modifier = Modifier) {
    Pill(
        text = stringResource(R.string.journey_last_of_day),
        modifier = modifier,
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        icon = Icons.Default.NightsStay
    )
}

/**
 * The wheelchair symbol of an accessible station, trip or journey, read as [description]. Shown only where the
 * timetable publishes wheelchair access (GTFS 1): an unknown value never shows it.
 */
@Composable
internal fun WheelchairMark(description: String, modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Filled.Accessible,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(18.dp)
    )
}

/**
 * Ids of the stations the packs publish as wheelchair accessible (catalogue ids and `gtfs:<pack>:<stop>`), read in the
 * background the first time [enabled] is true and kept for the process; empty until then.
 */
@Composable
internal fun rememberWheelchairAccessibleStations(enabled: Boolean = true): Set<String> {
    val context = LocalContext.current.applicationContext
    val state by produceState(emptySet<String>(), enabled) {
        if (!enabled) return@produceState
        value = try {
            BinarioDataGraph.offlineJourneyPlanner(context).wheelchairAccessibleStationIds()
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            emptySet()
        }
    }
    return state
}

/**
 * The static timetable's last departures of [date] from [origin] (towards [destination] when given) and the first of
 * the next day; null while it is read or without a station.
 */
@Composable
internal fun rememberStaticDayDepartures(origin: Station?, destination: Station?, date: LocalDate): State<StaticDayDepartures?> {
    val context = LocalContext.current.applicationContext
    return produceState<StaticDayDepartures?>(null, origin?.id, destination?.id, date) {
        val from = origin ?: return@produceState
        value = try {
            BinarioDataGraph.offlineJourneyPlanner(context).staticDayDepartures(
                StationId(from.id), from.endpoint(), destination?.let { StationId(it.id) }, destination?.endpoint(), date
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            null
        }
    }
}

private fun Station.endpoint(): JourneyEndpoint? =
    name.takeIf(String::isNotBlank)?.let { JourneyEndpoint(name = it, aliases = aliases, railwayCode = railwayCode) }
