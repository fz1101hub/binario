package it.binario.app.data

data class ServiceConflict(
    val serviceKey: String,
    val field: String,
    val observedValues: Set<String>,
    val providers: Set<String>
)

data class ReconciledSnapshot(
    val snapshot: RouteSnapshot,
    val conflicts: List<ServiceConflict>
)

/**
 * Riconcilia snapshot già acquisite senza generare nuove richieste. La fonte
 * vincente resta quella con punteggio più alto; l’eventuale discordanza non è
 * eliminata silenziosamente ma diventa un warning visibile alla UI.
 */
object SnapshotReconciler {
    fun reconcile(candidates: List<RouteSnapshot>): ReconciledSnapshot? {
        if (candidates.isEmpty()) return null
        val winner = candidates.maxWithOrNull(
            compareBy<RouteSnapshot> { it.qualityScore ?: Int.MIN_VALUE }
                .thenBy { it.sourceObservedAtEpochMillis }
        ) ?: return null
        val conflicts = detectConflicts(candidates)
        val enriched = if (conflicts.isEmpty()) winner else winner.copy(
            qualityWarnings = winner.qualityWarnings + SnapshotWarning.SOURCE_CONFLICT,
            notice = listOfNotNull(winner.notice, "Fonti concordanti non disponibili per ${conflicts.size} campo/i: verifica il canale ufficiale prima di viaggiare.").joinToString(" ")
        )
        return ReconciledSnapshot(enriched, conflicts)
    }

    fun detectConflicts(candidates: List<RouteSnapshot>): List<ServiceConflict> {
        val grouped = candidates.flatMap { snapshot ->
            snapshot.services.map { service -> SnapshotService(snapshot.providerName, service) }
        }.groupBy { it.key }
        return grouped.values.flatMap { group ->
            if (group.size < 2) emptyList() else buildList {
                conflict(group, "ritardo") { it.service.delayMinutes.toString() }?.let(::add)
                conflict(group, "binario") { it.service.platform.orEmpty() }?.let(::add)
                conflict(group, "stato") { it.service.status.name }?.let(::add)
            }
        }
    }

    private fun conflict(group: List<SnapshotService>, field: String, value: (SnapshotService) -> String): ServiceConflict? {
        val values = group.map(value).toSet()
        return if (values.size <= 1) null else ServiceConflict(
            serviceKey = group.first().key,
            field = field,
            observedValues = values,
            providers = group.map { it.provider }.toSet()
        )
    }

    private data class SnapshotService(val provider: String, val service: TrainService) {
        val key: String = "${service.number.uppercase()}|${service.scheduledDeparture}|${service.origin.uppercase()}"
    }
}
