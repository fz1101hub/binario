package it.binario.app.ui.foryou

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Train
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.FavouriteTrainPunctuality
import it.binario.app.FavouriteTrainReliability
import it.binario.app.PunctualityHistoryStore
import it.binario.app.PunctualityObservation
import it.binario.app.R
import it.binario.app.ServiceMonitor
import it.binario.app.data.RailTime
import it.binario.app.data.reliability.NetworkReliabilityRepository
import it.binario.app.ui.common.BinarioCard
import it.binario.app.ui.common.NetworkReliabilityLine
import it.binario.app.ui.common.NetworkReliabilityLines
import it.binario.app.ui.common.NetworkReliabilityLinesText
import it.binario.app.ui.common.NetworkReliabilityStrings
import it.binario.app.ui.common.RowDivider
import it.binario.app.ui.common.RowIcon
import it.binario.app.ui.common.ScreenMargin
import it.binario.app.ui.common.SectionTitle
import it.binario.app.ui.common.networkReliabilityAttribution
import it.binario.app.ui.common.rememberNetworkReliabilityStrings
import it.binario.app.widget.CatalogueStationCodes
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The texts of «Affidabilità dei miei treni», as functions of their numbers: resolved from the app resources on screen
 * ([rememberMyTrainsReliabilityStrings]) and from the resource files in the JVM tests.
 */
class MyTrainsReliabilityStrings(
    /** The network figures, worded as the result and detail lines («In orario 27 volte su 30 · ritardo medio 3 min»). */
    val network: NetworkReliabilityStrings,
    /** «Nei tuoi viaggi: in orario 8 volte su 10» (plural on the first number): the user's own records. */
    val personal: (onTime: Int, runs: Int) -> String,
    /** «In peggioramento». */
    val worse: String,
    /** «In miglioramento». */
    val better: String,
    /** «Ancora nessun dato». */
    val noData: String
)

/** The lines of one row and the sentence TalkBack reads for them after the train's name. */
@Immutable
data class MyTrainReliabilityText(
    /** The network figures at the train's last stop; null without them. */
    val network: NetworkReliabilityLine?,
    /** The runs recorded on this phone, with their cancellations; null without any. */
    val personal: NetworkReliabilityLine?,
    /** «In peggioramento» / «In miglioramento»; null without a trend. */
    val trend: String?,
    /** «Ancora nessun dato» for a train with neither network nor personal figures. */
    val noData: String?,
    /** Every line, the network one said to be unofficial ([NetworkReliabilityStrings.spoken]). */
    val spoken: String
)

/** The texts of a [FavouriteTrainReliability] row. */
object MyTrainsReliabilityLines {
    fun text(row: FavouriteTrainReliability, strings: MyTrainsReliabilityStrings): MyTrainReliabilityText {
        val network = row.network?.let { NetworkReliabilityLines.train(it, strings.network) }
        val personal = row.personal?.let { runs ->
            NetworkReliabilityLine(
                listOfNotNull(strings.personal(runs.onTime, runs.runs), runs.cancelled.takeIf { it > 0 }?.let(strings.network.cancelled))
            )
        }
        val trend = row.trend?.let { if (it.better) strings.better else strings.worse }
        val noData = strings.noData.takeIf { network == null && personal == null }
        val spoken = listOfNotNull(network?.let { strings.network.spoken(it.spokenText) }, personal?.spokenText, trend, noData).joinToString(". ")
        return MyTrainReliabilityText(network, personal, trend, noData, spoken)
    }
}

@Composable
internal fun rememberMyTrainsReliabilityStrings(): MyTrainsReliabilityStrings {
    val network = rememberNetworkReliabilityStrings()
    val resources = LocalResources.current
    return remember(resources, network) {
        MyTrainsReliabilityStrings(
            network = network,
            personal = { onTime, runs -> resources.getQuantityString(R.plurals.my_trains_reliability_personal, onTime, onTime, runs) },
            worse = resources.getString(R.string.my_trains_reliability_worse),
            better = resources.getString(R.string.my_trains_reliability_better),
            noData = resources.getString(R.string.my_trains_reliability_no_data)
        )
    }
}

/** The rows of the section once read, and the last day of the network statistics (null without them). */
@Immutable
internal data class MyTrainsReliabilityState(val rows: List<FavouriteTrainReliability>, val statsEnd: LocalDate?)

/**
 * The rows at today's date, off the main thread: the history as stored (the daily recorder of the favourite trains
 * writes there too, so it may be newer than [fallback], the copy of the view model), the embedded network statistics
 * (none when the build leaves them out) and the codes of the saved origins from the offline station catalogue. Nothing is
 * requested over the network and nothing new is stored.
 */
private suspend fun readMyTrainsReliability(context: Context, monitors: List<ServiceMonitor>, fallback: List<PunctualityObservation>): MyTrainsReliabilityState =
    withContext(Dispatchers.IO) {
        val shown = monitors.take(FavouriteTrainPunctuality.MAX_ROWS)
        val history = runCatching { PunctualityHistoryStore(context).read() }.getOrDefault(fallback)
        val stats = NetworkReliabilityRepository.load(context)
        val codes = if (stats == null) emptyMap() else CatalogueStationCodes.originCodes(context, shown)
        val rows = FavouriteTrainPunctuality.rows(shown, history, LocalDate.now(RailTime.defaultZone), stats) { codes[it.number] }
        MyTrainsReliabilityState(rows, stats?.windowEnd)
    }

/**
 * «Affidabilità dei miei treni» in Per te: for each favourite train (at most [FavouriteTrainPunctuality.MAX_ROWS], in the
 * order of «I miei treni») its on-time share at its last stop in the unofficial network statistics, its runs recorded on
 * this phone in the last 30 days and the trend of the last four weeks; a tap opens the train. Hidden without favourite
 * trains and while the figures are read; the attribution of the statistics closes it whenever a network figure is shown.
 */
@Composable
internal fun MyTrainsReliabilitySection(
    monitors: List<ServiceMonitor>,
    history: List<PunctualityObservation>,
    onOpen: (ServiceMonitor) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Read again when the favourites change or the app records a run (the view model's history is only the key here).
    val state by produceState<MyTrainsReliabilityState?>(null, monitors, history) {
        value = readMyTrainsReliability(context, monitors, history)
    }
    val strings = rememberMyTrainsReliabilityStrings()
    val shown = state?.takeIf { monitors.isNotEmpty() && it.rows.isNotEmpty() } ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.testTag("for-you-my-trains-reliability")) {
        SectionTitle(stringResource(R.string.my_trains_reliability_title))
        BinarioCard(Modifier.padding(horizontal = ScreenMargin)) {
            Text(
                stringResource(R.string.my_trains_reliability_description),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )
            shown.rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                MyTrainReliabilityRow(row, remember(row, strings) { MyTrainsReliabilityLines.text(row, strings) }, onOpen = { onOpen(row.monitor) })
            }
        }
        val statsEnd = shown.statsEnd
        if (statsEnd != null && shown.rows.any { it.network != null }) {
            Text(
                networkReliabilityAttribution(statsEnd),
                modifier = Modifier.padding(horizontal = ScreenMargin + 4.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = muted
            )
        }
    }
}

/** One favourite train: its name and route as «I miei treni» shows them, then its figures; one TalkBack item, a button. */
@Composable
private fun MyTrainReliabilityRow(row: FavouriteTrainReliability, text: MyTrainReliabilityText, onOpen: () -> Unit) {
    val names = favouriteTrainNames(row.monitor)
    val openLabel = stringResource(R.string.favourite_train_open)
    val spoken = listOf(names.title, names.detail, text.spoken).filter(String::isNotBlank).joinToString(". ")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(label = openLabel) { onOpen(); true }
            }
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 16.dp)
            .testTag("my-trains-reliability-row"),
        verticalAlignment = Alignment.Top
    ) {
        RowIcon(Icons.Default.Train)
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(names.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (names.detail.isNotBlank()) Text(names.detail, style = MaterialTheme.typography.bodySmall, color = muted)
            text.network?.let { NetworkReliabilityLinesText(listOf(it), Modifier.padding(top = 4.dp)) }
            text.personal?.let { FigureLine(Icons.Default.PhoneAndroid, it.text) }
            text.trend?.let { FigureLine(if (row.trend?.better == true) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown, it) }
            text.noData?.let { Text(it, modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = muted) }
        }
    }
}

/** A figure in the style of the network line: a small muted icon, then the text; never said by colour alone. */
@Composable
private fun FigureLine(icon: ImageVector, text: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
