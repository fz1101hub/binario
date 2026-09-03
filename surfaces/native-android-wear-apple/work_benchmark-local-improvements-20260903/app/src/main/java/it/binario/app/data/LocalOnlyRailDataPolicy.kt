package it.binario.app.data

import it.binario.app.data.provider.AlertProvider
import it.binario.app.data.provider.TripUpdateProvider
import it.binario.app.data.provider.VehiclePositionProvider

/**
 * Production policy for the local-first build. Network adapters remain outside
 * the execution path until a documented provider is expressly authorised.
 */
object LocalOnlyRailDataPolicy {
    fun routeSources(): List<TrainDataSource> = emptyList()
    fun alertProviders(): List<AlertProvider> = emptyList()
    fun vehiclePositionProviders(): List<VehiclePositionProvider> = emptyList()
    fun tripUpdateProviders(): List<TripUpdateProvider> = emptyList()
}
