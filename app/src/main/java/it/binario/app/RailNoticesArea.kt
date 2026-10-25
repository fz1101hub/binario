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
        // No strike feed is machine-readable from here: the official calendar opens in the browser on tap only.
        TextButton(onClick = { openExternalLink(uriHandler, RailExternalLinks.MIT_STRIKE_CALENDAR) }) {
            Text(stringResource(R.string.notices_strike_calendar))
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
