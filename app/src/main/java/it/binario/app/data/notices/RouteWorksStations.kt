package it.binario.app.data.notices

import android.content.Context
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.data.core.StationId
import it.binario.app.data.gtfs.directRunStations
import it.binario.app.data.provider.JourneyEndpoint
import it.binario.app.data.repository.BinarioDataGraph
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

/**
 * The stations a saved route's works are matched on, for the works sections and the evening alert: both ends and the
 * stations its direct trains call at on [day] in the embedded timetables; the ends alone when no train runs direct.
 * Read on the device only.
 */
object RouteWorksStations {
    suspend fun of(context: Context, route: SavedRoute, day: LocalDate): List<Station> {
        val origin = route.outwardOrigin
        val destination = route.outwardDestination
        val between = try {
            BinarioDataGraph.offlineJourneyPlanner(context.applicationContext).directRunStations(
                StationId(origin.id), origin.endpoint(), StationId(destination.id), destination.endpoint(), day
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            emptyList()
        }
        return (listOf(origin) + between + listOf(destination)).distinctBy(Station::id)
    }

    private fun Station.endpoint(): JourneyEndpoint? =
        name.takeIf(String::isNotBlank)?.let { JourneyEndpoint(name = it, aliases = aliases, railwayCode = railwayCode) }
}
