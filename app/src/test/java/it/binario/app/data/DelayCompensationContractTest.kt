package it.binario.app.data

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * contracts/shared-rules/v1/delay-compensation-v1.json: when the train detail states the compensation of Regulation
 * (EU) 2021/782, read as the iPhone's ExtrasContractTests read it.
 */
class DelayCompensationContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/delay-compensation-v1.json").readText(Charsets.UTF_8))

    @Test fun `every case of the shared rule`() {
        val cases = rules.getJSONArray("cases")
        assertEquals(13, cases.length())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val stops = case.getJSONArray("stops").let { array -> (0 until array.length()).map(array::getJSONObject) }.map { stop ->
                TrainStop(
                    station = stop.getString("name"),
                    scheduled = "10:00",
                    expected = "10:00",
                    platform = null,
                    delayMinutes = stop.getInt("delayMinutes"),
                    completed = stop.getBoolean("arrived"),
                    routeChange = if (stop.getBoolean("skipped")) StopRouteChange.SKIPPED else StopRouteChange.NONE,
                    operationalStatusVerified = if (stop.isNull("verified")) null else stop.getBoolean("verified")
                )
            }
            val cancelled = case.getBoolean("cancelled")
            val service = TrainService(
                "viaggiatreno:S01700:2153:1", "2153", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE,
                stops.first().station, stops.last().station, "09:00", "09:00", null, stops.last().delayMinutes,
                if (cancelled) ServiceStatus.CANCELLED else ServiceStatus.DELAYED, stops,
                operationalStatusVerified = case.getBoolean("serviceVerified")
            )
            val actual = DelayCompensationRule.of(service, isDemonstration = case.getBoolean("demonstration"))
            val expected = case.optJSONObject("expected")?.let { due ->
                DelayCompensation(
                    due.getString("station"),
                    due.getInt("delayMinutes"),
                    CompensationShare.entries.single { it.percent == due.getInt("percent") }
                )
            }
            assertEquals(case.getString("name"), expected, actual)
        }
    }
}
