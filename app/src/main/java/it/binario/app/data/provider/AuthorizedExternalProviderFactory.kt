package it.binario.app.data.provider

import it.binario.app.data.live.AuthorizedDbTimetablesFetcher
import it.binario.app.data.live.AuthorizedSwissRealtimeFetcher
import it.binario.app.data.live.DbTimetablesStationBoardAdapter
import it.binario.app.data.live.SwissGtfsRealtimeAdapter

/**
 * Credentials are intentionally plain constructor inputs rather than Android
 * resources/BuildConfig fields. A caller may provide them from a personal
 * backend or secure runtime store; Binario never persists them here.
 */
data class ExternalRailCredentials(
    val swissApiKey: String? = null,
    val dbClientId: String? = null,
    val dbApiKey: String? = null
)

data class AuthorizedExternalProviders(
    val tripUpdates: List<TripUpdateProvider> = emptyList(),
    val alerts: List<AlertProvider> = emptyList(),
    val stationBoards: List<StationBoardProvider> = emptyList()
)

object AuthorizedExternalProviderFactory {
    fun create(credentials: ExternalRailCredentials): AuthorizedExternalProviders {
        val swiss = credentials.swissApiKey?.takeIf(String::isNotBlank)?.let { key ->
            SwissGtfsRealtimeAdapter(AuthorizedSwissRealtimeFetcher(key))
        }
        val db = if (!credentials.dbClientId.isNullOrBlank() && !credentials.dbApiKey.isNullOrBlank()) {
            DbTimetablesStationBoardAdapter(
                AuthorizedDbTimetablesFetcher(credentials.dbClientId, credentials.dbApiKey)
            )
        } else null

        return AuthorizedExternalProviders(
            tripUpdates = listOfNotNull(swiss),
            alerts = listOfNotNull(swiss),
            stationBoards = listOfNotNull(db)
        )
    }
}
