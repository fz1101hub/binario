package it.binario.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionFallbackTest {
    private val route = SavedRoute(
        id = "test-route",
        name = "Test",
        outwardOrigin = Station("origin", "Origine", "Roma"),
        outwardDestination = Station("destination", "Destinazione", "Torino")
    )

    @Test
    fun `repository senza fonti non inventa corse dimostrative`() = runBlocking {
        val result = RailDataRepository().loadRoute(route, Direction.OUTBOUND)

        assertTrue(result is DataResult.Failure)
    }
}
