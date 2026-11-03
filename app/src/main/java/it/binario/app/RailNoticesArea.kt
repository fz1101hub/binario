package it.binario.app

import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import it.binario.app.data.notices.RailExternalLinks
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import it.binario.app.data.RailTime
import it.binario.app.data.live.StrikeNotice
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import it.binario.app.links.OfficialLinks

/**
 * "Avvisi e infomobilità": RFI and Trenitalia network notices, deduplicated by title, and Trenord notices grouped
 * by line. Nothing is downloaded until the user opens the area.
 */
@Composable
fun RailNoticesArea(viewModel: RailNoticesViewModel = viewModel()) {
    if (!viewModel.hasNetworkNoticeSources) return
    var open by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) viewModel.loadGeneralNotices() }
    val uriHandler = LocalUriHandler.current
    SourceSectionCard(title = stringResource(R.string.notices_area_title)) {
        SourceSectionNote(stringResource(R.string.notices_area_description))
        // The rail strikes of the MIT feed, once the area is open; the official calendar stays a link either way.
        if (open && viewModel.upcomingStrikes.isNotEmpty()) UpcomingStrikes(viewModel.upcomingStrikes)
        TextButton(onClick = { openExternalLink(uriHandler, RailExternalLinks.MIT_STRIKE_CALENDAR) }) {
            Text(stringResource(R.string.notices_strike_calendar))
        }
        // The FS Italiane news site with its radio traffic bulletins: a link, no audio is played or hosted in the app.
        TextButton(onClick = { openExternalLink(uriHandler, OfficialLinks.FS_NEWS) }) {
            Text(stringResource(R.string.notices_fs_news_bulletin))
        }
        if (!open) {
            Button(onClick = { open = true }) { Text(stringResource(R.string.notices_area_show)) }
        } else when (val state = viewModel.generalNotices) {
            SourceSectionState.Idle, SourceSectionState.Loading -> SourceSectionNote(stringResource(R.string.notices_loading))
            is SourceSectionState.Ready -> {
                val value = state.value
                Text(stringResource(R.string.notices_network_heading), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (value.network.isEmpty()) SourceSectionNote(stringResource(R.string.notices_area_empty))
                val visible = if (showAll) value.network else value.network.take(COLLAPSED_COUNT)
                visible.forEachIndexed { index, notice ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RailNoticeItem(notice)
                }
                if (!showAll && value.network.size > COLLAPSED_COUNT) {
                    TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.notices_area_show_all)) }
                }
                if (value.trenordGroups.isNotEmpty()) {
                    Text(stringResource(R.string.notices_trenord_heading), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    value.trenordGroups.forEach { group ->
                        noticeLinesLabel(group.lines, group.allLines)?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        group.notices.forEach { RailNoticeItem(it, showLines = false) }
                    }
                }
                if (value.stale) SourceSectionNote(stringResource(R.string.notices_stale))
                value.unavailable.forEach { publisher ->
                    SourceSectionNote(stringResource(R.string.notices_unavailable_publisher, publisher.brand()))
                }
                OutlinedButton(onClick = { viewModel.loadGeneralNotices(force = true) }) { Text(stringResource(R.string.notices_area_refresh)) }
            }
        }
    }
}

private const val COLLAPSED_COUNT = 6

/** "Scioperi in programma": date (or range), title and the announcement's page, from the MIT calendar. */
@Composable
private fun UpcomingStrikes(strikes: List<StrikeNotice>) {
    val locale = LocalConfiguration.current.locales[0]
    val uriHandler = LocalUriHandler.current
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    Column(Modifier.fillMaxWidth().testTag("notices-upcoming-strikes"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.strikes_upcoming_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        strikes.forEachIndexed { index, strike ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                StrikeBadge()
                val start = strike.startDate
                val end = strike.endDate
                val published = strike.publishedAt
                when {
                    start != null && end != null && end != start ->
                        Text(stringResource(R.string.strikes_date_range, start.format(dates), end.format(dates)), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    start != null -> Text(start.format(dates), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    published != null -> SourceSectionNote(stringResource(R.string.strikes_published_on, published.atZone(RailTime.defaultZone).toLocalDate().format(dates)))
                }
                Text(strike.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                strike.link?.let { link ->
                    TextButton(onClick = { openExternalLink(uriHandler, link) }) { Text(stringResource(R.string.notices_open_source)) }
                }
            }
        }
        SourceSectionNote(stringResource(R.string.strikes_upcoming_source))
    }
}
