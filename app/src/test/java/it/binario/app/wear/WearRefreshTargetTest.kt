package it.binario.app.wear

import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearRefreshTargetTest {
    private val origin = Station(
        id = "roma-termini",
        name = "Roma Termini",
        city = "Roma",
        region = "Lazio",
        railwayCode = "S08409",
        trenitaliaLocationId = "S08409",
        aliases = listOf("Termini"),
        latitude = 41.9,
        longitude = 12.5,
    )
    private val destination = Station(id = "napoli-centrale", name = "Napoli Centrale", city = "Napoli")

    @Test
    fun `the shared route survives the round trip with the identifiers the route sources need`() {
        val target = WearRefreshTarget(SavedRoute("route-1", "Casa, lavoro", origin, destination), Direction.RETURN)

        val decoded = WearRefreshTarget.decode(target.encode())

        assertEquals(target, decoded)
        assertEquals("Napoli Centrale → Roma Termini", decoded?.routeLabel)
    }

    @Test
    fun `a malformed or incomplete stored route is not refreshed`() {
        val encoded = WearRefreshTarget(SavedRoute("route-1", "Casa", origin, destination), Direction.OUTBOUND).encode()

        assertNull(WearRefreshTarget.decode("not json"))
        assertNull(WearRefreshTarget.decode(encoded.replace("\"OUTBOUND\"", "\"SIDEWAYS\"")))
        assertNull(WearRefreshTarget.decode(encoded.replace("\"route-1\"", "\"\"")))
        assertNull(WearRefreshTarget.decode("{\"id\":\"route-1\",\"name\":\"Casa\",\"direction\":\"OUTBOUND\"}"))
    }

    @Test
    fun `a watch refresh request reloads the route as work instead of reissuing the same item`() {
        val listener = source("WearRefreshRequestListenerService.kt")
        val publisher = source("WearSnapshotPublisher.kt")

        assertTrue(listener.contains("WearRefreshWorker.enqueue(applicationContext)"))
        assertTrue(listener.contains("WearSnapshotPublisher.refreshForWatch(applicationContext)"))
        assertTrue(listener.contains("ExistingWorkPolicy.KEEP"))
        assertFalse(listener.contains("republishLastSnapshot"))
        assertTrue(publisher.contains("is DataResult.Success -> publish(context, result.snapshot)"))
        assertTrue(publisher.contains("publishPayload(context, token, WearRefreshTarget(snapshot.route, snapshot.direction).encode()) { generatedAt ->"))
        // The payload is dated when it is written, after the capability check and the publication lock.
        assertTrue(publisher.contains("val payload = payloadAt(System.currentTimeMillis())\n            runWearSafely { putPayload(context, payload) }"))
        // Removed with the payload after a revocation (on enable and on clear) and by a payload without a route.
        assertEquals(3, Regex("remove\\(KEY_LAST_ROUTE\\)").findAll(publisher).count())
    }

    private fun source(name: String): String = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .firstOrNull(File::isDirectory)
        ?.resolve("it/binario/app/wear/$name")
        ?.readText()
        ?: error("Unable to locate $name from ${File(".").absolutePath}")
}
