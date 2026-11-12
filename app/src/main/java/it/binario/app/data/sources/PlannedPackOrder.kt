package it.binario.app.data.sources

import it.binario.app.data.core.SourceId

/** The classes of offline timetable packs, in the order a planned search takes them. */
enum class PlannedPackClass {
    /** The packs of the national Italian operators: Trenitalia and Italo ([PlannedPackOrder.nationalPacks]). */
    NATIONAL,
    /** The other Italian packs: regional railways (Trenord, STA, TFT, EAV, Trentino, ARST, FAL, …), border lines included. */
    ITALIAN_REGIONAL,
    /** The packs of a network abroad, whose first catalogue country is not Italy (Switzerland, ÖBB, SNCF, DELFI, HŽPP). */
    FOREIGN,
    /** Community mirrors and best-effort feeds ([RailSourceSelectionPolicy.isCoverageFallback]). */
    COMMUNITY_FALLBACK
}

/**
 * The order of the offline timetable packs in a planned search: when two packs publish the same train the copy of the
 * first one is kept, and the first ones take the bounded places of a search (the packs the journey planner adds for a
 * change between packs). Used by the journey planner and the route search only: the scores of
 * [RailSourceSelectionPolicy], the order of the live sources and [it.binario.app.data.SnapshotSourceAuthority] do not
 * change.
 *
 * In Italy the national packs come first, then the Italian regional ones, then the foreign ones, then the community
 * fallbacks ([PlannedPackClass]); within a class the planned-itinerary score of [RailSourceSelectionPolicy] (with the
 * operator affinity where the caller knows the operator) decides, so the foreign packs keep their order among themselves.
 * Border zones and local lines are the exception: when no national pack serves an end of the search (Lugano, Innsbruck,
 * Merano, Bergamo), the packs serving that end come first, in the same order among themselves ([ordered]).
 */
object PlannedPackOrder {
    /** Named here: the catalogue does not tell a national operator from a regional one. */
    val nationalPacks: Set<SourceId> = setOf(SourceId("trenitalia-gtfs"), SourceId("italo-gtfs"))

    private const val ITALY = "IT"

    /** First catalogue country of every source, read once ([RailSourceInventory.all] is rebuilt on every read). */
    private val firstCountry: Map<SourceId, String?> by lazy {
        RailSourceInventory.all.associate { it.id to it.countryCodes.firstOrNull() }
    }

    /**
     * The class of [sourceId], from the catalogue: a pack whose first country is not Italy is foreign (ÖBB is "AT, IT"),
     * one whose first country is Italy is Italian (STA is "IT, AT"). A pack the catalogue does not know, or without a
     * country, is taken as an Italian regional pack.
     */
    fun classOf(sourceId: SourceId): PlannedPackClass = when {
        RailSourceSelectionPolicy.isCoverageFallback(sourceId) -> PlannedPackClass.COMMUNITY_FALLBACK
        sourceId in nationalPacks -> PlannedPackClass.NATIONAL
        firstCountry[sourceId].let { it != null && it != ITALY } -> PlannedPackClass.FOREIGN
        else -> PlannedPackClass.ITALIAN_REGIONAL
    }

    /**
     * Packs (or services of a pack) in the planned order: those [first] selects, then by [PlannedPackClass], then by the
     * planned-itinerary score with [operatorHint]. Equal items keep their order (the sorts using it are stable).
     */
    fun <T> comparator(
        sourceId: (T) -> SourceId,
        operatorHint: (T) -> String? = { null },
        first: (T) -> Boolean = { false }
    ): Comparator<T> = compareBy<T> { if (first(it)) 0 else 1 }
        .thenBy { classOf(sourceId(it)).ordinal }
        .thenByDescending { RailSourceSelectionPolicy.score(RailInformationCategory.PLANNED_ITINERARY, sourceId(it), operatorHint(it)) }

    /**
     * [packs] of a search in the planned order. When no national pack serves the origin (or the destination), the packs
     * that serve it come first: their copy of a train is kept and they keep their places in the bounded selections.
     */
    fun <T> ordered(
        packs: List<T>,
        sourceId: (T) -> SourceId,
        coversOrigin: (T) -> Boolean,
        coversDestination: (T) -> Boolean
    ): List<T> {
        val nationalAtOrigin = packs.any { coversOrigin(it) && classOf(sourceId(it)) == PlannedPackClass.NATIONAL }
        val nationalAtDestination = packs.any { coversDestination(it) && classOf(sourceId(it)) == PlannedPackClass.NATIONAL }
        return packs.sortedWith(
            comparator(sourceId, first = { (!nationalAtOrigin && coversOrigin(it)) || (!nationalAtDestination && coversDestination(it)) })
        )
    }
}
