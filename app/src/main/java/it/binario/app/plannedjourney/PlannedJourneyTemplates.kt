package it.binario.app.plannedjourney

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * A reusable journey: route, departure time, reminder lead and checklist lines, without a date (always chosen anew).
 * Stored on this device only, never in the journeys' JSON or ICS files.
 */
data class PlannedJourneyTemplate(
    val id: String,
    val name: String,
    val origin: PlannedJourneyStation,
    val destination: PlannedJourneyStation,
    val time: LocalTime,
    val reminder: PlannedJourneyReminderMinutes? = null,
    val checklist: List<String> = emptyList()
)

interface PlannedJourneyTemplateStore {
    fun read(): String?
    /** Null removes the stored templates. */
    fun write(value: String?)
}

class SharedPreferencesPlannedJourneyTemplateStore(context: Context) : PlannedJourneyTemplateStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        "binario_planned_journey_store_v1",
        Context.MODE_PRIVATE
    )

    override fun read(): String? = preferences.getString(KEY, null)

    override fun write(value: String?) {
        preferences.edit { if (value == null) remove(KEY) else putString(KEY, value) }
    }

    private companion object {
        const val KEY = "binario-planned-journey-templates-v1"
    }
}

object PlannedJourneyTemplates {
    const val MAX_TEMPLATES = 12
    const val MAX_NAME_LENGTH = 48
    private const val KIND = "binario_planned_journey_templates"
    private const val VERSION = 1
    private const val MAX_BYTES = 65_536

    /**
     * A template only when it would make a valid journey: two distinct catalogue stations, a name, and checklist lines
     * that pass the Agenda's checks (no credentials, payment, identity or contact data).
     */
    fun validated(template: PlannedJourneyTemplate): PlannedJourneyTemplate? {
        val name = template.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH || name.any { it.isISOControl() }) return null
        val probe = PlannedJourneyDraft(
            id = template.id,
            itinerary = PlannedJourneyItineraryDraft(listOf(PlannedJourneySegmentDraft(
                "segment-1", template.origin, template.destination,
                PlannedJourneyDateTime(LocalDate.of(2030, 1, 7), template.time, it.binario.app.data.RailTime.defaultZone)
            ))),
            reminder = template.reminder?.let(::PlannedJourneyReminder),
            checklist = template.checklist.mapIndexed { index, label -> PlannedJourneyChecklistItem("check-${index + 1}", label) },
            createdAt = Instant.EPOCH
        )
        return if (PlannedJourneyFactory.create(probe) is PlannedJourneyValidationResult.Valid) template.copy(name = name) else null
    }

    /** Newest first; a template with the same name (ignoring case) is replaced, and only the newest twelve are kept. */
    fun add(templates: List<PlannedJourneyTemplate>, template: PlannedJourneyTemplate): List<PlannedJourneyTemplate> =
        (listOf(template) + templates.filterNot { it.name.equals(template.name, ignoreCase = true) || it.id == template.id })
            .take(MAX_TEMPLATES)

    fun encode(templates: List<PlannedJourneyTemplate>): String = JSONObject()
        .put("kind", KIND)
        .put("schemaVersion", VERSION)
        .put("templates", JSONArray(templates.take(MAX_TEMPLATES).map { template ->
            JSONObject()
                .put("id", template.id)
                .put("name", template.name)
                .put("origin", station(template.origin))
                .put("destination", station(template.destination))
                .put("time", template.time.toString())
                .apply { template.reminder?.let { put("reminderMinutes", it.minutes) } }
                .put("checklist", JSONArray(template.checklist))
        }))
        .toString()

    /** The readable, valid templates of a stored document; anything else reads as no template. */
    fun decode(encoded: String?): List<PlannedJourneyTemplate> {
        if (encoded.isNullOrBlank() || encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) return emptyList()
        return try {
            val root = JSONObject(encoded)
            if (root.optString("kind") != KIND || root.optInt("schemaVersion") != VERSION) return emptyList()
            val array = root.optJSONArray("templates") ?: return emptyList()
            (0 until minOf(array.length(), MAX_TEMPLATES)).mapNotNull { index ->
                runCatching {
                    val value = array.getJSONObject(index)
                    val checklist = value.getJSONArray("checklist")
                    validated(PlannedJourneyTemplate(
                        id = value.getString("id"),
                        name = value.getString("name"),
                        origin = station(value.getJSONObject("origin")),
                        destination = station(value.getJSONObject("destination")),
                        time = LocalTime.parse(value.getString("time")),
                        reminder = if (value.has("reminderMinutes")) {
                            PlannedJourneyReminderMinutes.fromMinutes(value.getInt("reminderMinutes")) ?: return@runCatching null
                        } else null,
                        checklist = (0 until checklist.length()).map { checklist.getString(it) }
                    ))
                }.getOrNull()
            }.distinctBy { it.id }
        } catch (error: RuntimeException) {
            emptyList()
        }
    }

    private fun station(station: PlannedJourneyStation): JSONObject = JSONObject()
        .put("canonicalId", station.canonicalId)
        .put("displayName", station.displayName)
        .apply { station.railwayCode?.let { put("railwayCode", it) } }

    private fun station(value: JSONObject) = PlannedJourneyStation(
        canonicalId = value.getString("canonicalId"),
        displayName = value.getString("displayName"),
        railwayCode = if (value.has("railwayCode")) value.getString("railwayCode") else null
    )
}
