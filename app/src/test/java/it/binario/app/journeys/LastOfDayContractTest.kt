package it.binario.app.journeys

import it.binario.app.data.journey.StaticDayDepartures
import it.binario.app.data.journey.StaticDeparture
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * contracts/shared-rules/v1/last-of-day-v1.json, read as the iPhone's LastOfDayContractTests read it: «Ultimo di oggi»
 * and «primo domani alle …» from the static timetable's day.
 */
class LastOfDayContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/last-of-day-v1.json").readText(Charsets.UTF_8))

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)

    private fun departure(value: JSONObject): StaticDeparture =
        StaticDeparture(LocalDateTime.parse(value.getString("departure")), value.text("publicNumber"), value.getString("terminus"))

    private fun day(case: JSONObject): StaticDayDepartures? = if (case.isNull("day")) null else case.getJSONObject("day").let { day ->
        StaticDayDepartures(
            day.getJSONArray("lastByTerminus").objects().map(::departure),
            if (day.isNull("firstNextDay")) null else departure(day.getJSONObject("firstNextDay"))
        )
    }

    @Test fun `is last`() {
        val cases = rules.getJSONArray("isLast").objects()
        assertEquals(9, cases.size)
        cases.forEach { case ->
            val found = LastOfDayMarks.isLast(day(case), case.text("publicNumber"), case.text("scheduledDeparture")?.let(LocalDateTime::parse))
            assertEquals(case.getString("name"), case.getBoolean("expected"), found)
        }
    }

    @Test fun `first of the next day`() {
        val cases = rules.getJSONArray("firstNextDay").objects()
        assertEquals(6, cases.size)
        cases.forEach { case ->
            val found = LastOfDayMarks.firstNextDay(day(case), LocalDateTime.parse(case.getString("now")))
            assertEquals(case.getString("name"), case.text("expected")?.let(LocalDateTime::parse), found?.departure)
        }
    }

    @Test fun `marked day`() {
        val cases = rules.getJSONArray("markedDay").objects()
        assertEquals(4, cases.size)
        cases.forEach { case ->
            val found = LastOfDayMarks.markedDay(
                LocalDate.parse(case.getString("today")), case.getBoolean("followsCurrentTime"), LocalDate.parse(case.getString("searchDate"))
            )
            assertEquals(case.getString("name"), case.text("expected")?.let(LocalDate::parse), found)
        }
    }
}
