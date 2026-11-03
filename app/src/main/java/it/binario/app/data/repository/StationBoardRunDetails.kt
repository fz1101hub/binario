package it.binario.app.data.repository

import it.binario.app.data.Station
import it.binario.app.data.TrainStop
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RailProvider
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Details belong to the selected station/run, independently of the route planner. */
data class StationBoardRunDetails(
    val entry: StationBoardEntry,
    val origin: String,
    val destination: String,
    val stops: List<TrainStop>,
    val providerName: String,
    val observedAt: Instant?
)

interface StationBoardRunDetailProvider : RailProvider {
    suspend fun getStationBoardRun(station: Station, kind: BoardKind, entry: StationBoardEntry): ProviderResult<StationBoardRunDetails>
}

class StationBoardRunDetailService(private val providers: List<StationBoardRunDetailProvider>) {
    suspend fun load(station: Station, kind: BoardKind, entry: StationBoardEntry): StationBoardRunDetails? = withContext(Dispatchers.IO) {
        for (provider in providers.sortedByDescending { it.descriptor.id in entry.nativeRunIds }) {
            val result = try {
                withTimeout(12_000) { provider.getStationBoardRun(station, kind, entry) }
            } catch (_: TimeoutCancellationException) { continue }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { continue }
            when (result) {
                is ProviderResult.Success -> return@withContext result.value
                is ProviderResult.Partial -> return@withContext result.value
                else -> Unit
            }
        }
        null
    }
}
