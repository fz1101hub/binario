package it.binario.app.plannedjourney
import android.content.Context

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import it.binario.app.data.RailTime
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlannedJourneyEditorState(
    val journeyId: String? = null,
    val label: String = "",
    val originQuery: String = "",
    val destinationQuery: String = "",
    val originStationId: String? = null,
    val destinationStationId: String? = null,
    val isMultiSegment: Boolean = false,
    val date: String = LocalDate.now(RailTime.defaultZone).toString(),
    val time: String = "09:00",
    val timeZoneId: String = RailTime.defaultZone.id,
    val reminderMinutes: PlannedJourneyReminderMinutes? = null,
    val checklistText: String = "",
    val validationMessage: UiMessage? = null,
    /** The user saw that the departure has already passed and tapped Save again. */
    val pastDepartureConfirmed: Boolean = false,
    /** Information shown at the top of the form, such as «Ritorno preparato». */
    val notice: UiMessage? = null,
    /** Extra weekdays of a repeated journey; the weekday of [date] is always part of it. */
    val repeatWeekdays: Set<DayOfWeek> = emptySet(),
    /** How many journeys the recurrence generates in all; 1 is a single journey. */
    val repeatCount: Int = 1,
    /** An imported recurrence's last day, kept while it does not precede the first departure. */
    val repeatEndsOn: LocalDate? = null
) {
    val isEditing: Boolean get() = journeyId != null
}

sealed interface PlannedJourneyImportState {
    data object Idle : PlannedJourneyImportState
    data class Json(val preview: PlannedJourneyImportPreview) : PlannedJourneyImportState
    data class Ics(val preview: PlannedJourneyIcsImportPreview) : PlannedJourneyImportState
    data class Error(val message: UiMessage) : PlannedJourneyImportState
}

/**
 * The guided text import: the text stays in memory only (never in preferences or saved state) until the import is
 * completed; [candidates] is null until «Crea anteprima».
 */
data class PlannedJourneyTextImportState(
    val text: String = "",
    val visible: Boolean = true,
    val candidates: List<PlannedJourney>? = null,
    val selected: Set<String> = emptySet(),
    val skipped: Int = 0,
    val reviewed: Boolean = false,
    val message: UiMessage? = null,
    /** Received with «Condividi» from another app rather than typed here. */
    val fromShare: Boolean = false,
    /** Read from another app's calendar file: there is no text to edit, only the events to choose. */
    val fromCalendar: Boolean = false
)

/** UI-oriented state holder; all durable writes are delegated to [PlannedJourneyRepository]. */
enum class PlannedJourneyExportFormat(val suggestedName: String) {
    JSON("binario-viaggi-pianificati.json"),
    ICS("binario-agenda.ics")
}

class PlannedJourneyViewModel(
    private val repository: PlannedJourneyRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = RailTime.defaultZone,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val reminders: PlannedJourneyReminderController? = null,
    private val preferences: PlannedJourneyPreferences? = null,
    private val templateStore: PlannedJourneyTemplateStore? = null
) : ViewModel() {
    private val initialRead = repository.read()
    var snapshot by mutableStateOf(initialRead.snapshot)
        private set
    var warning by mutableStateOf(initialRead.warning?.let { UiMessage(UiMessageKey.PLANNED_JOURNEY_STORED_UNREADABLE) })
        private set
    /** The stored Agenda is not fully readable: [snapshot] holds the recovered journeys and every write is refused. */
    var readOnly by mutableStateOf(initialRead.readOnly)
        private set

    /** Reads the stored Agenda again, when a screen showing it returns to the foreground. */
    fun reload() {
        val read = repository.read()
        snapshot = read.snapshot
        readOnly = read.readOnly
        warning = read.warning?.let { UiMessage(UiMessageKey.PLANNED_JOURNEY_STORED_UNREADABLE) }
    }
    var filter by mutableStateOf(
        savedStateHandle.get<String>(AGENDA_FILTER_KEY)
            ?.let { runCatching { PlannedJourneyAgendaFilter.valueOf(it) }.getOrNull() }
            ?: PlannedJourneyAgendaFilter.UPCOMING
    )
        private set
    var editor by mutableStateOf<PlannedJourneyEditorState?>(null)
        private set
    var importState by mutableStateOf<PlannedJourneyImportState>(PlannedJourneyImportState.Idle)
        private set
    var feedback by mutableStateOf<UiMessage?>(null)
        private set
    var textImport by mutableStateOf<PlannedJourneyTextImportState?>(null)
        private set

    /** Opens the text import, keeping a text typed earlier unless a new one is given (e.g. shared from another app). */
    fun beginTextImport(text: String? = null, fromShare: Boolean = false) {
        val current = textImport
        textImport = if (text != null || current == null) {
            PlannedJourneyTextImportState(text = text.orEmpty().take(PlannedJourneyTextImport.MAX_CHARACTERS), fromShare = fromShare)
        } else {
            current.copy(visible = true)
        }
        feedback = null
    }

    fun changeTextImport(text: String) {
        textImport = textImport?.copy(text = text.take(PlannedJourneyTextImport.MAX_CHARACTERS), candidates = null,
            selected = emptySet(), skipped = 0, reviewed = false, message = null)
    }

    fun previewTextImport(stations: Iterable<PlannedJourneyStation>) {
        val current = textImport ?: return
        val text = PlannedJourneyTextImport.normalize(current.text)
        val preview = text?.let {
            PlannedJourneyTextImport.preview(it, stations.toList(), snapshot.journeys, zone, now(), newId)
        }
        textImport = if (preview == null || preview.candidates.isEmpty()) {
            current.copy(candidates = null, selected = emptySet(), skipped = preview?.skipped ?: 0, reviewed = false,
                message = UiMessage(UiMessageKey.PLANNED_JOURNEY_TEXT_IMPORT_NONE))
        } else {
            current.copy(candidates = preview.candidates, selected = preview.candidates.mapTo(mutableSetOf()) { it.id },
                skipped = preview.skipped, reviewed = false, message = null)
        }
    }

    fun toggleTextImportCandidate(id: String) {
        val current = textImport ?: return
        textImport = current.copy(selected = if (id in current.selected) current.selected - id else current.selected + id, message = null)
    }

    fun setTextImportReviewed(reviewed: Boolean) {
        textImport = textImport?.copy(reviewed = reviewed, message = null)
    }

    /** Adds the selected journeys only after the explicit «Ho verificato»; never replaces a journey. */
    fun applyTextImport() {
        val current = textImport ?: return
        val chosen = current.candidates.orEmpty().filter { it.id in current.selected }
        if (!current.reviewed || chosen.isEmpty()) {
            textImport = current.copy(message = UiMessage(UiMessageKey.PLANNED_JOURNEY_TEXT_IMPORT_CONFIRM_NEEDED))
            return
        }
        val result = repository.applyJsonPreview(
            PlannedJourneyImportPreview(accepted = chosen, duplicates = emptyList(), conflicts = emptyList()),
            PlannedJourneyConflictPolicy.KEEP_EXISTING
        )
        when (result) {
            is PlannedJourneyRepositorySaveResult.Saved -> persist(result, UiMessageKey.PLANNED_JOURNEY_IMPORT_SUCCESS) { textImport = null }
            is PlannedJourneyRepositorySaveResult.Rejected -> textImport = current.copy(message = UiMessage(rejectionMessage(result.reason)))
        }
    }

    /** Hides the import; the text stays in memory until the import is completed or the text is deleted. */
    fun dismissTextImport() {
        textImport = textImport?.copy(visible = false)
    }
    var pendingExport by mutableStateOf(
        savedStateHandle.get<String>(PENDING_EXPORT_KEY)
            ?.let { runCatching { PlannedJourneyExportFormat.valueOf(it) }.getOrNull() }
    )
        private set
    var reminderStatus by mutableStateOf(PlannedJourneyReminderStatus())
        private set
    /** The lead proposed to every new journey, return and replan; the editor can still change it. */
    var defaultReminder by mutableStateOf(runCatching { preferences?.defaultReminder() }.getOrNull())
        private set

    /** Saved journey templates, newest first (at most [PlannedJourneyTemplates.MAX_TEMPLATES]). */
    var templates by mutableStateOf(runCatching { PlannedJourneyTemplates.decode(templateStore?.read()) }.getOrDefault(emptyList()))
        private set

    /** Saves the editor's route, time, lead and checklist under [name]; the date is never part of a template. */
    fun saveTemplate(name: String, stations: Iterable<PlannedJourneyStation>) {
        val current = editor ?: return
        val store = templateStore ?: return
        val stationList = stations.toList()
        val origin = resolveStation(current.originQuery, current.originStationId, stationList)
        val destination = resolveStation(current.destinationQuery, current.destinationStationId, stationList)
        val time = runCatching { LocalTime.parse(current.time.trim()) }.getOrNull()
        val template = if (origin == null || destination == null || time == null) null else PlannedJourneyTemplates.validated(
            PlannedJourneyTemplate(newId(), name, origin, destination, time, current.reminderMinutes, checklistLines(current.checklistText))
        )
        if (template == null) {
            editor = current.copy(validationMessage = UiMessage(UiMessageKey.PLANNED_JOURNEY_TEMPLATE_INVALID))
            return
        }
        val updated = PlannedJourneyTemplates.add(templates, template)
        runCatching { store.write(PlannedJourneyTemplates.encode(updated)) }
            .onSuccess {
                templates = updated
                editor = current.copy(notice = UiMessage(UiMessageKey.PLANNED_JOURNEY_TEMPLATE_SAVED), validationMessage = null)
            }
            .onFailure { editor = current.copy(validationMessage = UiMessage(UiMessageKey.PLANNED_JOURNEY_ACTION_REJECTED)) }
    }

    /** Fills the editor from a template: route, time, lead and checklist; the date must be chosen anew. */
    fun applyTemplate(id: String) {
        val current = editor ?: return
        val template = templates.firstOrNull { it.id == id } ?: return
        editor = current.copy(
            originQuery = template.origin.displayName,
            originStationId = template.origin.canonicalId,
            destinationQuery = template.destination.displayName,
            destinationStationId = template.destination.canonicalId,
            date = "",
            time = template.time.toString(),
            reminderMinutes = template.reminder,
            checklistText = template.checklist.joinToString("\n"),
            notice = UiMessage(UiMessageKey.PLANNED_JOURNEY_TEMPLATE_APPLIED, template.name),
            validationMessage = null,
            pastDepartureConfirmed = false
        )
    }

    fun deleteTemplate(id: String) {
        val store = templateStore ?: return
        val updated = templates.filterNot { it.id == id }
        runCatching { store.write(if (updated.isEmpty()) null else PlannedJourneyTemplates.encode(updated)) }.onSuccess {
            templates = updated
            editor = editor?.copy(notice = UiMessage(UiMessageKey.PLANNED_JOURNEY_TEMPLATE_DELETED))
        }
    }

    fun selectDefaultReminder(value: PlannedJourneyReminderMinutes?) {
        val store = preferences ?: return
        runCatching { store.setDefaultReminder(value) }.onSuccess { defaultReminder = value }
    }
    private var reminderRefreshGeneration = 0L

    fun refreshReminders() {
        val controller = reminders ?: return
        val generation = ++reminderRefreshGeneration
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching { controller.refresh() }.getOrElse { reminderStatus.copy(failed = true) }
            }
            if (generation == reminderRefreshGeneration) reminderStatus = status
        }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        val controller = reminders ?: return
        runCatching { controller.setEnabled(enabled) }
            .onSuccess {
                reminderStatus = reminderStatus.copy(enabled = enabled)
                refreshReminders()
            }
            .onFailure { reminderStatus = reminderStatus.copy(failed = true) }
    }

    fun agendaEntries(): List<PlannedJourneyAgendaEntry> = PlannedJourneyAgenda.ordered(
        journeys = snapshot.journeys,
        filter = filter,
        now = now()
    )

    fun selectFilter(value: PlannedJourneyAgendaFilter) {
        filter = value
        savedStateHandle[AGENDA_FILTER_KEY] = value.name
    }

    fun beginCreate() {
        editor = PlannedJourneyEditorState(date = LocalDate.now(zone).toString(), timeZoneId = zone.id, reminderMinutes = defaultReminder)
        feedback = null
    }

    fun beginEdit(journey: PlannedJourney) {
        val segment = journey.itinerary.segments.first()
        editor = PlannedJourneyEditorState(
            journeyId = journey.id,
            label = journey.label,
            originQuery = segment.origin.displayName,
            destinationQuery = segment.destination.displayName,
            originStationId = segment.origin.canonicalId,
            destinationStationId = segment.destination.canonicalId,
            isMultiSegment = journey.itinerary.segments.size > 1,
            date = segment.departure.date.toString(),
            time = segment.departure.time.toString(),
            timeZoneId = segment.departure.timeZone.id,
            reminderMinutes = journey.reminder?.minutesBeforeDeparture,
            checklistText = journey.checklist.joinToString("\n") { item -> item.label },
            repeatWeekdays = journey.recurrence?.weekdays?.toSet().orEmpty(),
            repeatCount = journey.recurrence?.occurrenceLimit ?: 1,
            repeatEndsOn = journey.recurrence?.endsOn
        )
        feedback = null
    }

    /** The reverse route of a one-leg journey: date, time, reminder and checklist ticks are chosen anew. */
    fun beginReturn(journey: PlannedJourney) =
        beginFromDraft(journey, PlannedJourneyDrafts.returnFrom(journey, DRAFT_ID, now()), UiMessageKey.PLANNED_JOURNEY_RETURN_PREPARED)

    /** The same route of a one-leg journey on a new date and time. */
    fun beginReplan(journey: PlannedJourney) =
        beginFromDraft(journey, PlannedJourneyDrafts.replanFrom(journey, DRAFT_ID, now()), UiMessageKey.PLANNED_JOURNEY_REPLAN_PREPARED)

    private fun beginFromDraft(source: PlannedJourney, draft: PlannedJourneyDraft, notice: UiMessageKey) {
        val segment = draft.itinerary.segments.singleOrNull() ?: return
        editor = PlannedJourneyEditorState(
            originQuery = segment.origin.displayName,
            destinationQuery = segment.destination.displayName,
            originStationId = segment.origin.canonicalId,
            destinationStationId = segment.destination.canonicalId,
            date = "",
            time = "",
            timeZoneId = zone.id,
            // The source's reminder is not inherited: the new journey gets the default lead. The labels come back unticked.
            reminderMinutes = defaultReminder,
            checklistText = source.checklist.joinToString("\n") { item -> item.label },
            notice = UiMessage(notice)
        )
        feedback = null
    }

    fun changeEditor(transform: (PlannedJourneyEditorState) -> PlannedJourneyEditorState) {
        editor?.let { editor = transform(it).copy(validationMessage = null, pastDepartureConfirmed = false) }
    }

    fun dismissEditor() {
        editor = null
    }

    /** Stations are supplied by the app catalog, so persisted plans retain canonical identities. */
    fun saveEditor(stations: Iterable<PlannedJourneyStation>) {
        val current = editor ?: return
        val stationList = stations.toList()
        val origin = resolveStation(current.originQuery, current.originStationId, stationList)
        val destination = resolveStation(current.destinationQuery, current.destinationStationId, stationList)
        val departure = runCatching {
            PlannedJourneyDateTime(LocalDate.parse(current.date.trim()), LocalTime.parse(current.time.trim()), ZoneId.of(current.timeZoneId))
        }.getOrNull()
        if (origin == null || destination == null || departure == null) {
            editor = current.copy(validationMessage = UiMessage(UiMessageKey.PLANNED_JOURNEY_EDITOR_INVALID))
            return
        }
        val checklist = checklistLines(current.checklistText)
            .mapIndexed { index, label -> PlannedJourneyChecklistItem("check-${index + 1}", label) }
        val recurrence = current.repeatCount.takeIf { it > 1 }?.let { count ->
            PlannedJourneyRecurrence(
                weekdays = (current.repeatWeekdays + departure.date.dayOfWeek).sortedBy { it.value },
                startsOn = departure.date,
                endsOn = current.repeatEndsOn?.takeUnless { it.isBefore(departure.date) },
                occurrenceLimit = count.coerceAtMost(PlannedJourneyPersistenceSchema.MAX_RECURRENCE_OCCURRENCES)
            )
        }
        val existing = current.journeyId?.let { id -> snapshot.journeys.firstOrNull { it.id == id } }
        val departureChanged = existing == null || existing.itinerary.segments.first().departure != departure
        val departed = departure.isValidLocalTime() && departure.scheduledAt().toInstant().isBefore(now())
        if (departed && departureChanged && !current.pastDepartureConfirmed) {
            // A journey saved in the past goes straight to the archive with no reminder: say so once, save on the second tap.
            editor = current.copy(validationMessage = UiMessage(UiMessageKey.PLANNED_JOURNEY_DEPARTURE_PAST), pastDepartureConfirmed = true)
            return
        }
        val candidate = if (existing == null) {
            PlannedJourneyFactory.create(
                PlannedJourneyDraft(
                    id = newId(),
                    label = current.label,
                    itinerary = PlannedJourneyItineraryDraft(
                        listOf(PlannedJourneySegmentDraft("segment-1", origin, destination, departure))
                    ),
                    reminder = current.reminderMinutes?.let(::PlannedJourneyReminder),
                    recurrence = recurrence,
                    checklist = checklist,
                    createdAt = now()
                )
            ).let { result ->
                when (result) {
                    is PlannedJourneyValidationResult.Valid -> result.value
                    is PlannedJourneyValidationResult.Invalid -> {
                        editor = current.copy(validationMessage = UiMessage(invalidDraftMessage(result.issues)))
                        return
                    }
                }
            }
        } else {
            existing.copy(
                label = current.label.trim(),
                itinerary = PlannedJourneyItinerary(
                    listOf(existing.itinerary.segments.first().copy(origin = origin, destination = destination, departure = departure)) +
                        existing.itinerary.segments.drop(1)
                ),
                reminder = current.reminderMinutes?.let(::PlannedJourneyReminder),
                recurrence = recurrence,
                // A completed occurrence stays only while the new recurrence still generates its date.
                completedOccurrenceDates = recurrence?.let(PlannedJourneyOccurrences::dates).orEmpty()
                    .let { dates -> existing.completedOccurrenceDates.filter { it in dates } },
                checklist = checklist
            )
        }
        persist(repository.save(candidate), UiMessageKey.PLANNED_JOURNEY_SAVE_SUCCESS, inEditor = true) { editor = null }
    }

    /** Removes every journey (readable or not), their reminders and the templates; the default lead stays. */
    fun clearAll() {
        val result = repository.clearAll()
        if (result is PlannedJourneyRepositorySaveResult.Saved) {
            runCatching { templateStore?.write(null) }.onSuccess { templates = emptyList() }
            readOnly = false
            editor = null
            textImport = null
            importState = PlannedJourneyImportState.Idle
        }
        persist(result, UiMessageKey.PLANNED_JOURNEY_CLEARED)
    }

    fun complete(entry: PlannedJourneyAgendaEntry) {
        val result = if (entry.journey.recurrence == null) repository.complete(entry.journey.id, now())
        else repository.completeOccurrence(entry.journey.id, entry.occurrence.occurrenceDate)
        persist(result, UiMessageKey.PLANNED_JOURNEY_COMPLETE_SUCCESS)
    }

    fun archive(journeyId: String) {
        persist(repository.archive(journeyId, now()), UiMessageKey.PLANNED_JOURNEY_ARCHIVE_SUCCESS)
    }

    fun remove(journeyId: String) {
        persist(repository.remove(journeyId), UiMessageKey.PLANNED_JOURNEY_DELETE_SUCCESS)
    }

    fun toggleChecklist(journeyId: String, item: PlannedJourneyChecklistItem) {
        persist(repository.updateChecklistItem(journeyId, item.id, !item.completed), UiMessageKey.PLANNED_JOURNEY_CHECKLIST_SUCCESS)
    }

    fun previewImport(content: String, isIcs: Boolean, stations: List<PlannedJourneyStation> = emptyList()) {
        if (isIcs && PlannedJourneyForeignIcs.isForeign(content)) {
            previewForeignCalendar(content, stations)
            return
        }
        importState = if (isIcs) {
            when (val result = repository.previewIcsImport(content)) {
                is PlannedJourneyIcsImportResult.Preview -> PlannedJourneyImportState.Ics(result.preview)
                is PlannedJourneyIcsImportResult.Invalid -> PlannedJourneyImportState.Error(UiMessage(UiMessageKey.PLANNED_JOURNEY_IMPORT_INVALID))
            }
        } else {
            when (val result = repository.previewJsonImport(content)) {
                is PlannedJourneyJsonImportResult.Preview -> PlannedJourneyImportState.Json(result.preview)
                is PlannedJourneyJsonImportResult.Invalid -> PlannedJourneyImportState.Error(UiMessage(UiMessageKey.PLANNED_JOURNEY_IMPORT_INVALID))
            }
        }
    }

    /**
     * Another app's calendar: its route events become the same reviewed, add-only proposal as a pasted text, never
     * replacing a journey; Binario's own ICS export keeps its integrity preview.
     */
    private fun previewForeignCalendar(content: String, stations: List<PlannedJourneyStation>) {
        val calendar = PlannedJourneyForeignIcs.read(content)
        if (calendar == null) {
            importState = PlannedJourneyImportState.Error(UiMessage(UiMessageKey.PLANNED_JOURNEY_IMPORT_INVALID))
            return
        }
        val preview = PlannedJourneyTextImport.previewLines(calendar.lines, calendar.skipped, stations, snapshot.journeys, zone, now(), newId)
        textImport = PlannedJourneyTextImportState(
            candidates = preview.candidates.takeIf { it.isNotEmpty() },
            selected = preview.candidates.mapTo(mutableSetOf()) { it.id },
            skipped = preview.skipped,
            message = if (preview.candidates.isEmpty()) UiMessage(UiMessageKey.PLANNED_JOURNEY_TEXT_IMPORT_NONE) else null,
            fromCalendar = true
        )
        feedback = null
    }

    fun applyJsonImport(policy: PlannedJourneyConflictPolicy) {
        val current = importState as? PlannedJourneyImportState.Json ?: return
        persist(repository.applyJsonPreview(current.preview, policy), UiMessageKey.PLANNED_JOURNEY_IMPORT_SUCCESS) { importState = PlannedJourneyImportState.Idle }
    }

    fun dismissImportPreview() {
        importState = PlannedJourneyImportState.Idle
    }

    fun prepareExport(format: PlannedJourneyExportFormat) {
        pendingExport = format
        savedStateHandle[PENDING_EXPORT_KEY] = format.name
    }
    /** Consume only the small descriptor; export bytes are regenerated after the SAF result. */
    fun consumePendingExport(): PlannedJourneyExportFormat? {
        val format = pendingExport
        pendingExport = null
        savedStateHandle.remove<String>(PENDING_EXPORT_KEY)
        return format
    }
    fun export(format: PlannedJourneyExportFormat, icsText: PlannedJourneyIcsText = PlannedJourneyIcsText.ITALIAN): String = when (format) {
        PlannedJourneyExportFormat.JSON -> repository.exportJson()
        PlannedJourneyExportFormat.ICS -> repository.exportIcs(now(), icsText)
    }

    fun note(message: UiMessage) {
        feedback = message
    }

    private fun persist(
        result: PlannedJourneyRepositorySaveResult,
        success: UiMessageKey,
        /** A refusal is shown inside the open editor, not behind it. */
        inEditor: Boolean = false,
        afterSuccess: () -> Unit = {}
    ) {
        when (result) {
            is PlannedJourneyRepositorySaveResult.Saved -> {
                snapshot = result.snapshot
                warning = null
                feedback = UiMessage(success)
                afterSuccess()
                refreshReminders()
            }
            is PlannedJourneyRepositorySaveResult.Rejected -> {
                val message = UiMessage(rejectionMessage(result.reason))
                val openEditor = editor
                if (inEditor && openEditor != null) editor = openEditor.copy(validationMessage = message)
                else feedback = message
            }
        }
    }

    private fun rejectionMessage(reason: PlannedJourneyRejection): UiMessageKey = when (reason) {
        PlannedJourneyRejection.READ_ONLY -> UiMessageKey.PLANNED_JOURNEY_READ_ONLY
        PlannedJourneyRejection.FULL -> UiMessageKey.PLANNED_JOURNEY_FULL
        PlannedJourneyRejection.DUPLICATE -> UiMessageKey.PLANNED_JOURNEY_DUPLICATE
        PlannedJourneyRejection.INVALID -> UiMessageKey.PLANNED_JOURNEY_ACTION_REJECTED
    }

    /** The field the user can fix, when one issue names it; otherwise the generic refusal. */
    private fun invalidDraftMessage(issues: List<PlannedJourneyValidationIssue>): UiMessageKey = when {
        issues.any { it.code == PlannedJourneyValidationCode.SAME_ORIGIN_AND_DESTINATION } -> UiMessageKey.ROUTE_STATIONS_MUST_DIFFER
        else -> UiMessageKey.PLANNED_JOURNEY_ACTION_REJECTED
    }

    private fun checklistLines(text: String): List<String> = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .take(PlannedJourneyPersistenceSchema.MAX_CHECKLIST_ITEMS)
        .toList()

    private fun resolveStation(query: String, selectedId: String?, stations: List<PlannedJourneyStation>): PlannedJourneyStation? {
        val normalized = query.trim()
        if (selectedId != null) return stations.singleOrNull {
            it.canonicalId == selectedId && it.displayName.equals(normalized, ignoreCase = true)
        }
        return stations.singleOrNull { it.canonicalId.equals(normalized, ignoreCase = true) }
            ?: stations.filter { it.displayName.equals(normalized, ignoreCase = true) }.distinctBy { it.canonicalId }.singleOrNull()
    }
    companion object {
        internal const val AGENDA_FILTER_KEY = "plannedJourney.agendaFilter"
        internal const val PENDING_EXPORT_KEY = "plannedJourney.pendingExport"
        /** The id of a return or replan draft is never stored: the saved journey gets a new one. */
        private const val DRAFT_ID = "draft"
        fun factory(context: Context) = viewModelFactory {
            initializer {
                PlannedJourneyViewModel(
                    repository = PlannedJourneyRepository(SharedPreferencesPlannedJourneyStorage(context.applicationContext)),
                    savedStateHandle = createSavedStateHandle(),
                    reminders = AndroidPlannedJourneyReminders.controller(context.applicationContext),
                    preferences = SharedPreferencesPlannedJourneyPreferences(context.applicationContext),
                    templateStore = SharedPreferencesPlannedJourneyTemplateStore(context.applicationContext)
                )
            }
        }
    }
}
