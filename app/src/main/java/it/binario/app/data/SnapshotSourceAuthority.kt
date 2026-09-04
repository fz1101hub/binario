package it.binario.app.data

import it.binario.app.data.core.SourceId
import it.binario.app.data.sources.RailInformationCategory
import it.binario.app.data.sources.RailSourceSelectionPolicy

/**
 * Central authority gate for route snapshots.
 *
 * Coverage fallbacks are intentionally publishable when they are the only
 * available source, but they can never be promoted to the same quality band as
 * an official/operator/infrastructure source only because they are fresh and
 * structurally complete.
 */
object SnapshotSourceAuthority {
    private val sourceAliases = mapOf(
        "european-sleeper" to SourceId("european-sleeper-gtfs"),
        "european-sleeper-gtfs" to SourceId("european-sleeper-gtfs"),
        "delfi-gtfs-community" to SourceId("delfi-gtfs-community")
    )

    fun isCoverageFallback(snapshot: RouteSnapshot): Boolean =
        candidateSourceIds(snapshot).any(RailSourceSelectionPolicy::isCoverageFallback) ||
            snapshot.providerName.contains("fallback best-effort", ignoreCase = true)

    fun plannedAuthorityScore(snapshot: RouteSnapshot): Int? = candidateSourceIds(snapshot)
        .map { sourceId ->
            RailSourceSelectionPolicy.score(
                RailInformationCategory.PLANNED_ITINERARY,
                sourceId,
                snapshot.services.firstOrNull()?.operatorDisplayName
            )
        }
        .maxOrNull()

    private fun candidateSourceIds(snapshot: RouteSnapshot): Set<SourceId> = buildSet {
        snapshot.services.forEach { service ->
            service.id.substringBefore(':')
                .takeIf(String::isNotBlank)
                ?.let { raw -> add(sourceAliases[raw] ?: SourceId(raw)) }
        }
    }
}
