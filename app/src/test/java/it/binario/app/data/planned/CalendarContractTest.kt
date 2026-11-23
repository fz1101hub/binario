package it.binario.app.data.planned

import java.io.File
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * contracts/shared-rules/v1/calendar-v1.json: the Italian holidays (the shared table and Easter), read from the same file
 * by the Alexa tests.
 */
class CalendarContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/calendar-v1.json").readText(Charsets.UTF_8))

    @Test fun `holidays are the shared ones`() {
        val cases = rules.getJSONArray("holidays")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val date = case.getString("date")
            assertEquals(date, case.getBoolean("holiday"), ItalianRailServiceCalendar.isHoliday(LocalDate.parse(date)))
        }
    }
}
