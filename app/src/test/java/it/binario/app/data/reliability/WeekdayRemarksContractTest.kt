package it.binario.app.data.reliability

import java.io.File
import java.io.StringReader
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-rules/v1/weekday-remarks-v1.json: the weekday remarks of a train's punctuality detail, the same on
 * Android and on the iPhone (`NetworkReliability.weekdayRemarks` in BinarioKit, SharedRulesContractTests.swift).
 */
class WeekdayRemarksContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val directory = File(repositoryRoot, "contracts/shared-rules/v1")
    private val contract = JSONObject(File(directory, "weekday-remarks-v1.json").readText(Charsets.UTF_8))

    @Test fun `every shared case gives the shared weekday remarks, at most two, the largest difference first`() {
        val document = File(directory, contract.getString("document")).readText(Charsets.UTF_8)
        val stats = requireNotNull(NetworkReliabilityParser.parse(StringReader(document))) {
            "the shared statistics of the weekday cases did not parse"
        }
        val cases = contract.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val number = case.getString("number")
            val stopIndex = case.getInt("stop")
            val train = stats.trainsNumbered(number).single()
            val stop = requireNotNull(NetworkReliability.at(train, stopIndex)) { "«$number» has no figures" }
            val expected = case.getJSONArray("expected").let { remarks ->
                (0 until remarks.length()).map { remarks.getJSONObject(it) }.map { Triple(it.getInt("weekday"), it.getInt("samples"), it.getInt("onTime")) }
            }
            val actual = NetworkReliability.weekdayRemarksShown(train, stopIndex, stop).map { Triple(it.weekday.value, it.samples, it.onTime) }
            assertEquals(case.getString("why"), expected, actual)
        }
    }
}
