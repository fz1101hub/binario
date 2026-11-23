package it.binario.app.journeys

import it.binario.app.data.core.LegMode
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.gtfs.journeyLegMode
import it.binario.app.data.journey.urbanLineName
import it.binario.app.ui.journeys.urbanLineRepeatsMode
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-rules/v1/journey-leg-vehicles-v1.json: what a timetable leg travels on and the label it shows
 * ([journeyLegMode], [JourneyLegVehicle.of]), the same on the iPhone (SharedRulesContractTests.swift).
 */
class JourneyLegVehiclesContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/journey-leg-vehicles-v1.json").readText(Charsets.UTF_8))

    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else getString(name)

    @Test fun `the coach packs are the packs whose buses are scheduled coaches`() {
        val listed = rules.getJSONArray("scheduledCoachPacks")
        val packs = (0 until listed.length()).map(listed::getString).toSet()
        assertEquals(EmbeddedGtfsPacks.all.filter { it.busRoutesAreScheduledCoaches }.map { it.sourceId.value }.toSet(), packs)
    }

    @Test fun `every shared leg has the shared mode, vehicle and label`() {
        val cases = rules.getJSONArray("legVehicles")
        assertTrue(cases.length() > 0)
        for (position in 0 until cases.length()) {
            val case = cases.getJSONObject(position)
            val pack = case.getString("pack")
            // Railway and city packs, those of personal builds too.
            val coaches = EmbeddedGtfsPacks.forBuild(personalSources = true).single { it.sourceId.value == pack }.busRoutesAreScheduledCoaches
            val label = "${case.getString("routeType")} $pack ${case.text("operator")} ${case.text("line")}: ${case.getString("why")}"
            val mode = journeyLegMode(case.getString("routeType"), coaches)
            assertEquals(label, case.text("mode"), mode?.name)
            if (mode == null) {
                assertNull(label, case.text("vehicle"))
                continue
            }
            val vehicle = JourneyLegVehicle.of(mode, case.text("operator"), case.text("line"))
            assertEquals(label, case.text("vehicle"), vehicle.name)
            assertEquals(label, case.text("androidLabel"), vehicle.resourceName)
        }
    }

    @Test fun `every shared city line is named as the rule says`() {
        val cases = rules.getJSONArray("urbanLines")
        assertTrue(cases.length() > 0)
        for (position in 0 until cases.length()) {
            val case = cases.getJSONObject(position)
            assertEquals(case.getString("why"), case.text("line"), urbanLineName(LegMode.valueOf(case.getString("mode")), case.getString("published")))
        }
    }

    @Test fun `a city line that repeats the mode is left out of the label`() {
        val cases = rules.getJSONArray("urbanLabels")
        assertTrue(cases.length() > 0)
        for (position in 0 until cases.length()) {
            val case = cases.getJSONObject(position)
            assertEquals(case.getString("why"), case.getBoolean("repeatsMode"), urbanLineRepeatsMode(case.getString("line"), case.getString("label")))
        }
    }
}
