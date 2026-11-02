package it.binario.app.ui.departures

import android.content.Context
import androidx.core.content.edit
import it.binario.app.data.TrainService
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.widget.TrainTypeGroup
import it.binario.app.widget.WidgetTrainTypes

/**
 * The train types hidden in Partenze, with the groups and the mapping of the widget settings ([WidgetTrainTypes]):
 * Frecce/AV, Intercity/notte, Regionali/suburbani, Italo, Bus, Internazionali. A train whose type the source does not
 * tell has no group and is never hidden.
 */
internal object PartenzeTrainTypes {
    /** Route board rows: category and operator, a replacement service or a scheduled coach of the timetable. */
    fun groupOf(service: TrainService): TrainTypeGroup? =
        if (service.scheduledBus) TrainTypeGroup.REPLACEMENT_BUS
        else WidgetTrainTypes.groupOf(service.trainCategory, service.operator, WidgetTrainTypes.isReplacement(service.routeChange?.kind))

    /** Station board rows: service kind, operator, line label and status; a scheduled coach counts as a bus. */
    fun groupOf(entry: StationBoardEntry): TrainTypeGroup? =
        if (entry.serviceKind == ServiceKind.BUS) TrainTypeGroup.REPLACEMENT_BUS
        else WidgetTrainTypes.groupOf(entry.serviceKind, entry.operatorId?.value, entry.lineLabel, entry.status?.value)

    fun <T> visible(items: List<T>, excluded: Set<TrainTypeGroup>, groupOf: (T) -> TrainTypeGroup?): List<T> =
        if (excluded.isEmpty()) items else items.filterNot { WidgetTrainTypes.isHidden(groupOf(it), excluded) }

    /** Persisted form: group names separated by commas; unknown names are ignored. */
    fun encode(excluded: Set<TrainTypeGroup>): String = TrainTypeGroup.entries.filter { it in excluded }.joinToString(",") { it.name }

    fun decode(value: String?): Set<TrainTypeGroup> = value.orEmpty().split(",")
        .mapNotNull { name -> TrainTypeGroup.entries.firstOrNull { it.name == name.trim() } }
        .toSet()
}

/** The excluded train types of each station, on this device only (one preference per station id). */
internal class TrainTypeExclusionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(stationId: String): Set<TrainTypeGroup> = PartenzeTrainTypes.decode(preferences.getString(key(stationId), null))

    fun save(stationId: String, excluded: Set<TrainTypeGroup>) {
        preferences.edit {
            if (excluded.isEmpty()) remove(key(stationId)) else putString(key(stationId), PartenzeTrainTypes.encode(excluded))
        }
    }

    private fun key(stationId: String) = "excluded:$stationId"

    private companion object {
        const val PREFERENCES = "binario_partenze_train_types"
    }
}
