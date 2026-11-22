package it.binario.app.ui.common

import it.binario.app.R
import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.ForeignPackChoice
import it.binario.app.data.gtfs.ForeignPackDownloadError
import it.binario.app.data.gtfs.ForeignPackDownloadState
import it.binario.app.data.gtfs.ForeignPackOffer
import it.binario.app.data.gtfs.ForeignPackOffer.Style.BANNER
import it.binario.app.data.gtfs.ForeignPackOffer.Style.DIALOG
import it.binario.app.data.gtfs.ForeignPackSettingsRow
import it.binario.app.data.gtfs.ForeignPackSettingsRow.State
import it.binario.app.ui.common.ForeignPackPromptRules.DialogActions
import it.binario.app.ui.common.ForeignPackPromptRules.StatusArg
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which prompt the results, the board and Settings show (design §3.3–3.4, owner decisions Q3 and Q4). */
class ForeignPackPromptRulesTest {
    private val swiss = ForeignPackChoice(SourceId("ch-otd-gtfs"), "CH", 2_659_987L, LocalDate.of(2026, 12, 12))
    private val french = ForeignPackChoice(SourceId("sncf-gtfs"), "FR", 5_000_000L, LocalDate.of(2027, 4, 30))
    private val austrian = ForeignPackChoice(SourceId("oebb-gtfs"), "AT", 1_000_000L, LocalDate.of(2026, 12, 12))
    private val unknown = ForeignPackChoice(SourceId("sbb-de"), "DE", 1L, LocalDate.of(2026, 12, 12))

    private fun dialog(vararg packs: ForeignPackChoice, expired: List<ForeignPackChoice> = emptyList()) = ForeignPackOffer(DIALOG, packs.toList(), expired)
    private fun banner(vararg packs: ForeignPackChoice) = ForeignPackOffer(BANNER, packs.toList())

    @Test
    fun `the results wait for both searches and ask only when neither found anything`() {
        // Nothing until the direct trains («Ricerca tratta») and the journeys have both answered.
        assertNull(ForeignPackPromptRules.forResults(dialog(swiss), routeAnswered = false, dialog(swiss), journeysAnswered = true))
        assertNull(ForeignPackPromptRules.forResults(dialog(swiss), routeAnswered = true, null, journeysAnswered = false))

        assertEquals(dialog(swiss), ForeignPackPromptRules.forResults(dialog(swiss), true, dialog(swiss), true))
        // One of the two found trains: no dialog.
        assertNull(ForeignPackPromptRules.forResults(dialog(swiss), true, null, true))
        assertNull(ForeignPackPromptRules.forResults(null, true, dialog(swiss), true))
        // Both ends outside the zone, options found by one: the banner.
        assertEquals(banner(swiss), ForeignPackPromptRules.forResults(dialog(swiss), true, banner(swiss), true))
        assertEquals(banner(swiss), ForeignPackPromptRules.forResults(banner(swiss), true, banner(swiss), true))
        assertNull(ForeignPackPromptRules.forResults(null, true, null, true))
        // Several countries: one row each, once.
        assertEquals(
            dialog(swiss, french),
            ForeignPackPromptRules.forResults(dialog(swiss, french), true, dialog(french, swiss), true)
        )
    }

    @Test
    fun `a declined dialog is not shown again, a banner stays, unknown countries never show`() {
        val declined = setOf(swiss.sourceId)

        assertNull(ForeignPackPromptRules.visible(dialog(swiss), declined))
        assertEquals(dialog(french), ForeignPackPromptRules.visible(dialog(swiss, french), declined))
        assertNull(ForeignPackPromptRules.visible(dialog(expired = listOf(swiss)), declined))
        assertEquals(banner(swiss), ForeignPackPromptRules.visible(banner(swiss), declined))
        assertNull(ForeignPackPromptRules.visible(dialog(unknown), emptySet()))
        assertNull(ForeignPackPromptRules.visible(banner(unknown), emptySet()))
        assertEquals(dialog(swiss), ForeignPackPromptRules.visible(dialog(swiss, unknown), emptySet()))
        assertNull(ForeignPackPromptRules.visible(null, emptySet()))
        // An ended timetable alone still says so (no download offered).
        assertEquals(dialog(expired = listOf(swiss)), ForeignPackPromptRules.visible(dialog(expired = listOf(swiss)), emptySet()))
    }

    @Test
    fun `the dialog buttons follow the countries and the download`() {
        val running = ForeignPackDownloadState.Running(swiss.sourceId, 10L, 100L)
        assertEquals(DialogActions.DOWNLOAD_ONE, ForeignPackPromptRules.actions(dialog(swiss), ForeignPackDownloadState.Idle))
        assertEquals(DialogActions.CLOSE, ForeignPackPromptRules.actions(dialog(swiss, french), ForeignPackDownloadState.Idle))
        assertEquals(DialogActions.CLOSE, ForeignPackPromptRules.actions(dialog(expired = listOf(swiss)), ForeignPackDownloadState.Idle))
        assertEquals(DialogActions.CANCEL_DOWNLOAD, ForeignPackPromptRules.actions(dialog(swiss), running))
        assertEquals(DialogActions.CANCEL_DOWNLOAD, ForeignPackPromptRules.actions(dialog(swiss, french), running))
        // Another country downloading (from Settings) is not this dialog's download.
        assertEquals(DialogActions.DOWNLOAD_ONE, ForeignPackPromptRules.actions(dialog(french), running))
        assertEquals(ForeignPackDownloadState.Idle, ForeignPackPromptRules.downloadShown(dialog(french), running))
        assertEquals(running, ForeignPackPromptRules.downloadShown(dialog(swiss), running))
        val failed = ForeignPackDownloadState.Failed(swiss.sourceId, ForeignPackDownloadError.REFUSED)
        assertEquals(failed, ForeignPackPromptRules.downloadShown(dialog(swiss), failed))
        assertEquals(ForeignPackDownloadState.Idle, ForeignPackPromptRules.downloadShown(dialog(french), failed))
    }

    @Test
    fun `each country has its own texts and each error its line`() {
        val ch = ForeignPackPromptRules.country("CH")!!
        val at = ForeignPackPromptRules.country("AT")!!
        val fr = ForeignPackPromptRules.country("FR")!!
        assertEquals(R.string.foreign_pack_prompt_ch, ch.prompt)
        assertEquals(R.string.foreign_pack_expired_update_app_at, at.expired)
        assertEquals(R.string.foreign_pack_board_fr, fr.board)
        assertEquals(R.string.foreign_pack_partial_banner_ch, ch.banner)
        assertEquals(R.string.settings_foreign_pack_country_at, at.name)
        assertEquals(15, listOf(ch, at, fr).flatMap { listOf(it.name, it.prompt, it.banner, it.board, it.expired) }.toSet().size)
        assertNull(ForeignPackPromptRules.country("DE"))
        assertEquals(listOf(swiss, austrian), ForeignPackPromptRules.supported(listOf(swiss, unknown, austrian)))
        assertEquals(R.string.foreign_pack_error_network, ForeignPackPromptRules.error(ForeignPackDownloadError.NETWORK))
        assertEquals(R.string.foreign_pack_error_refused, ForeignPackPromptRules.error(ForeignPackDownloadError.REFUSED))
        assertEquals(R.string.foreign_pack_error_invalid, ForeignPackPromptRules.error(ForeignPackDownloadError.INVALID))
    }

    @Test
    fun `Settings status lines`() {
        val ch = ForeignPackPromptRules.country("CH")!!
        fun row(state: State) = ForeignPackSettingsRow("CH", swiss.sourceId, state, swiss.sizeBytes, swiss.lastServiceDate)
        assertEquals(ForeignPackPromptRules.StatusText(R.string.settings_foreign_pack_status_included),
            ForeignPackPromptRules.status(ForeignPackSettingsRow("CH", null, State.INCLUDED, 0L, null), ch))
        assertEquals(ForeignPackPromptRules.StatusText(R.string.settings_foreign_pack_status_border), ForeignPackPromptRules.status(row(State.BORDER_ONLY), ch))
        assertEquals(
            ForeignPackPromptRules.StatusText(R.string.settings_foreign_pack_status_installed, listOf(StatusArg.SIZE, StatusArg.DATE)),
            ForeignPackPromptRules.status(row(State.INSTALLED), ch)
        )
        assertEquals(
            ForeignPackPromptRules.StatusText(R.string.settings_foreign_pack_status_update, listOf(StatusArg.SIZE)),
            ForeignPackPromptRules.status(row(State.UPDATE_AVAILABLE), ch)
        )
        assertEquals(
            ForeignPackPromptRules.StatusText(R.string.foreign_pack_expired_update_app_ch, listOf(StatusArg.DATE)),
            ForeignPackPromptRules.status(row(State.EXPIRED), ch)
        )
    }
}
