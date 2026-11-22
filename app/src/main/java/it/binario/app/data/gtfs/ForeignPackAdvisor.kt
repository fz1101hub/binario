package it.binario.app.data.gtfs

import it.binario.app.data.core.SourceId
import java.time.LocalDate

/** One country the app can offer: its pack, the size of the pinned download and the last day of its timetable. */
data class ForeignPackChoice(
    val sourceId: SourceId,
    /** ISO code of the country ("CH", "AT", "FR"): it picks the texts. */
    val country: String,
    val sizeBytes: Long,
    val lastServiceDate: LocalDate
)

/**
 * What the app proposes after a search. [DIALOG]: the search found nothing and an end needs a full pack. [BANNER]: the
 * search found options, but both ends lie outside the border zone of the same pack, so more trains may exist.
 */
data class ForeignPackOffer(
    val style: Style,
    /** The packs to offer, one row per country. */
    val packs: List<ForeignPackChoice>,
    /** Packs an end needs whose timetable of this version has ended: no download, «aggiorna Binario». */
    val expired: List<ForeignPackChoice> = emptyList()
) {
    enum class Style { DIALOG, BANNER }
}

/**
 * When the app asks to download a full foreign pack (design §3.3, owner decisions Q3 and Q4). Only a pack bundled as its
 * border subset with a download pin can be offered ([ForeignPackEntry.downloadable]); a pack bundled whole never is.
 * For such a pack P not installed, `needs(P, e)` holds when station `e` is served by the full pack but is not one of its
 * zone stations. A pack whose pin has expired is never offered: the dialog names it as ended instead.
 */
class ForeignPackAdvisor(
    private val pins: ForeignPackPins,
    private val installed: (SourceId) -> Boolean,
    private val today: () -> LocalDate,
    /** Packs the user declined in this process: no more dialogs for them. */
    private val declined: () -> Set<SourceId> = { emptySet() }
) {
    /** True when the readers can use [pack] at end [stationId] (always, unless it is a border subset and the end is out of its zone). */
    fun covers(pack: EmbeddedGtfsPack, stationId: String?): Boolean = pack.coversEnd(stationId)

    /** The full pack of [entry] would serve [stationId] where its border subset does not. */
    fun needs(entry: ForeignPackEntry, stationId: String?): Boolean =
        entry.downloadable && !installed(entry.sourceId) && entry.serves(stationId) && !entry.inZone(stationId)

    /** The packs not installed, still valid, that some end of [ends] needs (the dialog's rows). */
    fun missingFor(ends: List<String?>): List<ForeignPackEntry> =
        pins.downloadable.filter { entry -> !entry.expiredOn(today()) && ends.any { needs(entry, it) } }

    /** The packs not installed, still valid, that every end of [ends] needs (the banner's packs). */
    fun incompleteFor(ends: List<String?>): List<ForeignPackEntry> =
        if (ends.isEmpty()) emptyList()
        else pins.downloadable.filter { entry -> !entry.expiredOn(today()) && ends.all { needs(entry, it) } }

    /** The expired pins an end of [ends] needs: the app says that this version's timetable has ended. */
    fun expiredFor(ends: List<String?>): List<ForeignPackEntry> =
        pins.downloadable.filter { entry -> entry.expiredOn(today()) && ends.any { needs(entry, it) } }

    /**
     * The offer after a search between [origin] and [destination] (journeys or «Ricerca tratta») that [found] options or
     * not; null when there is nothing to ask. Packs declined in this process are not asked again in a dialog.
     */
    fun afterSearch(origin: String?, destination: String?, found: Boolean): ForeignPackOffer? {
        val ends = listOf(origin, destination)
        if (found) {
            val packs = incompleteFor(ends).map(::choice)
            return packs.takeIf { it.isNotEmpty() }?.let { ForeignPackOffer(ForeignPackOffer.Style.BANNER, it) }
        }
        val skipped = declined()
        val packs = missingFor(ends).filterNot { it.sourceId in skipped }.map(::choice)
        val expired = expiredFor(ends).filterNot { it.sourceId in skipped }.map(::choice)
        if (packs.isEmpty() && expired.isEmpty()) return null
        return ForeignPackOffer(ForeignPackOffer.Style.DIALOG, packs, expired)
    }

    /** The line of a station's «Partenze» when a full pack would serve it: one choice per country. */
    fun forStation(stationId: String?): List<ForeignPackChoice> = missingFor(listOf(stationId)).map(::choice)

    private fun choice(entry: ForeignPackEntry) = ForeignPackChoice(
        entry.sourceId,
        entry.country,
        entry.android?.sizeBytes ?: 0L,
        entry.lastServiceDate
    )
}
