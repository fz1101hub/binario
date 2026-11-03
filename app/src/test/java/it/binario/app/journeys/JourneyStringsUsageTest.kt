package it.binario.app.journeys

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every `journey_*` string of the journeys feature is shown somewhere: release lint treats an unused resource as an
 * error, and an unused notice usually means a state the screen forgot to explain.
 */
class JourneyStringsUsageTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `every journey string and plural is referenced by the app code`() {
        val catalog = File(repositoryRoot, "app/src/main/res/values/strings.xml").readText()
        val names = Regex("<(string|plurals) name=\"(journey_[a-z_]+)\"").findAll(catalog).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val code = File(repositoryRoot, "app/src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val unused = names.filterNot { (kind, name) -> Regex("R\\.$kind\\.$name\\b").containsMatchIn(code) }.map { it.second }
        assertEquals(emptyList<String>(), unused)
    }
}
