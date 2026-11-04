package it.binario.app.ui.departures

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.data.RailTime
import it.binario.app.data.Station
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.DirectConnections
import it.binario.app.data.gtfs.DirectDestination
import it.binario.app.data.gtfs.directConnections
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.data.repository.BinarioDataGraph
import it.binario.app.ui.common.LocalizedDates
import it.binario.app.ui.common.compactDurationLabel
import it.binario.app.ui.common.journeyDurationSpoken
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * «Collegamenti diretti»: the destinations [station] reaches without changes on a day of the coming week, from the
 * embedded timetables (trains first, then metro, tram and funicular lines from the station or a stop next to it), with
 * the runs of the day, the first and last departure and the fastest run. A destination opens the route search to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DirectConnectionsSheet(
    station: Station,
    onSearch: (destination: DirectDestination, date: LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val today = remember { LocalDate.now(RailTime.defaultZone) }
    var dayOffset by rememberSaveable(station.id) { mutableStateOf(0) }
    var filter by rememberSaveable(station.id) { mutableStateOf("") }
    val date = today.plusDays(dayOffset.toLong())
    val connections by rememberDirectConnections(station, date)
    val locale = LocalConfiguration.current.locales[0]
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("direct-connections")) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.direct_connections_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(stringResource(R.string.direct_connections_from, station.name), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.direct_connections_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((0 until DAYS_SHOWN).toList()) { offset ->
                            val day = today.plusDays(offset.toLong())
                            FilterChip(
                                selected = offset == dayOffset,
                                onClick = { dayOffset = offset },
                                label = {
                                    Text(
                                        if (offset == 0) stringResource(R.string.planner_date_today, day.format(LocalizedDates.dayMonth(locale)))
                                        else day.format(LocalizedDates.weekdayDayMonth(locale))
                                    )
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it.take(60) },
                        label = { Text(stringResource(R.string.direct_connections_filter)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            val value = connections
            when {
                value == null || value.date != date -> item(key = "loading") {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.direct_connections_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                value.isEmpty -> item(key = "empty") { SheetMessage(stringResource(R.string.direct_connections_empty)) }
                else -> {
                    val rail = DirectConnectionFilter.matching(value.rail, filter)
                    val urban = DirectConnectionFilter.matching(value.urban, filter)
                    if (rail.isEmpty() && urban.isEmpty()) {
                        item(key = "no-match") { SheetMessage(stringResource(R.string.direct_connections_no_match, filter.trim())) }
                    }
                    if (rail.isNotEmpty()) {
                        item(key = "rail-header") { SectionHeader(stringResource(R.string.direct_connections_trains)) }
                        items(rail, key = { "rail-${it.stationId}" }) { destination ->
                            DirectDestinationRow(station, destination) { onSearch(destination, date) }
                        }
                    }
                    if (urban.isNotEmpty()) {
                        item(key = "urban-header") { SectionHeader(stringResource(R.string.direct_connections_urban)) }
                        items(urban, key = { "urban-${it.stationId}" }) { destination ->
                            DirectDestinationRow(station, destination) { onSearch(destination, date) }
                        }
                    }
                }
            }
            item(key = "end") {
                TextButton(onClick = onDismiss, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.planned_journey_close))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SheetMessage(text: String) {
    Text(text, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DirectDestinationRow(origin: Station, destination: DirectDestination, onClick: () -> Unit) {
    val runs = pluralStringResource(
        if (destination.urban) R.plurals.direct_connections_runs else R.plurals.direct_connections_train_count,
        destination.runs, destination.runs
    )
    val firstLast = stringResource(
        R.string.direct_connections_first_last,
        DirectConnectionFilter.clock(destination.firstDepartureMinute),
        DirectConnectionFilter.clock(destination.lastDepartureMinute)
    )
    val fastest = stringResource(R.string.direct_connections_fastest, compactDurationLabel(destination.fastestMinutes))
    val fastestSpoken = stringResource(R.string.direct_connections_fastest, journeyDurationSpoken(destination.fastestMinutes.toLong()))
    val boarding = destination.boardingStop?.let { stringResource(R.string.direct_connections_from_stop, it) }
    val lines = destination.lines.joinToString(" ").takeIf(String::isNotEmpty)
    val action = stringResource(R.string.planner_airport_search, origin.name, destination.name)
    val description = listOfNotNull(destination.name, lines, runs, firstLast, fastestSpoken, boarding).joinToString(", ")
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = action) { onClick(); true }
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(destination.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            listOfNotNull(lines, runs, firstLast, fastest).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        boarding?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** The direct connections of [station] on [date], read in the background; null while they are read. */
@Composable
private fun rememberDirectConnections(station: Station, date: LocalDate): State<DirectConnections?> {
    val context = LocalContext.current.applicationContext
    return produceState<DirectConnections?>(null, station.id, date) {
        val key = "${station.id}|$date"
        DirectConnectionsMemory.get(key)?.let { value = it; return@produceState }
        value = try {
            BinarioDataGraph.offlineJourneyPlanner(context).directConnections(
                StationId(station.id),
                station.name.takeIf(String::isNotBlank)?.let { JourneyEndpoint(name = it, aliases = station.aliases, railwayCode = station.railwayCode) },
                date
            ).also { DirectConnectionsMemory.put(key, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            DirectConnections(date, emptyList(), emptyList())
        }
    }
}

/** The route search a destination of «Collegamenti diretti» opens. */
internal object DirectConnectionSearch {
    /**
     * Today: no date and no time, so the results follow the current time. Another day: that day and the first
     * departure to the destination (a first departure after midnight, 24:10, is 00:10 of the next calendar day).
     */
    fun departure(date: LocalDate, firstDepartureMinute: Int, today: LocalDate): Pair<LocalDate?, LocalTime?> {
        if (date == today) return null to null
        val ofDay = Math.floorMod(firstDepartureMinute, MINUTES_PER_DAY)
        return date.plusDays(Math.floorDiv(firstDepartureMinute, MINUTES_PER_DAY).toLong()) to LocalTime.of(ofDay / 60, ofDay % 60)
    }
}

/** The pure parts of the sheet: the name filter and the clock times. */
internal object DirectConnectionFilter {
    /** [destinations] whose name contains every word of [query], ignoring case and accents; all of them for a blank query. */
    fun matching(destinations: List<DirectDestination>, query: String): List<DirectDestination> {
        val words = normalize(query).split(' ').filter(String::isNotEmpty)
        if (words.isEmpty()) return destinations
        return destinations.filter { destination ->
            val name = normalize(destination.name)
            words.all { it in name }
        }
    }

    /** "06:05"; a minute of the service day after midnight (24:35) is the clock time it shows (00:35). */
    fun clock(minute: Int): String {
        val ofDay = Math.floorMod(minute, MINUTES_PER_DAY)
        return String.format(Locale.ROOT, "%02d:%02d", ofDay / 60, ofDay % 60)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

/** The last few answers, so a day or a station chosen again shows at once (process memory only, a handful of entries). */
private object DirectConnectionsMemory {
    private const val CAPACITY = 6
    private val entries = object : LinkedHashMap<String, DirectConnections>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DirectConnections>?): Boolean = size > CAPACITY
    }

    fun get(key: String): DirectConnections? = synchronized(entries) { entries[key] }
    fun put(key: String, value: DirectConnections) = synchronized(entries) { entries[key] = value }
}

private const val DAYS_SHOWN = 7
private const val MINUTES_PER_DAY = 24 * 60
