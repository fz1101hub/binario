package it.binario.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.TrainViewModel
import it.binario.app.data.gtfs.ForeignPackDownloadState
import it.binario.app.data.gtfs.ForeignPackSettingsRow
import it.binario.app.ui.common.ForeignPackDownloadStatus
import it.binario.app.ui.common.ForeignPackPromptRules
import it.binario.app.ui.common.foreignPackDate
import it.binario.app.ui.common.foreignPackSize

/**
 * Impostazioni › «Orari dei paesi confinanti»: Switzerland, Austria and France with the state of their timetable on the
 * phone, and «Scarica» / «Elimina» where they apply. Deleting takes effect at once.
 */
@Composable
internal fun ForeignPacksSettings(viewModel: TrainViewModel) {
    LaunchedEffect(Unit) { viewModel.loadForeignPackRows() }
    val download by viewModel.foreignPackDownload.collectAsState()
    val rows = viewModel.foreignPackRows
    if (rows.isEmpty()) return
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_foreign_packs_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        rows.forEach { row -> ForeignPackSettingsRowView(viewModel, row, download) }
        if (rows.any { it.sourceId != null }) {
            Text(
                stringResource(R.string.foreign_pack_prompt_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ForeignPackSettingsRowView(viewModel: TrainViewModel, row: ForeignPackSettingsRow, download: ForeignPackDownloadState) {
    val texts = ForeignPackPromptRules.country(row.country) ?: return
    val status = ForeignPackPromptRules.status(row, texts)
    val size = foreignPackSize(row.sizeBytes)
    val date = row.lastServiceDate?.let { foreignPackDate(it) }.orEmpty()
    val args = status.args.map { if (it == ForeignPackPromptRules.StatusArg.SIZE) size else date }
    val running = download is ForeignPackDownloadState.Running
    val own = when (download) {
        is ForeignPackDownloadState.Running -> download.sourceId == row.sourceId
        is ForeignPackDownloadState.Failed -> download.sourceId == row.sourceId
        ForeignPackDownloadState.Idle -> false
    }
    Column(Modifier.testTag("settings-foreign-pack-${row.country}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(texts.name), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            stringResource(status.res, *args.toTypedArray()),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (own) ForeignPackDownloadStatus(download)
        val sourceId = row.sourceId ?: return@Column
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (own && running) {
                TextButton(onClick = viewModel::cancelForeignPackDownload, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            } else if (row.canDownload) {
                TextButton(onClick = { viewModel.downloadForeignPack(sourceId) }, enabled = !running, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.foreign_pack_download_size, size))
                }
            }
            if (row.canDelete) {
                TextButton(onClick = { viewModel.deleteForeignPack(sourceId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.foreign_pack_delete))
                }
            }
        }
    }
}
