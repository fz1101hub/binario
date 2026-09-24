package it.binario.app.wear

import it.binario.app.data.DataConfidence
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import java.time.LocalDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidWearSnapshotContractTest {
    @Test
    fun `encoder Android coincide con la fixture golden v1`() {
        val generatedAt = 1_893_484_800_000L
        val route = SavedRoute(
            id = "roma-termini-napoli-centrale",
            name = "Roma–Napoli",
            outwardOrigin = Station("roma-termini", "Roma Termini", "Roma"),
            outwardDestination = Station("napoli-centrale", "Napoli Centrale", "Napoli")
        )
        val snapshot = RouteSnapshot(
            route = route,
            direction = Direction.OUTBOUND,
            services = listOf(
                service("fr9505", "FR 9505", RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, "08:10", "08:18", "7", 8, ServiceStatus.DELAYED),
                service("ic701", "IC 701", RailOperator.TRENITALIA_LONG_DISTANCE, TrainCategory.INTERCITY, "08:35", "08:35", "9", 0, ServiceStatus.ON_TIME)
            ),
            providerName = "Trenitalia",
            retrievedAtEpochMillis = generatedAt,
            sourceObservedAtEpochMillis = 1_893_484_790_000L,
            expiresAtEpochMillis = 1_893_485_700_000L,
            freshness = Freshness.FRESH,
            confidence = DataConfidence.OPERATOR_PRIMARY,
            notice = "Dati operativi ricevuti dal telefono."
        )

        val actual = AndroidWearSnapshotContract.operational(snapshot, generatedAt).normalisedNumbers()
        val expected = fixture("android-to-wear-v1.json").normalisedNumbers()

        assertEquals(expected, actual)
    }

    @Test
    fun `encoder trims whitespace introduced by notice truncation`() {
        val generatedAt = 1_893_484_800_000L
        val snapshot = RouteSnapshot(
            route = SavedRoute(
                id = "roma-napoli",
                name = "Roma–Napoli",
                outwardOrigin = Station("roma", "Roma Termini", "Roma"),
                outwardDestination = Station("napoli", "Napoli Centrale", "Napoli")
            ),
            direction = Direction.OUTBOUND,
            services = emptyList(),
            providerName = "RFI",
            retrievedAtEpochMillis = generatedAt,
            sourceObservedAtEpochMillis = generatedAt,
            expiresAtEpochMillis = generatedAt + 60_000L,
            freshness = Freshness.AGING,
            confidence = DataConfidence.INFRASTRUCTURE_PRIMARY,
            notice = "x".repeat(179) + " parole oltre il limite"
        )

        val notice = AndroidWearSnapshotContract.operational(snapshot, generatedAt).getValue("notice") as String

        assertEquals(179, notice.length)
        assertFalse(notice.endsWith(" "))
    }

    private fun service(
        id: String,
        number: String,
        operator: RailOperator,
        category: TrainCategory,
        scheduled: String,
        expected: String,
        platform: String,
        delay: Int,
        status: ServiceStatus
    ) = TrainService(
        id = id,
        number = number,
        operator = operator,
        trainCategory = category,
        origin = "Roma Termini",
        destination = "Napoli Centrale",
        scheduledDeparture = scheduled,
        expectedDeparture = expected,
        platform = platform,
        delayMinutes = delay,
        status = status,
        stops = emptyList(),
        operationalStatusVerified = true,
        scheduledDepartureDateTime = LocalDateTime.of(2030, 1, 1, scheduled.substringBefore(':').toInt(), scheduled.substringAfter(':').toInt())
    )

    private fun fixture(name: String): Map<String, Any> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { key -> json.get(key) }
    }

    private fun Map<String, Any>.normalisedNumbers(): Map<String, Any> = mapValues { (_, value) ->
        if (value is Number) value.toLong() else value
    }
}
