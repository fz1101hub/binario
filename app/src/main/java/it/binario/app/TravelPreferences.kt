package it.binario.app

import android.content.Context
import androidx.core.content.edit
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.provider.JourneyRanking
import it.binario.app.data.provider.TravelMode
import it.binario.app.data.provider.WalkingSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Preferenze di viaggio" (Impostazioni › Preferenze di viaggio): walking speed and longest walk, "Preferisci", the kinds
 * of service to prefer or avoid and "Solo percorsi accessibili in sedia a rotelle". Kept on this device only (and in
 * the local backup); sent with every journey search to the offline planner, never anywhere else.
 */
internal object TravelPreferencesCodec {
    fun toJson(value: JourneyPreferences): JSONObject = JSONObject().apply {
        put("walkingSpeed", value.walkingSpeed.name)
        put("maxWalkMinutes", value.maxWalkMinutes)
        put("ranking", value.ranking.name)
        put("preferredModes", JSONArray(value.preferredModes.map(TravelMode::name).sorted()))
        put("avoidedModes", JSONArray(value.avoidedModes.map(TravelMode::name).sorted()))
        put("wheelchairOnly", value.wheelchairOnly)
    }

    /** Unknown names and values fall back to the defaults, so a file from a later version still reads. */
    fun fromJson(value: JSONObject): JourneyPreferences {
        fun modes(key: String): Set<TravelMode> {
            val array = value.optJSONArray(key) ?: return emptySet()
            return (0 until array.length()).mapNotNullTo(linkedSetOf()) { index ->
                TravelMode.entries.firstOrNull { it.name == array.optString(index) }
            }
        }
        val preferred = modes("preferredModes")
        return JourneyPreferences(
            walkingSpeed = WalkingSpeed.entries.firstOrNull { it.name == value.optString("walkingSpeed") } ?: WalkingSpeed.NORMAL,
            maxWalkMinutes = value.optInt("maxWalkMinutes", JourneyPreferences.DEFAULT_MAX_WALK_MINUTES)
                .takeIf { it in JourneyPreferences.MAX_WALK_CHOICES } ?: JourneyPreferences.DEFAULT_MAX_WALK_MINUTES,
            ranking = JourneyRanking.entries.firstOrNull { it.name == value.optString("ranking") } ?: JourneyRanking.FASTEST,
            preferredModes = preferred,
            avoidedModes = modes("avoidedModes") - preferred,
            wheelchairOnly = value.optBoolean("wheelchairOnly", false)
        )
    }

    fun decode(raw: String?): JourneyPreferences =
        raw?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: JourneyPreferences()
}

/** SharedPreferences of the travel preferences: one JSON value ([TravelPreferencesCodec]). */
internal class TravelPreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("binario_travel_preferences", Context.MODE_PRIVATE)

    fun read(): JourneyPreferences = TravelPreferencesCodec.decode(prefs.getString(KEY, null))

    fun save(value: JourneyPreferences) {
        prefs.edit { putString(KEY, TravelPreferencesCodec.toJson(value).toString()) }
    }

    private companion object {
        const val KEY = "travel_preferences"
    }
}

/** The travel preferences of this process, read once; settings, results and the journey search observe it. */
internal object TravelPreferencesRepository {
    @Volatile private var state: MutableStateFlow<JourneyPreferences>? = null

    fun state(context: Context): StateFlow<JourneyPreferences> = flow(context).asStateFlow()

    fun current(context: Context): JourneyPreferences = flow(context).value

    fun update(context: Context, value: JourneyPreferences) {
        TravelPreferencesStore(context).save(value)
        flow(context).value = value
    }

    private fun flow(context: Context): MutableStateFlow<JourneyPreferences> = state ?: synchronized(this) {
        state ?: MutableStateFlow(TravelPreferencesStore(context).read()).also { state = it }
    }
}
