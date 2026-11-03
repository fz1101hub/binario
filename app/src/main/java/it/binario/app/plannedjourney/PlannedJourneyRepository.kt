package it.binario.app.plannedjourney

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.time.Instant

/**
 * Small local-only persistence boundary for [PlannedJourney]. The payload deliberately uses the
 * transfer codec so the bytes stored on the device have the same bounded, versioned schema as a
 * user-exported JSON document. This repository neither observes nor writes SavedRoute data.
 */
interface PlannedJourneyStorage {
    fun read(): String?
    fun write(value: String)
}

class SharedPreferencesPlannedJourneyStorage(context: Context) : PlannedJourneyStorage {
    private val contextForReminders = context.applicationContext
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        "binario_planned_journey_store_v1",
        Context.MODE_PRIVATE
    )

    override fun read(): String? = preferences.getString(PlannedJourneyPersistenceSchema.STORAGE_KEY, null)

    // KTX edit(commit = true) discards commit's success flag, needed before changing device alarms.
    @SuppressLint("UseKtx")
    override fun write(value: String) {
        synchronized(AndroidPlannedJourneyReminders.gate) {
            check(preferences.edit().putString(PlannedJourneyPersistenceSchema.STORAGE_KEY, value).commit())
            AndroidPlannedJourneyReminders.requestRefresh(contextForReminders)
        }
    }
}

data class PlannedJourneyRepositoryRead(
    val snapshot: PlannedJourneyPersistenceSnapshot,
    /** A corrupt or unsupported local payload is left untouched; the user may still export/import safely. */
    val warning: String? = null
)

sealed interface PlannedJourneyRepositorySaveResult {
    data class Saved(val snapshot: PlannedJourneyPersistenceSnapshot) : PlannedJourneyRepositorySaveResult
    data class Rejected(val detail: String) : PlannedJourneyRepositorySaveResult
}

sealed interface PlannedJourneyJsonImportResult {
    data class Preview(val preview: PlannedJourneyImportPreview) : PlannedJourneyJsonImportResult
    data class Invalid(val detail: String) : PlannedJourneyJsonImportResult
}

data class PlannedJourneyIcsImportPreview(
    val matchingEvents: List<PlannedJourneyCalendarEvent>,
    val conflictingEvents: List<PlannedJourneyCalendarEvent>,
    val unlinkedEvents: List<PlannedJourneyCalendarEvent>
)

sealed interface PlannedJourneyIcsImportResult {
    data class Preview(val preview: PlannedJourneyIcsImportPreview) : PlannedJourneyIcsImportResult
    data class Invalid(val detail: String) : PlannedJourneyIcsImportResult
}

class PlannedJourneyRepository(private val storage: PlannedJourneyStorage) {
    fun read(): PlannedJourneyRepositoryRead {
        val encoded = storage.read().orEmpty()
        if (encoded.isBlank()) return PlannedJourneyRepositoryRead(emptySnapshot())
        return when (val decoded = PlannedJourneyTransfer.decode(encoded)) {
            is PlannedJourneyTransferDecodeResult.Valid -> PlannedJourneyRepositoryRead(decoded.snapshot)
            is PlannedJourneyTransferDecodeResult.Invalid -> PlannedJourneyRepositoryRead(
                snapshot = emptySnapshot(),
                warning = "I viaggi pianificati salvati sul dispositivo non sono leggibili: ${decoded.errors.first().detail}"
            )
        }
    }

    fun save(journey: PlannedJourney): PlannedJourneyRepositorySaveResult {
        val current = read().snapshot
        if (PlannedJourneyValidator.validate(journey).isNotEmpty()) {
            return PlannedJourneyRepositorySaveResult.Rejected("Il viaggio non rispetta le regole di pianificazione.")
        }
        val candidate = PlannedJourneyPersistenceSnapshot(
            journeys = current.journeys.filterNot { it.id == journey.id } + journey
        )
        return writeValidated(candidate)
    }

    fun updateChecklistItem(journeyId: String, itemId: String, completed: Boolean): PlannedJourneyRepositorySaveResult {
        val current = read().snapshot
        val journey = current.journeys.firstOrNull { it.id == journeyId }
            ?: return PlannedJourneyRepositorySaveResult.Rejected("Viaggio pianificato non trovato.")
        val item = journey.checklist.firstOrNull { it.id == itemId }
            ?: return PlannedJourneyRepositorySaveResult.Rejected("Voce della checklist non trovata.")
        return save(journey.copy(checklist = journey.checklist.map { if (it.id == item.id) it.copy(completed = completed) else it }))
    }

    fun complete(journeyId: String, completedAt: Instant): PlannedJourneyRepositorySaveResult = transform(journeyId) { journey ->
        PlannedJourneyLifecycle.complete(journey, completedAt)
    }

    fun completeOccurrence(
        journeyId: String,
        occurrenceDate: java.time.LocalDate
    ): PlannedJourneyRepositorySaveResult = transform(journeyId) { journey ->
        PlannedJourneyLifecycle.completeOccurrence(journey, occurrenceDate)
    }

    fun archive(journeyId: String, archivedAt: Instant): PlannedJourneyRepositorySaveResult = transform(journeyId) { journey ->
        PlannedJourneyLifecycle.archive(journey, archivedAt)
    }

    fun remove(journeyId: String): PlannedJourneyRepositorySaveResult {
        val current = read().snapshot
        if (current.journeys.none { it.id == journeyId }) return PlannedJourneyRepositorySaveResult.Rejected("Viaggio pianificato non trovato.")
        return writeValidated(current.copy(journeys = current.journeys.filterNot { it.id == journeyId }))
    }

    fun exportJson(): String = PlannedJourneyTransfer.encode(read().snapshot)

    fun exportIcs(
        generatedAt: Instant,
        text: PlannedJourneyIcsText = PlannedJourneyIcsText.ITALIAN
    ): String = PlannedJourneyIcs.export(read().snapshot.journeys, generatedAt, text)

    fun previewJsonImport(encoded: String): PlannedJourneyJsonImportResult = when (val decoded = PlannedJourneyTransfer.decode(encoded)) {
        is PlannedJourneyTransferDecodeResult.Valid -> PlannedJourneyJsonImportResult.Preview(
            PlannedJourneyTransfer.preview(decoded.snapshot, read().snapshot.journeys)
        )
        is PlannedJourneyTransferDecodeResult.Invalid -> PlannedJourneyJsonImportResult.Invalid(decoded.errors.joinToString(" ") { it.detail })
    }

    fun applyJsonPreview(
        preview: PlannedJourneyImportPreview,
        conflictPolicy: PlannedJourneyConflictPolicy
    ): PlannedJourneyRepositorySaveResult {
        val current = read().snapshot
        if (current.journeys.size + preview.accepted.size > PlannedJourneyPersistenceSchema.MAX_JOURNEYS) {
            return PlannedJourneyRepositorySaveResult.Rejected(
                "L'importazione supera il limite locale di ${PlannedJourneyPersistenceSchema.MAX_JOURNEYS} viaggi."
            )
        }
        return writeValidated(PlannedJourneyTransfer.merge(current.journeys, preview, conflictPolicy))
    }

    /**
     * ICS generated by Binario intentionally contains occurrence metadata, not sufficient fields
     * to reconstruct an editable journey. Its import is therefore an integrity/conflict preview;
     * JSON remains the restore format.
     */
    fun previewIcsImport(calendar: String): PlannedJourneyIcsImportResult = when (val parsed = PlannedJourneyIcs.parseOwnedCalendar(calendar)) {
        is PlannedJourneyIcsParseResult.Invalid -> PlannedJourneyIcsImportResult.Invalid(parsed.detail)
        is PlannedJourneyIcsParseResult.Valid -> {
            val existing = read().snapshot.journeys.flatMap { journey ->
                PlannedJourneyOccurrences.forJourney(journey).map { occurrence ->
                    Triple(journey, occurrence.occurrenceDate, occurrence)
                }
            }.associateBy { (_, date, occurrence) -> "${occurrence.sourceJourneyId}|$date" }
            val matching = mutableListOf<PlannedJourneyCalendarEvent>()
            val conflicts = mutableListOf<PlannedJourneyCalendarEvent>()
            val unlinked = mutableListOf<PlannedJourneyCalendarEvent>()
            parsed.events.forEach { event ->
                val current = existing["${event.journeyId}|${event.occurrenceDate}"]
                when {
                    current == null -> unlinked += event
                    current.third.scheduledAt == event.startsAt &&
                        current.first.reminder?.minutesBeforeDeparture?.minutes == event.reminderMinutes -> matching += event
                    else -> conflicts += event
                }
            }
            PlannedJourneyIcsImportResult.Preview(PlannedJourneyIcsImportPreview(matching, conflicts, unlinked))
        }
    }

    private fun transform(
        journeyId: String,
        operation: (PlannedJourney) -> PlannedJourney
    ): PlannedJourneyRepositorySaveResult {
        val journey = read().snapshot.journeys.firstOrNull { it.id == journeyId }
            ?: return PlannedJourneyRepositorySaveResult.Rejected("Viaggio pianificato non trovato.")
        return runCatching { operation(journey) }
            .fold(onSuccess = ::save, onFailure = { PlannedJourneyRepositorySaveResult.Rejected(it.message ?: "Operazione non disponibile.") })
    }

    private fun writeValidated(snapshot: PlannedJourneyPersistenceSnapshot): PlannedJourneyRepositorySaveResult {
        val issues = PlannedJourneyValidator.validate(snapshot)
        if (issues.isNotEmpty()) {
            return PlannedJourneyRepositorySaveResult.Rejected(issues.first().detail)
        }
        return runCatching {
            // encode performs the bounded byte-size check before a preference write.
            storage.write(PlannedJourneyTransfer.encode(snapshot))
            PlannedJourneyRepositorySaveResult.Saved(snapshot)
        }.getOrElse { error ->
            PlannedJourneyRepositorySaveResult.Rejected(error.message ?: "Impossibile salvare i viaggi pianificati.")
        }
    }

    private fun emptySnapshot() = PlannedJourneyPersistenceSnapshot(journeys = emptyList())
}
