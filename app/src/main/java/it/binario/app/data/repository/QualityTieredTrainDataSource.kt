package it.binario.app.data.repository

import it.binario.app.data.DataQualityBand
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.SnapshotQualityAssessor
import it.binario.app.data.TrainDataSource

/**
 * Enforces source tiers at runtime instead of relying on list order or scores.
 *
 * Tier 1 contains official/operator/infrastructure/open-data planning. Tier 2
 * contains coverage fallbacks. Tier 2 is queried only when Tier 1 cannot build
 * the requested route at all. This guarantees that a fresh best-effort feed can
 * fill a blind spot without shadowing a slower or less fresh official source.
 */
class QualityTieredTrainDataSource(
    private val primary: TrainDataSource,
    private val coverageFallbacks: List<TrainDataSource>,
    private val clock: () -> Long = System::currentTimeMillis
) : TrainDataSource {
    override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
        loadRoute(route, direction, JourneyQuery.now())

    override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
        val primaryResult = primary.loadRoute(route, direction, query)
        if (primaryResult is DataResult.Success) return primaryResult
        if (coverageFallbacks.isEmpty()) return primaryResult

        val candidates = coverageFallbacks.mapNotNull { source ->
            when (val result = source.loadRoute(route, direction, query)) {
                is DataResult.Success -> withQuality(result.snapshot)
                is DataResult.Failure -> null
            }
        }
        return candidates.maxWithOrNull(
            compareBy<RouteSnapshot> { qualityRank(it.qualityBand) }
                .thenBy { it.qualityScore ?: Int.MIN_VALUE }
                .thenBy { it.sourceObservedAtEpochMillis }
        )?.let(DataResult::Success) ?: primaryResult
    }

    private fun withQuality(snapshot: RouteSnapshot): RouteSnapshot {
        val quality = SnapshotQualityAssessor.assess(snapshot, clock())
        return snapshot.copy(
            qualityScore = quality.score,
            qualityBand = quality.band,
            qualityWarnings = snapshot.qualityWarnings + quality.warnings
        )
    }

    private fun qualityRank(band: DataQualityBand): Int = when (band) {
        DataQualityBand.VERIFIED -> 4
        DataQualityBand.RELIABLE -> 3
        DataQualityBand.LIMITED -> 2
        DataQualityBand.NOT_PUBLISHABLE -> 1
        DataQualityBand.UNKNOWN -> 0
    }
}
