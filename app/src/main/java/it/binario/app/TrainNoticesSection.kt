package it.binario.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.binario.app.data.TrainService
import it.binario.app.data.notices.TrainNoticeContext

/**
 * "Avvisi per questo treno": per-train notices from the enabled public sources, loaded only when the detail is
 * shown. Hidden when no notice source is enabled, so an empty list is never mistaken for "no disruption".
 */
@Composable
fun TrainNoticesSection(service: TrainService, viewModel: RailNoticesViewModel) {
    if (!viewModel.hasTrainNoticeSources) return
    val context = remember(service.id, service.number, service.scheduledDepartureDateTime) { TrainNoticeContext.from(service) } ?: return
    LaunchedEffect(context.key) { viewModel.loadTrainNotices(context) }
    val locale = LocalConfiguration.current.locales[0]
    SourceSectionCard(title = stringResource(R.string.notices_train_title)) {
        when (val state = viewModel.trainNoticesFor(context.key)) {
            SourceSectionState.Idle, SourceSectionState.Loading -> SourceSectionNote(stringResource(R.string.notices_loading))
            is SourceSectionState.Ready -> {
                val value = state.value
                if (value.trainNotices.isEmpty()) {
                    SourceSectionNote(stringResource(R.string.notices_train_empty))
                }
                value.trainNotices.forEach { notice ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(notice.textFor(locale.language), style = MaterialTheme.typography.bodyMedium)
                        notice.publishedAt?.let { SourceSectionNote(stringResource(R.string.notices_published_at, noticeTimestamp(it, locale))) }
                    }
                }
                if (value.trainNotices.isNotEmpty()) SourceSectionNote(stringResource(R.string.notices_source_viaggiatreno))
                if (value.stale) SourceSectionNote(stringResource(R.string.notices_stale))
                if (value.unavailable) {
                    SourceSectionNote(stringResource(R.string.notices_unavailable))
                    TextButton(onClick = { viewModel.loadTrainNotices(context, force = true) }) {
                        Text(stringResource(R.string.notices_retry))
                    }
                }
            }
        }
    }
}
