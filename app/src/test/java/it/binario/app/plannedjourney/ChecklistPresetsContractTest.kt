package it.binario.app.plannedjourney

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * contracts/shared-rules/v1/checklist-presets-v1.json: the lines of each «Preparazione del viaggio» preset and how a
 * preset is added to the editor's text, read from the same file by the iPhone tests.
 */
class ChecklistPresetsContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/checklist-presets-v1.json").readText(Charsets.UTF_8))

    /** `TIME_MARGIN` → `timeMargin`, the name the file and the iPhone use. */
    private fun shared(name: String): String = name.lowercase().split('_').mapIndexed { index, word ->
        if (index == 0) word else word.replaceFirstChar(Char::uppercase)
    }.joinToString("")

    private fun JSONArray.strings() = (0 until length()).map(::getString)

    @Test fun `the lines and the presets are the shared ones`() {
        assertEquals(rules.getJSONArray("lines").strings(), PlannedJourneyChecklistLine.entries.map { shared(it.name) })
        val presets = rules.getJSONArray("presets")
        assertEquals(presets.length(), PlannedJourneyChecklistPreset.entries.size)
        for (index in 0 until presets.length()) {
            val case = presets.getJSONObject(index)
            val preset = PlannedJourneyChecklistPreset.entries.single { shared(it.name) == case.getString("preset") }
            assertEquals(case.getString("preset"), case.getJSONArray("lines").strings(), preset.orderedLines().map { shared(it.name) })
        }
    }

    @Test fun `a preset is added to the text as on the iPhone`() {
        val labels = rules.getJSONObject("labels")
        val cases = rules.getJSONArray("apply")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val preset = PlannedJourneyChecklistPreset.entries.single { shared(it.name) == case.getString("preset") }
            val applied = PlannedJourneyChecklistPresets.apply(case.getString("text"), preset) { labels.getString(shared(it.name)) }
            assertEquals("case $index", case.getString("expected"), applied)
        }
    }
}
