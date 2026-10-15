package it.binario.app.plannedjourney

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

data class PlannedJourneyReminderPlan(
    val key: String,
    val departure: Instant,
    val trigger: Instant,
    val zone: ZoneId,
    val title: String,
    val origin: String,
    val destination: String
)

/** Device consent and delivery identifiers are deliberately absent from JSON/ICS transfers. */
data class PlannedJourneyReminderState(
    val enabled: Boolean = false,
    val registered: Set<String> = emptySet(),
    val delivered: Set<String> = emptySet()
)

interface PlannedJourneyReminderStateStore {
    fun read(): PlannedJourneyReminderState
    fun write(state: PlannedJourneyReminderState)
}

interface PlannedJourneyReminderDevice {
    fun schedule(plan: PlannedJourneyReminderPlan)
    /** Cancels both an alarm and any notification already displayed under this key. */
    fun cancel(key: String)
    fun notify(plan: PlannedJourneyReminderPlan)
}

data class PlannedJourneyReminderStatus(
    val enabled: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val failed: Boolean = false
)

interface PlannedJourneyReminderController {
    fun setEnabled(enabled: Boolean)
    fun refresh(): PlannedJourneyReminderStatus
}

object PlannedJourneyReminderPlanner {
    fun plans(snapshot: PlannedJourneyPersistenceSnapshot): List<PlannedJourneyReminderPlan> {
        if (PlannedJourneyValidator.validate(snapshot).isNotEmpty()) return emptyList()
        return snapshot.journeys.filter { it.status == PlannedJourneyStatus.ACTIVE && it.reminder != null }
            .flatMap { journey ->
                PlannedJourneyOccurrences.forJourney(journey)
                    .filter { it.occurrenceDate !in journey.completedOccurrenceDates }
                    .map { occurrence ->
                        val first = occurrence.itinerary.segments.first()
                        val last = occurrence.itinerary.segments.last()
                        val minutes = journey.reminder!!.minutesBeforeDeparture.minutes
                        val fields = listOf(journey.id, occurrence.occurrenceDate.toString(),
                            occurrence.scheduledAt.toString(), minutes.toString(), journey.label,
                            first.origin.canonicalId, first.origin.displayName,
                            last.destination.canonicalId, last.destination.displayName)
                        val digest = MessageDigest.getInstance("SHA-256")
                        fields.forEach { field ->
                            val bytes = field.toByteArray(Charsets.UTF_8)
                            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                            digest.update(bytes)
                        }
                        PlannedJourneyReminderPlan(
                            key = digest.digest().joinToString("") { "%02x".format(it) },
                            departure = occurrence.scheduledAt,
                            trigger = occurrence.scheduledAt.minusSeconds(minutes * 60L),
                            zone = first.departure.timeZone,
                            title = journey.label.ifBlank { "${first.origin.displayName} → ${last.destination.displayName}" },
                            origin = first.origin.displayName,
                            destination = last.destination.displayName
                        )
                    }
            }.distinctBy { it.key }.sortedWith(compareBy<PlannedJourneyReminderPlan> { it.trigger }.thenBy { it.key })
    }
}

/** Calls are serialized by the Android adapter with repository writes and consent changes. */
class PlannedJourneyReminderEngine(
    private val store: PlannedJourneyReminderStateStore,
    private val device: PlannedJourneyReminderDevice
) {
    fun refresh(
        snapshot: PlannedJourneyPersistenceSnapshot,
        allowed: Boolean,
        now: Instant,
        alarmsCleared: Boolean = false
    ): PlannedJourneyReminderStatus {
        val old = store.read()
        val plans = PlannedJourneyReminderPlanner.plans(snapshot)
        val live = plans.filter { it.departure > now }
        // Retain bounded delivery IDs while their journey exists, even across clock rollbacks.
        val delivered = old.delivered.intersect(plans.map { it.key }.toSet())
        // Preserve alarms already queued by Android, including simultaneous/late deliveries.
        // Reboot/update removes Android's queue: discard elapsed entries so they cannot reserve
        // all slots and starve future recurrences. Normal refresh must preserve queued deliveries.
        val queuedDue = if (alarmsCleared) emptySet() else old.registered.intersect(
            live.filter { it.trigger <= now && it.key !in delivered }.map { it.key }.toSet())
        val future = if (old.enabled && allowed) live.filter { it.trigger > now && it.key !in delivered }
            .take((MAX_PENDING - queuedDue.size).coerceAtLeast(0)) else emptyList()
        val retained = if (old.enabled && allowed) delivered.intersect(live.map { it.key }.toSet()) + queuedDue + future.map { it.key } else emptySet()
        var failed = false
        val registered = old.registered.toMutableSet()
        (registered - retained).forEach { key ->
            runCatching { device.cancel(key) }.onSuccess { registered.remove(key) }.onFailure { failed = true }
        }
        // Persist cleanup before adding more alarms. Failed cancellations remain repairable.
        store.write(old.copy(registered = registered.toSet(), delivered = delivered))
        val capacity = (PlannedJourneyReminderStateCodec.MAX_REGISTERED - registered.size).coerceAtLeast(0)
        val additions = future.filter { it.key !in registered }.take(capacity)
        val schedulable = future.filter { it.key in registered } + additions
        if (schedulable.size != future.size) failed = true
        registered += additions.map { it.key }
        // Journal before AlarmManager calls: a killed process cannot lose their identifiers.
        store.write(old.copy(registered = registered.toSet(), delivered = delivered))
        schedulable.forEach { plan ->
            runCatching { device.schedule(plan) }.onFailure { failed = true }
        }
        return PlannedJourneyReminderStatus(old.enabled, allowed, failed)
    }

    fun deliver(key: String, snapshot: PlannedJourneyPersistenceSnapshot, allowed: Boolean, now: Instant) {
        val state = store.read()
        if (state.enabled && allowed && key in state.registered && key !in state.delivered) {
            val current = PlannedJourneyReminderPlanner.plans(snapshot).firstOrNull { it.key == key }
            if (current != null && current.trigger <= now && current.departure > now) {
                // At most once, including a process death immediately after NotificationManager.
                store.write(state.copy(delivered = state.delivered + key))
                runCatching { device.notify(current) }
            }
        }
        refresh(snapshot, allowed, now)
    }

    companion object {
        // Leave room under Android's per-UID alarm limit for commuter/stop alarms.
        const val MAX_PENDING = 64
    }
}

object PlannedJourneyReminderStateCodec {
    const val MAX_REGISTERED = 1024
    private const val MAX_DELIVERED = PlannedJourneyPersistenceSchema.MAX_JOURNEYS *
        PlannedJourneyPersistenceSchema.MAX_RECURRENCE_OCCURRENCES
    private const val MAX_BYTES = 128 * 1024
    private val KEY = Regex("[0-9a-f]{64}")

    fun encode(state: PlannedJourneyReminderState): String {
        require(state.registered.size <= MAX_REGISTERED && state.delivered.size <= MAX_DELIVERED)
        require((state.registered + state.delivered).all { KEY.matches(it) })
        return JSONObject().put("version", 1).put("enabled", state.enabled)
            .put("registered", JSONArray(state.registered.sorted()))
            .put("delivered", JSONArray(state.delivered.sorted())).toString().also {
                require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            }
    }

    fun decode(value: String?): PlannedJourneyReminderState? = runCatching {
        if (value == null) return PlannedJourneyReminderState()
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val json = JSONObject(value)
        require(json.keys().asSequence().toSet() == setOf("version", "enabled", "registered", "delivered"))
        require(json.get("version") == 1 && json.get("enabled") is Boolean)
        fun keys(name: String, max: Int): Set<String> {
            val array = json.getJSONArray(name)
            require(array.length() <= max)
            return (0 until array.length()).map { index ->
                val key = array.get(index)
                require(key is String && KEY.matches(key))
                key
            }.toSet()
        }
        PlannedJourneyReminderState(json.getBoolean("enabled"), keys("registered", MAX_REGISTERED), keys("delivered", MAX_DELIVERED))
    }.getOrNull()
}
