package it.binario.app.plannedjourney

import java.util.Locale

/** A ready-made checklist line; its text is localized by the UI and stored as typed, like any other line. */
enum class PlannedJourneyChecklistLine { TIME_MARGIN, DOCUMENTS, BELONGINGS, CONNECTION, BIKE, ASSISTANCE }

/** Preparation presets: each adds its lines in [PlannedJourneyChecklistLine] order. */
enum class PlannedJourneyChecklistPreset(private val lines: Set<PlannedJourneyChecklistLine>) {
    COMMUTER(setOf(PlannedJourneyChecklistLine.TIME_MARGIN, PlannedJourneyChecklistLine.DOCUMENTS, PlannedJourneyChecklistLine.BELONGINGS)),
    CONNECTION(COMMUTER.lines + PlannedJourneyChecklistLine.CONNECTION),
    BIKE(COMMUTER.lines + PlannedJourneyChecklistLine.BIKE),
    ASSISTANCE(COMMUTER.lines + PlannedJourneyChecklistLine.ASSISTANCE);

    fun orderedLines(): List<PlannedJourneyChecklistLine> = PlannedJourneyChecklistLine.entries.filter { it in lines }
}

object PlannedJourneyChecklistPresets {
    /**
     * The editor's checklist text with the preset's lines added after the user's own ones; a line already present
     * (same text, ignoring case and spaces) is not repeated, and the list stays within the checklist limit.
     */
    fun apply(text: String, preset: PlannedJourneyChecklistPreset, label: (PlannedJourneyChecklistLine) -> String): String {
        val existing = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val seen = existing.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        val added = preset.orderedLines().map { label(it).trim() }.filter { it.isNotEmpty() && seen.add(it.lowercase(Locale.ROOT)) }
        return (existing + added).take(PlannedJourneyPersistenceSchema.MAX_CHECKLIST_ITEMS).joinToString("\n")
    }
}
