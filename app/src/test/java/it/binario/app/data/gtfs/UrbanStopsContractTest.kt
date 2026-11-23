package it.binario.app.data.gtfs

import it.binario.app.data.journey.GtfsJourneyPlannerSettings
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-rules/v1/urban-stops-v1.json: how a city stop's name is shown ([StopNameCase]), its badge in the
 * station search ([UrbanStopBadge]) and the minutes of a walk to it ([GtfsJourneyPlannerSettings.walkMinutes]), the
 * same in the iPhone index builder (tests/test_ios_gtfs_schedule.py) and, for the badges, on the iPhone
 * (SharedRulesContractTests.swift).
 */
class UrbanStopsContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/urban-stops-v1.json").readText(Charsets.UTF_8))

    private fun cases(name: String): List<JSONObject> = rules.getJSONArray(name).let { array -> (0 until array.length()).map(array::getJSONObject) }

    @Test fun `every shared stop name is shown as the rule says`() {
        val cases = cases("stopNames")
        assertTrue(cases.isNotEmpty())
        for (case in cases) assertEquals(case.getString("why"), case.getString("shown"), StopNameCase.display(case.getString("published")))
    }

    @Test fun `every shared badge is the badge of its modes`() {
        val cases = cases("badges")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val expected = if (case.isNull("badge")) null else case.getString("badge")
            assertEquals(case.getString("why"), expected, UrbanStopBadge.of(case.getString("modes"))?.letter)
        }
    }

    @Test fun `every shared walk takes the shared minutes`() {
        val cases = cases("walks")
        assertTrue(cases.isNotEmpty())
        val settings = GtfsJourneyPlannerSettings()
        for (case in cases) assertEquals(case.getString("why"), case.getInt("minutes"), settings.walkMinutes(case.getDouble("meters")))
    }
}
