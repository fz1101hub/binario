package it.binario.app.plannedjourney

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import it.binario.app.data.RailTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class PlannedJourneyEditorState(
    val journeyId: String? = null,
    val label: String = "",
    val originQuery: String = "",
    val destinationQuery: String = "",
    val date: String = LocalDate.now(RailTime.defaultZone).toString(),
    val time: String = "09:00",
    val reminderMinutes: PlannedJourneyReminderMinutes? = null,
    val checklistText: String = "",
    val validationMessage: String? = null
) {
    val isEditing: Boolean get() = journeyId != null
}

sealed interface PlannedJourneyImportState {
    data object Idle : PlannedJourneyImportState
    data class Json(val preview: PlannedJourneyImportPreview) : PlannedJourneyImportState
    data class Ics(val preview: PlannedJourneyIcsImportPreview) : PlannedJourneyImportState
    data class Error(val detail: String) : PlannedJourneyImportState
}

/** UI-oriented state holder; all durable writes are delegated to [PlannedJourneyRepository]. */
class PlannedJourneyViewModel(
    private val repository: PlannedJourneyRepository,
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = RailTime.defaultZone,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {
    var snapshot by mutableStateOf(repository.read().snapshot)
        private set
    var warning by mutableStateOf(repository.read().warning)
        private set
    var filter by mutableStateOf(PlannedJourneyAgendaFilter.UPCOMING)
        private set
    var editor by mutableStateOf<PlannedJourneyEditorState?>(null)
        private set
    var importState by mutableStateOf<PlannedJourneyImportState>(PlannedJourneyImportState.Idle)
        private set
    var feedback by mutableStateOf<String?>(null)
        private set

    fun agendaEntries(): List<PlannedJourneyAgendaEntry> = PlannedJourneyAgenda.ordered(
        journeys = snapshot.journeys,
        filter = filter,
        now = now()
    )

    fun selectFilter(value: PlannedJourneyAgendaFilter) {
        filter = value
    }

    fun beginCreate() {
        editor = PlannedJourneyEditorState(date = LocalDate.now(zone).toString())
        feedback = null
    }

    fun beginEdit(journey: PlannedJourney) {
        val segment = journey.itinerary.segments.first()
        editor = PlannedJourneyEditorState(
            journeyId = journey.id,
            label = journey.label,
            originQuery = segment.origin.displayName,
            destinationQuery = segment.destination.displayName,
            date = segment.departure.date.toString(),
            time = segment.departure.time.toString(),
            reminderMinutes = journey.reminder?.minutesBeforeDeparture,
            checklistText = journey.checklist.joinToString("\n") { item -> item.label }
        )
        feedback = null
    }

    fun changeEditor(transform: (PlannedJourneyEditorState) -> PlannedJourneyEditorState) {
        editor?.let { editor = transform(it).copy(validationMessage = null) }
    }

    fun dismissEditor() {
        editor = null
    }

    /** Stations are supplied by the app catalog, so persisted plans retain canonical identities. */
    fun saveEditor(stations: Iterable<PlannedJourneyStation>) {
        val current = editor ?: return
        val stationList = stations.toList()
        val origin = resolveStation(current.originQuery, stationList)
        val destination = resolveStation(current.destinationQuery, stationList)
        val departure = runCatching {
            PlannedJourneyDateTime(LocalDate.parse(current.date.trim()), LocalTime.parse(current.time.trim()), zone)
        }.getOrNull()
        if (origin == null || destination == null || departure == null) {
            editor = current.copy(validationMessage = "Scegli stazioni del catalogo e inserisci data e orario validi.")
            return
        }
        val checklist = current.checklistText.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .take(PlannedJourneyPersistenceSchema.MAX_CHECKLIST_ITEMS)
            .mapIndexed { index, label -> PlannedJourneyChecklistItem("check-${index + 1}", label) }
            .toList()
        val existing = current.journeyId?.let { id -> snapshot.journeys.firstOrNull { it.id == id } }
        val candidate = if (existing == null) {
            PlannedJourneyFactory.create(
                PlannedJourneyDraft(
                    id = newId(),
                    label = current.label,
                    itinerary = PlannedJourneyItineraryDraft(
                        listOf(PlannedJourneySegmentDraft("segment-1", origin, destination, departure))
                    ),
                    reminder = current.reminderMinutes?.let(::PlannedJourneyReminder),
                    checklist = checklist,
                    createdAt = now()
                )
            ).let { result ->
                when (result) {
                    is PlannedJourneyValidationResult.Valid -> result.value
                    is PlannedJourneyValidationResult.Invalid -> {
                        editor = current.copy(validationMessage = result.issues.first().detail)
                        return
                    }
                }
            }
        } else {
            existing.copy(
                label = current.label.trim(),
                itinerary = PlannedJourneyItinerary(
                    listOf(PlannedJourneySegment("segment-1", origin, destination, departure))
                ),
                reminder = current.reminderMinutes?.let(::PlannedJourneyReminder),
                checklist = checklist
            )
        }
        persist(repository.save(candidate), "Viaggio pianificato salvato.") { editor = null }
    }

    fun complete(entry: PlannedJourneyAgendaEntry) {
        val result = if (entry.journey.recurrence == null) repository.complete(entry.journey.id, now())
        else repository.completeOccurrence(entry.journey.id, entry.occurrence.occurrenceDate)
        persist(result, "Occorrenza completata.")
    }

    fun archive(journeyId: String) {
        persist(repository.archive(journeyId, now()), "Viaggio archiviato.")
    }

    fun toggleChecklist(journeyId: String, item: PlannedJourneyChecklistItem) {
        persist(repository.updateChecklistItem(journeyId, item.id, !item.completed), "Checklist aggiornata.")
    }

    fun previewImport(content: String, isIcs: Boolean) {
        importState = if (isIcs) {
            when (val result = repository.previewIcsImport(content)) {
                is PlannedJourneyIcsImportResult.Preview -> PlannedJourneyImportState.Ics(result.preview)
                is PlannedJourneyIcsImportResult.Invalid -> PlannedJourneyImportState.Error(result.detail)
            }
        } else {
            when (val result = repository.previewJsonImport(content)) {
                is PlannedJourneyJsonImportResult.Preview -> PlannedJourneyImportState.Json(result.preview)
                is PlannedJourneyJsonImportResult.Invalid -> PlannedJourneyImportState.Error(result.detail)
            }
        }
    }

    fun applyJsonImport(policy: PlannedJourneyConflictPolicy) {
        val current = importState as? PlannedJourneyImportState.Json ?: return
        persist(repository.applyJsonPreview(current.preview, policy), "Importazione completata.") { importState = PlannedJourneyImportState.Idle }
    }

    fun dismissImportPreview() {
        importState = PlannedJourneyImportState.Idle
    }

    fun exportJson(): String = repository.exportJson()
    fun exportIcs(): String = repository.exportIcs(now())

    fun note(message: String) {
        feedback = message
    }

    private fun persist(
        result: PlannedJourneyRepositorySaveResult,
        success: String,
        afterSuccess: () -> Unit = {}
    ) {
        when (result) {
            is PlannedJourneyRepositorySaveResult.Saved -> {
                snapshot = result.snapshot
                warning = null
                feedback = success
                afterSuccess()
            }
            is PlannedJourneyRepositorySaveResult.Rejected -> feedback = result.detail
        }
    }

    private fun resolveStation(query: String, stations: List<PlannedJourneyStation>): PlannedJourneyStation? {
        val normalized = query.trim()
        return stations.firstOrNull { it.canonicalId.equals(normalized, ignoreCase = true) }
            ?: stations.firstOrNull { it.displayName.equals(normalized, ignoreCase = true) }
    }
}
