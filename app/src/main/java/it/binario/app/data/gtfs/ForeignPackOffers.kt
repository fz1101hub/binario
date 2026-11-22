package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.ForeignPackDownloadState.Running
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a download of a full foreign pack did not install anything (the texts of the dialog and of Settings). */
enum class ForeignPackDownloadError {
    /** «Download non riuscito. Riprova più tardi.» */
    NETWORK,
    /** GitHub answered 403 or 429, or is paused after doing so. */
    REFUSED,
    /** The bytes received are not the pinned ones: nothing was kept. */
    INVALID
}

/** The one download of a full foreign pack the process can run, as the dialog and Settings show it. */
sealed interface ForeignPackDownloadState {
    data object Idle : ForeignPackDownloadState

    /** [written] of [total] bytes received for [sourceId]; [total] is the pinned size. */
    data class Running(val sourceId: SourceId, val written: Long, val total: Long) : ForeignPackDownloadState {
        /** 0..1 for the progress bar. */
        val fraction: Float get() = if (total <= 0L) 0f else (written.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /** The last download of [sourceId] failed; shown until another download starts or the user closes it. */
    data class Failed(val sourceId: SourceId, val error: ForeignPackDownloadError) : ForeignPackDownloadState
}

/** One country of Settings › «Orari dei paesi confinanti». */
data class ForeignPackSettingsRow(
    /** ISO code of the country ("CH", "AT", "FR"): it picks the texts. */
    val country: String,
    /** The pack of a downloadable pin; null when the country's timetable is bundled whole. */
    val sourceId: SourceId?,
    val state: State,
    /** The pinned size of the full pack (0 when it cannot be downloaded). */
    val sizeBytes: Long,
    /** The last day of the full pack's timetable (null when bundled whole). */
    val lastServiceDate: LocalDate?
) {
    enum class State {
        /** Bundled whole in the app (no download pin, owner decision D2): a static status, no button. */
        INCLUDED,
        /** Only the border zone is bundled; the full pack can be downloaded. */
        BORDER_ONLY,
        /** The full pack of this version's pin is installed: it can be deleted. */
        INSTALLED,
        /** A full pack of an older version was removed at the update: it can be downloaded again. */
        UPDATE_AVAILABLE,
        /** The pin's timetable has ended: no download, «aggiorna Binario». */
        EXPIRED
    }

    val canDownload: Boolean get() = sourceId != null && (state == State.BORDER_ONLY || state == State.UPDATE_AVAILABLE)
    val canDelete: Boolean get() = sourceId != null && state == State.INSTALLED

    companion object {
        /** The countries Settings lists, in this order, whatever the pins file holds. */
        val COUNTRIES = listOf("CH", "AT", "FR")

        /**
         * The rows of Settings for [pins]: a country without a downloadable pin is bundled whole (or, for a border
         * subset without a pin, border only with no button); a downloadable one takes its [status] from the store.
         */
        fun build(pins: ForeignPackPins, today: LocalDate, status: (ForeignPackEntry, LocalDate) -> ForeignPackStatus): List<ForeignPackSettingsRow> =
            COUNTRIES.map { country ->
                val entry = pins.packs.firstOrNull { it.country == country }
                when {
                    entry == null || !entry.borderOnly -> ForeignPackSettingsRow(country, null, State.INCLUDED, 0L, null)
                    !entry.downloadable -> ForeignPackSettingsRow(country, null, State.BORDER_ONLY, 0L, entry.lastServiceDate)
                    else -> ForeignPackSettingsRow(
                        country,
                        entry.sourceId,
                        when (status(entry, today)) {
                            ForeignPackStatus.BORDER_ONLY -> State.BORDER_ONLY
                            ForeignPackStatus.INSTALLED -> State.INSTALLED
                            ForeignPackStatus.UPDATE_AVAILABLE -> State.UPDATE_AVAILABLE
                            ForeignPackStatus.EXPIRED -> State.EXPIRED
                        },
                        entry.android?.sizeBytes ?: 0L,
                        entry.lastServiceDate
                    )
                }
            }
    }
}

/**
 * The process-wide side of the foreign pack offers (design §3.3–3.5): the [ForeignPackAdvisor] of the current files,
 * the packs the user declined in this process (no more dialogs for them until the app is restarted), the one download
 * that may run, started only by a tap and cancellable, and Settings' rows and «Elimina». Every screen reads the same
 * instance, so a download started from the results shows its progress in Settings too. An install or a delete bumps
 * [generation]: the view models then run their current search again on the new files.
 */
class ForeignPackOffers(
    val pins: ForeignPackPins,
    /** Bumped by every install and delete of a full pack ([ForeignPackStore.generation]). */
    val generation: StateFlow<Long>,
    /** The full pack of a source is installed for this version's pin (cheap: read from the current snapshot). */
    private val installed: (SourceId) -> Boolean,
    /** Settings' state of a downloadable pack; may read the disk, so [settingsRows] is called off the main thread. */
    private val status: (ForeignPackEntry, LocalDate) -> ForeignPackStatus,
    private val download: suspend (SourceId, (Long, Long) -> Unit) -> ForeignPackDownloadResult,
    private val remove: (SourceId) -> Unit,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate
) {
    private val declinedIds = MutableStateFlow<Set<SourceId>>(emptySet())

    /** The packs whose dialog the user answered «Non ora» in this process. */
    val declined: StateFlow<Set<SourceId>> = declinedIds.asStateFlow()

    private val state = MutableStateFlow<ForeignPackDownloadState>(ForeignPackDownloadState.Idle)
    val downloadState: StateFlow<ForeignPackDownloadState> = state.asStateFlow()
    private var job: Job? = null

    fun advisor(): ForeignPackAdvisor = ForeignPackAdvisor(pins, installed, today) { declinedIds.value }

    /** The offer after a search between two stations (journeys or «Ricerca tratta»); null when there is nothing to ask. */
    fun afterSearch(origin: String?, destination: String?, found: Boolean): ForeignPackOffer? =
        advisor().afterSearch(origin, destination, found)

    /** The packs whose full timetable would serve [stationId] where the bundled border zone does not («Partenze»). */
    fun forStation(stationId: String?): List<ForeignPackChoice> = advisor().forStation(stationId)

    /** A station of [stationIds] is served by a pack bundled as its border zone: a search there depends on the files. */
    fun touches(vararg stationIds: String?): Boolean = pins.borderOnly.any { entry -> stationIds.any(entry::serves) }

    /** «Non ora»: no more dialogs for the packs of [offer] in this process. */
    fun decline(offer: ForeignPackOffer) {
        val ids = (offer.packs + offer.expired).map(ForeignPackChoice::sourceId)
        if (ids.isNotEmpty()) declinedIds.update { it + ids }
    }

    /**
     * «Scarica»: downloads the full pack of [sourceId] (one at a time; a tap while another runs does nothing). There
     * are no retries: after a failure only a new tap starts again.
     */
    fun start(sourceId: SourceId) {
        synchronized(this) {
            if (job?.isActive == true) return
            state.value = Running(sourceId, 0L, pins.entry(sourceId)?.android?.sizeBytes ?: 0L)
            job = scope.launch {
                try {
                    val result = download(sourceId) { written, total ->
                        state.update { current -> if (current.isRunning(sourceId)) Running(sourceId, written, total) else current }
                    }
                    state.value = outcome(sourceId, result)
                } catch (error: CancellationException) {
                    state.update { current -> if (current.isRunning(sourceId)) ForeignPackDownloadState.Idle else current }
                    throw error
                }
            }
        }
    }

    /** «Annulla» during a download: it stops at the next block and nothing is kept. */
    fun cancel() {
        synchronized(this) {
            job?.cancel()
            job = null
        }
        state.update { if (it is ForeignPackDownloadState.Running) ForeignPackDownloadState.Idle else it }
    }

    /** Closes the error of the last download. */
    fun clearError() {
        state.update { if (it is ForeignPackDownloadState.Failed) ForeignPackDownloadState.Idle else it }
    }

    /** Settings › «Elimina»: effective at once (the readers of the next search use the border zone again). */
    fun delete(sourceId: SourceId) {
        remove(sourceId)
    }

    /** Settings' rows; [status] may read the disk, so this runs off the main thread. */
    fun settingsRows(): List<ForeignPackSettingsRow> = ForeignPackSettingsRow.build(pins, today(), status)

    private fun ForeignPackDownloadState.isRunning(sourceId: SourceId): Boolean =
        this is ForeignPackDownloadState.Running && this.sourceId == sourceId

    companion object {
        /** The state after a download of [sourceId] ended with [result]. */
        fun outcome(sourceId: SourceId, result: ForeignPackDownloadResult): ForeignPackDownloadState = when (result) {
            ForeignPackDownloadResult.Installed, ForeignPackDownloadResult.Busy, ForeignPackDownloadResult.NotOffered -> ForeignPackDownloadState.Idle
            ForeignPackDownloadResult.NetworkError -> ForeignPackDownloadState.Failed(sourceId, ForeignPackDownloadError.NETWORK)
            ForeignPackDownloadResult.Refused -> ForeignPackDownloadState.Failed(sourceId, ForeignPackDownloadError.REFUSED)
            ForeignPackDownloadResult.Invalid -> ForeignPackDownloadState.Failed(sourceId, ForeignPackDownloadError.INVALID)
        }
    }
}
