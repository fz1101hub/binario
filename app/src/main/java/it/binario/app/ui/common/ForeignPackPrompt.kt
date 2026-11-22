package it.binario.app.ui.common

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.TrainViewModel
import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.ForeignPackChoice
import it.binario.app.data.gtfs.ForeignPackDownloadError
import it.binario.app.data.gtfs.ForeignPackDownloadState
import it.binario.app.data.gtfs.ForeignPackOffer
import it.binario.app.data.gtfs.ForeignPackSettingsRow
import java.time.LocalDate

/** The texts of one country of the full foreign packs: one string per country keeps each language's grammar right. */
internal data class ForeignPackCountryTexts(
    /** «Svizzera»: the title of its row in Settings. */
    @StringRes val name: Int,
    /** «Per questa tratta servono gli orari completi della Svizzera (%1$s). Scaricarli?» */
    @StringRes val prompt: Int,
    /** «Altri treni in Svizzera: scarica gli orari completi (%1$s)» */
    @StringRes val banner: Int,
    /** «Orari completi della Svizzera non scaricati · Scarica (%1$s)» */
    @StringRes val board: Int,
    /** «Gli orari della Svizzera di questa versione sono terminati il %1$s: aggiorna Binario.» */
    @StringRes val expired: Int
)

/** Which texts and buttons the foreign pack prompts use (design §3.3–3.4): plain functions, tested on the JVM. */
internal object ForeignPackPromptRules {
    enum class DialogActions {
        /** A pack of the dialog is downloading: «Annulla» stops it. */
        CANCEL_DOWNLOAD,
        /** One country: «Scarica» and «Non ora». */
        DOWNLOAD_ONE,
        /** Several countries (each line has its own «Scarica») or only ended timetables: «Non ora» closes. */
        CLOSE
    }

    /** The texts of [country]; null for a country this version has no texts for (it is then never offered). */
    fun country(country: String): ForeignPackCountryTexts? = when (country) {
        "CH" -> ForeignPackCountryTexts(
            R.string.settings_foreign_pack_country_ch, R.string.foreign_pack_prompt_ch, R.string.foreign_pack_partial_banner_ch,
            R.string.foreign_pack_board_ch, R.string.foreign_pack_expired_update_app_ch
        )
        "AT" -> ForeignPackCountryTexts(
            R.string.settings_foreign_pack_country_at, R.string.foreign_pack_prompt_at, R.string.foreign_pack_partial_banner_at,
            R.string.foreign_pack_board_at, R.string.foreign_pack_expired_update_app_at
        )
        "FR" -> ForeignPackCountryTexts(
            R.string.settings_foreign_pack_country_fr, R.string.foreign_pack_prompt_fr, R.string.foreign_pack_partial_banner_fr,
            R.string.foreign_pack_board_fr, R.string.foreign_pack_expired_update_app_fr
        )
        else -> null
    }

    @StringRes
    fun error(error: ForeignPackDownloadError): Int = when (error) {
        ForeignPackDownloadError.NETWORK -> R.string.foreign_pack_error_network
        ForeignPackDownloadError.REFUSED -> R.string.foreign_pack_error_refused
        ForeignPackDownloadError.INVALID -> R.string.foreign_pack_error_invalid
    }

    /** Only the countries with texts. */
    fun supported(choices: List<ForeignPackChoice>): List<ForeignPackChoice> = choices.filter { country(it.country) != null }

    /**
     * The offer of the route results, which merge the direct trains («Ricerca tratta») and the journeys with changes:
     * nothing until both have answered; the dialog only when neither found anything; a banner when one of them found
     * options and both ends lie outside the border zone of the same pack (owner decision Q3).
     */
    fun forResults(route: ForeignPackOffer?, routeAnswered: Boolean, journeys: ForeignPackOffer?, journeysAnswered: Boolean): ForeignPackOffer? {
        if (!routeAnswered || !journeysAnswered) return null
        if (route?.style == ForeignPackOffer.Style.DIALOG && journeys?.style == ForeignPackOffer.Style.DIALOG) {
            return ForeignPackOffer(ForeignPackOffer.Style.DIALOG, union(route.packs, journeys.packs), union(route.expired, journeys.expired))
        }
        val banners = listOfNotNull(route, journeys).filter { it.style == ForeignPackOffer.Style.BANNER }
        if (banners.isEmpty()) return null
        return ForeignPackOffer(ForeignPackOffer.Style.BANNER, banners.map { it.packs }.reduce(::union))
    }

    /**
     * [offer] as shown: only countries with texts, and a dialog without the packs answered «Non ora» in this process
     * ([declined]); null when nothing is left to show. A banner stays: it is one line and asks nothing.
     */
    fun visible(offer: ForeignPackOffer?, declined: Set<SourceId>): ForeignPackOffer? {
        offer ?: return null
        val skip = if (offer.style == ForeignPackOffer.Style.DIALOG) declined else emptySet()
        val packs = supported(offer.packs).filterNot { it.sourceId in skip }
        val expired = supported(offer.expired).filterNot { it.sourceId in skip }
        if (packs.isEmpty() && (offer.style == ForeignPackOffer.Style.BANNER || expired.isEmpty())) return null
        return offer.copy(packs = packs, expired = if (offer.style == ForeignPackOffer.Style.DIALOG) expired else emptyList())
    }

    fun actions(offer: ForeignPackOffer, download: ForeignPackDownloadState): DialogActions = when {
        download is ForeignPackDownloadState.Running && offer.packs.any { it.sourceId == download.sourceId } -> DialogActions.CANCEL_DOWNLOAD
        offer.packs.size == 1 -> DialogActions.DOWNLOAD_ONE
        else -> DialogActions.CLOSE
    }

    /** The download state to show in a dialog of [offer]: only its own packs' progress or error. */
    fun downloadShown(offer: ForeignPackOffer, download: ForeignPackDownloadState): ForeignPackDownloadState = when (download) {
        is ForeignPackDownloadState.Running -> download.takeIf { offer.packs.any { it.sourceId == download.sourceId } }
        is ForeignPackDownloadState.Failed -> download.takeIf { offer.packs.any { it.sourceId == download.sourceId } }
        ForeignPackDownloadState.Idle -> null
    } ?: ForeignPackDownloadState.Idle

    /** What a Settings row's status line takes, in the order of its placeholders. */
    enum class StatusArg { SIZE, DATE }

    data class StatusText(@StringRes val res: Int, val args: List<StatusArg> = emptyList())

    /**
     * The status line of a Settings row of [texts]' country: bundled whole (a static «incluso nell'app»), border zone
     * only, installed with its size and last day, an update to download again (owner decision Q4), or ended.
     */
    fun status(row: ForeignPackSettingsRow, texts: ForeignPackCountryTexts): StatusText = when (row.state) {
        ForeignPackSettingsRow.State.INCLUDED -> StatusText(R.string.settings_foreign_pack_status_included)
        ForeignPackSettingsRow.State.BORDER_ONLY -> StatusText(R.string.settings_foreign_pack_status_border)
        ForeignPackSettingsRow.State.INSTALLED -> StatusText(R.string.settings_foreign_pack_status_installed, listOf(StatusArg.SIZE, StatusArg.DATE))
        ForeignPackSettingsRow.State.UPDATE_AVAILABLE -> StatusText(R.string.settings_foreign_pack_status_update, listOf(StatusArg.SIZE))
        ForeignPackSettingsRow.State.EXPIRED -> StatusText(texts.expired, listOf(StatusArg.DATE))
    }

    private fun union(first: List<ForeignPackChoice>, second: List<ForeignPackChoice>): List<ForeignPackChoice> =
        (first + second).distinctBy(ForeignPackChoice::sourceId)
}

/** A pinned size as the phone writes it ("2,7 MB"). */
@Composable
internal fun foreignPackSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** A last day of service in the short date of the language. */
@Composable
internal fun foreignPackDate(date: LocalDate): String = date.format(LocalizedDates.shortDate(LocalConfiguration.current.locales[0]))

/** One tappable line (results banner, «Partenze»): the tap opens the download dialog, nothing starts by itself. */
@Composable
internal fun ForeignPackLine(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = ScreenMargin, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * The download dialog: the dialog after a search ([searchOffer]; «Non ora» declines it for the rest of the process) or
 * the one opened by a tap on a line ([TrainViewModel.foreignPackRequest]; «Non ora» only closes it).
 */
@Composable
internal fun ForeignPackPromptHost(viewModel: TrainViewModel, searchOffer: ForeignPackOffer?) {
    val download by viewModel.foreignPackDownload.collectAsState()
    val request = viewModel.foreignPackRequest?.let { ForeignPackPromptRules.visible(it, emptySet()) }
    val offer = request ?: searchOffer?.takeIf { it.style == ForeignPackOffer.Style.DIALOG } ?: return
    ForeignPackDialog(
        offer = offer,
        download = ForeignPackPromptRules.downloadShown(offer, download),
        onDownload = viewModel::downloadForeignPack,
        onCancelDownload = viewModel::cancelForeignPackDownload,
        onDismiss = { if (request != null) viewModel.closeForeignPackRequest() else viewModel.declineForeignPacks(offer) }
    )
}

@Composable
private fun ForeignPackDialog(
    offer: ForeignPackOffer,
    download: ForeignPackDownloadState,
    onDownload: (SourceId) -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val actions = ForeignPackPromptRules.actions(offer, download)
    val running = download is ForeignPackDownloadState.Running
    AlertDialog(
        // While a download runs only «Annulla» closes it.
        onDismissRequest = { if (!running) onDismiss() },
        modifier = Modifier.testTag("foreign-pack-dialog"),
        title = { Text(stringResource(R.string.settings_foreign_packs_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                offer.packs.forEach { choice ->
                    val texts = ForeignPackPromptRules.country(choice.country) ?: return@forEach
                    Text(stringResource(texts.prompt, foreignPackSize(choice.sizeBytes)), style = MaterialTheme.typography.bodyMedium)
                    if (actions == ForeignPackPromptRules.DialogActions.CLOSE && offer.packs.size > 1) {
                        TextButton(onClick = { onDownload(choice.sourceId) }, enabled = !running, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.foreign_pack_download))
                        }
                    }
                }
                offer.expired.forEach { choice ->
                    val texts = ForeignPackPromptRules.country(choice.country) ?: return@forEach
                    Text(stringResource(texts.expired, foreignPackDate(choice.lastServiceDate)), style = MaterialTheme.typography.bodyMedium)
                }
                if (offer.packs.isNotEmpty()) {
                    Text(
                        stringResource(R.string.foreign_pack_prompt_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ForeignPackDownloadStatus(download)
            }
        },
        confirmButton = {
            when (actions) {
                ForeignPackPromptRules.DialogActions.CANCEL_DOWNLOAD ->
                    TextButton(onClick = onCancelDownload, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
                ForeignPackPromptRules.DialogActions.DOWNLOAD_ONE ->
                    TextButton(onClick = { onDownload(offer.packs.single().sourceId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.foreign_pack_download))
                    }
                ForeignPackPromptRules.DialogActions.CLOSE ->
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.foreign_pack_not_now)) }
            }
        },
        dismissButton = if (actions == ForeignPackPromptRules.DialogActions.DOWNLOAD_ONE) {
            { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.foreign_pack_not_now)) } }
        } else null
    )
}

/** «Download in corso…» with its progress, or why the last download failed; nothing when idle. */
@Composable
internal fun ForeignPackDownloadStatus(download: ForeignPackDownloadState, modifier: Modifier = Modifier) {
    when (download) {
        is ForeignPackDownloadState.Running -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.foreign_pack_downloading), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
        }
        is ForeignPackDownloadState.Failed -> Text(
            stringResource(ForeignPackPromptRules.error(download.error)),
            modifier = modifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        ForeignPackDownloadState.Idle -> Unit
    }
}
