package it.binario.app

import java.io.File
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * contracts/shared-rules/v1/personal-punctuality-v1.json: the personal punctuality of a train from the observations of
 * this phone ([PunctualityHistory]), read as the iPhone's PersonalFeaturesContractTests read it.
 */
class PersonalPunctualityContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/personal-punctuality-v1.json").readText(Charsets.UTF_8))

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    @Test fun `train keys`() {
        rules.getJSONArray("trainKeys").objects().forEach { case ->
            assertEquals(case.getString("expected"), PunctualityHistory.trainKey(case.getString("input")))
        }
    }

    @Test fun `buckets`() {
        rules.getJSONArray("buckets").objects().forEach { case ->
            val observation = PunctualityObservation(
                LocalDate.of(2026, 11, 24), "2153", "", case.getInt("delayMinutes"), case.getBoolean("cancelled")
            )
            assertEquals(case.getString("expected"), PunctualityHistory.bucketOf(observation).name)
        }
    }

    @Test fun `statistics of a train`() {
        val cases = rules.getJSONArray("stats").objects()
        assertEquals(4, cases.size)
        cases.forEach { case ->
            val name = case.getString("name")
            // As read from a stored history: the case's entries are the JSON form of the history and of the backup.
            val history = PunctualityHistory.fromJson(case.getJSONArray("history"))
            val stats = PunctualityHistory.stats(history, case.getString("train"))
            val expected = case.optJSONObject("expected")
            if (expected == null) {
                assertNull(name, stats)
                return@forEach
            }
            requireNotNull(stats) { name }
            assertEquals(name, expected.getInt("count"), stats.count)
            assertEquals(name, expected.getInt("onTimePercent"), stats.onTimePercent)
            assertEquals(name, if (expected.isNull("averageDelayMinutes")) null else expected.getInt("averageDelayMinutes"), stats.averageDelayMinutes)
            assertEquals(name, expected.getInt("cancelledCount"), stats.cancelledCount)
            assertEquals(name, expected.getInt("latePercent"), stats.latePercent)
            val distribution = expected.getJSONObject("distribution")
            PunctualityBucket.entries.forEach { bucket ->
                assertEquals("$name ${bucket.name}", distribution.getInt(bucket.name), stats.distribution[bucket])
            }
            val worst = expected.optJSONObject("worstStop")
            if (worst == null) {
                assertNull(name, stats.worstStop)
            } else {
                assertEquals(name, worst.getString("station"), stats.worstStop?.station)
                assertEquals(name, worst.getInt("averageIncreaseMinutes"), stats.worstStop?.averageIncreaseMinutes)
                assertEquals(name, worst.getInt("samples"), stats.worstStop?.samples)
            }
            val dates = expected.getJSONArray("historyDates")
            assertEquals(name, (0 until dates.length()).map { dates.getString(it) }, stats.history.map { it.serviceDate.toString() })
        }
    }
}
