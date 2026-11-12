package it.binario.app.data.reliability

import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.widget.ReliabilityMarks
import java.io.File
import java.io.StringReader
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * contracts/shared-rules/v1: the train-number keys and the statistics rules Android, iPhone and Alexa must share,
 * read from the same file by the three test suites.
 */
class SharedRulesContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val contract = File(repositoryRoot, "contracts/shared-rules/v1")
    private val rules = JSONObject(File(contract, "rules-v1.json").readText(Charsets.UTF_8))

    @Test fun `train numbers give the shared statistics key and live query`() {
        val cases = rules.getJSONArray("trainNumbers")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val published = case.getString("published")
            assertEquals(published, case.getString("key"), NetworkReliability.numberKey(published))
            val query = TrainNumberQuery(published, LocalDate.of(2026, 11, 12))
            assertEquals(published, case.getString("query"), query.normalized)
            assertEquals(published, case.optString("queryNumeric").takeUnless { case.isNull("queryNumeric") }, query.numeric)
            assertEquals(published, case.getBoolean("queryValid"), query.valid)
        }
    }

    @Test fun `on-time limits and the minimum samples are the shared ones`() {
        val statistics = rules.getJSONObject("statistics")
        assertEquals(statistics.getInt("minimumSamples"), NetworkReliability.MIN_SAMPLES)
        val limits = statistics.getJSONArray("onTimeLimitMinutes")
        for (index in 0 until limits.length()) {
            val case = limits.getJSONObject(index)
            val category = case.getString("category")
            assertEquals("«$category»", case.getInt("minutes"), NetworkReliability.onTimeLimitMinutes(category))
        }
    }

    @Test fun `a train without origin is measured at its last stop as the other readers measure it`() {
        val statistics = rules.getJSONObject("statistics")
        val document = File(contract, statistics.getString("document")).readText(Charsets.UTF_8)
        val stats = requireNotNull(NetworkReliabilityParser.parse(StringReader(document))) { "the shared statistics fixture did not parse" }
        val cases = statistics.getJSONArray("lastStopWithoutOrigin")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val number = case.getString("number")
            // Android and iPhone share one rule; a case where Alexa differs carries both («divergent»).
            val key = if (case.has("divergent")) "app" else "expected"
            val stop = ReliabilityMarks.favouriteStop(stats, number, null)
            if (case.isNull(key)) {
                assertNull("«$number»", stop)
                continue
            }
            val expected = case.getJSONObject(key)
            val actual = requireNotNull(stop) { "«$number» has no figures" }
            assertEquals(number, expected.getInt("samples"), actual.samples)
            assertEquals(number, expected.getInt("onTime"), actual.onTime)
            assertEquals(number, expected.getInt("limitMinutes"), actual.onTimeLimitMinutes)
            assertEquals(number, expected.getInt("daysObserved"), actual.daysObserved)
            assertEquals(number, expected.getInt("daysCancelled"), actual.daysCancelled)
            // A cancelled day counts as a day not on time.
            val daysOnTime = expected.getJSONObject("daysOnTime")
            assertEquals(number, daysOnTime.getInt("part").toDouble() / daysOnTime.getInt("whole"), actual.score, 1e-9)
        }
    }
}
