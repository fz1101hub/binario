package it.binario.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataReconciliationTest {
    private val route = SavedRoute(
        id = "reconcile-route",
        name = "Riconciliazione",
        outwardOrigin = Station("roma-termini", "Roma Termini", "Roma"),
        outwardDestination = Station("napoli-centrale", "Napoli Centrale", "Napoli")
    )

    @Test
    fun `rileva conflitti di ritardo e binario mantenendo la snapshot migliore`() {
        val preferred = snapshot("Fonte primaria", 95, delay = 5, platform = "3")
        val secondary = snapshot("Gestore infrastruttura", 88, delay = 8, platform = "4")

        val reconciled = SnapshotReconciler.reconcile(listOf(secondary, preferred))!!

        assertEquals("Fonte primaria", reconciled.snapshot.providerName)
        assertEquals(2, reconciled.conflicts.size)
        assertTrue(SnapshotWarning.SOURCE_CONFLICT in reconciled.snapshot.qualityWarnings)
    }

    private fun snapshot(provider: String, score: Int, delay: Int, platform: String): RouteSnapshot = RouteSnapshot(
        route = route,
        direction = Direction.OUTBOUND,
        services = listOf(
            TrainService(
                id = "service",
                number = "123",
                operator = RailOperator.TRENITALIA_REGIONALE,
                trainCategory = TrainCategory.REGIONALE,
                origin = "Roma Termini",
                destination = "Napoli Centrale",
                scheduledDeparture = "10:00",
                expectedDeparture = "10:${delay.toString().padStart(2, '0')}",
                platform = platform,
                delayMinutes = delay,
                status = ServiceStatus.DELAYED,
                stops = emptyList()
            )
        ),
        providerName = provider,
        retrievedAtEpochMillis = 1_750_000_000_000L,
        expiresAtEpochMillis = 1_750_000_060_000L,
        freshness = Freshness.FRESH,
        confidence = DataConfidence.OPERATOR_PRIMARY,
        qualityScore = score
    )
}
