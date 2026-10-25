package it.binario.app.widget

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station

enum class WidgetTargetType { ROUTE }

/**
 * La configurazione appartiene alla singola istanza di widget. Una tratta conserva
 * entrambi gli estremi: non dipende quindi da tratte, preferiti o cataloghi dell’app.
 */
data class WidgetDisplayConfiguration(
    val targetType: WidgetTargetType,
    val targetId: String,
    val targetLabel: String,
    val displayName: String = "",
    val routeOrigin: Station? = null,
    val routeDestination: Station? = null
) {
    fun routeTarget(): SavedRoute? = if (targetType == WidgetTargetType.ROUTE && routeOrigin != null && routeDestination != null) {
        SavedRoute(targetId, targetLabel, routeOrigin, routeDestination)
    } else null

    fun matchesRoute(route: SavedRoute): Boolean =
        targetType == WidgetTargetType.ROUTE &&
            routeOrigin?.id == route.outwardOrigin.id &&
            routeDestination?.id == route.outwardDestination.id

    /** Applica snapshot/errori solo alla stessa tratta e alla direzione mostrata dall'istanza. */
    fun matchesRouteDirection(route: SavedRoute, direction: Direction, displayedDirection: Direction?): Boolean =
        matchesRoute(route) && (displayedDirection ?: Direction.OUTBOUND) == direction
}

fun WidgetDisplayConfiguration.widgetTitle(fallback: String = targetLabel): String = displayName.trim().ifBlank { fallback }

class WidgetConfigurationStore(context: Context) {
    private val preferences = context.getSharedPreferences("binario_widget_configuration_v1", Context.MODE_PRIVATE)

    fun read(widgetId: Int): WidgetDisplayConfiguration? = preferences.getString(key(widgetId), null)?.let(::decode)

    fun all(): List<Pair<Int, WidgetDisplayConfiguration>> = preferences.all.mapNotNull { (key, value) ->
        val widgetId = key.removePrefix("widget_").toIntOrNull() ?: return@mapNotNull null
        val configuration = (value as? String)?.let(::decode) ?: return@mapNotNull null
        widgetId to configuration
    }

    fun save(widgetId: Int, configuration: WidgetDisplayConfiguration) {
        preferences.edit { putString(key(widgetId), encode(configuration)) }
    }

    fun remove(widgetId: Int) {
        preferences.edit { remove(key(widgetId)) }
    }

    private fun key(widgetId: Int): String = "widget_$widgetId"

    private fun encode(value: WidgetDisplayConfiguration): String = (
        listOf(
            value.targetType.name,
            value.targetId,
            value.targetLabel,
            value.displayName
        ) + encodeStation(value.routeOrigin) + encodeStation(value.routeDestination)
        ).joinToString("|") { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }

    private fun decode(value: String): WidgetDisplayConfiguration? = runCatching {
        val parts = value.split("|").map { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }
        when (parts.size) {
            3 -> WidgetDisplayConfiguration(WidgetTargetType.valueOf(parts[0]), parts[1], parts[2])
            4 -> WidgetDisplayConfiguration(WidgetTargetType.valueOf(parts[0]), parts[1], parts[2], parts[3])
            12 -> WidgetDisplayConfiguration(
                targetType = WidgetTargetType.valueOf(parts[0]),
                targetId = parts[1],
                targetLabel = parts[2],
                displayName = parts[3],
                routeOrigin = stationOrNull(parts[4], parts[5], parts[6], parts[7]),
                routeDestination = stationOrNull(parts[8], parts[9], parts[10], parts[11])
            )
            22 -> WidgetDisplayConfiguration(
                targetType = WidgetTargetType.valueOf(parts[0]),
                targetId = parts[1],
                targetLabel = parts[2],
                displayName = parts[3],
                routeOrigin = stationOrNull(parts.subList(4, 13)),
                routeDestination = stationOrNull(parts.subList(13, 22))
            )
            else -> null
        }
    }.getOrNull()

    private fun stationOrNull(id: String, name: String, city: String, region: String): Station? =
        if (id.isBlank() || name.isBlank()) null else Station(id, name, city, region.ifBlank { null })

    private fun encodeStation(station: Station?): List<String> = listOf(
        station?.id.orEmpty(),
        station?.name.orEmpty(),
        station?.city.orEmpty(),
        station?.region.orEmpty(),
        station?.railwayCode.orEmpty(),
        station?.trenitaliaLocationId.orEmpty(),
        station?.aliases?.joinToString(ALIAS_SEPARATOR).orEmpty(),
        station?.latitude?.toString().orEmpty(),
        station?.longitude?.toString().orEmpty()
    )

    private fun stationOrNull(parts: List<String>): Station? {
        val id = parts[0]
        val name = parts[1]
        if (id.isBlank() || name.isBlank()) return null
        return Station(
            id = id,
            name = name,
            city = parts[2],
            region = parts[3].ifBlank { null },
            railwayCode = parts[4].ifBlank { null },
            trenitaliaLocationId = parts[5].ifBlank { null },
            aliases = parts[6].split(ALIAS_SEPARATOR).filter(String::isNotBlank),
            latitude = parts[7].toDoubleOrNull(),
            longitude = parts[8].toDoubleOrNull()
        )
    }

    private companion object {
        const val ALIAS_SEPARATOR = "\u001F"
    }
}
