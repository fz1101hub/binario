package it.binario.app.journeys

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * contracts/shared-rules/v1/results-paging-v1.json and contracts/shared-data/v1/results-paging-v1.json, read as the
 * iPhone's ResultsPagingContractTests read them: «Più presto» and «Più tardi» on synthetic sources.
 */
class ResultsPagingContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/results-paging-v1.json").readText(Charsets.UTF_8))
    private val day = LocalDate.of(2026, 11, 24)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.minutes(): List<Int> = (0 until length()).map { getInt(it) }

    private fun at(minutes: Int, on: LocalDate = day): LocalDateTime = on.atStartOfDay().plusMinutes(minutes.toLong())

    private fun dayOf(case: JSONObject): LocalDate = if (case.has("day")) LocalDate.parse(case.getString("day")) else day

    private fun loader(source: JSONObject, on: LocalDate = day): suspend (LocalDateTime) -> List<LocalDateTime>? {
        val departures = source.getJSONArray("departures").minutes().sorted().map { at(it, on) }
        val pageSize = source.getInt("pageSize")
        val fails = source.optBoolean("fails", false)
        val failsBefore = if (source.isNull("failsBefore")) null else at(source.getInt("failsBefore"), on)
        return { from ->
            if (fails || (failsBefore != null && from.isBefore(failsBefore))) null
            else departures.filter { !it.isBefore(from) }.take(pageSize)
        }
    }

    private fun expected(case: JSONObject): List<LocalDateTime>? =
        if (case.isNull("expected")) null else case.getJSONArray("expected").minutes().map { at(it, dayOf(case)) }

    @Test fun `the paging reads the shared table`() {
        val table = JSONObject(File(repositoryRoot, "contracts/shared-data/v1/results-paging-v1.json").readText(Charsets.UTF_8))
        assertEquals(table.getJSONArray("earlierSpansMinutes").minutes().map { it.toLong() }, ResultsPaging.EARLIER_SPANS_MINUTES)
        assertEquals(table.getInt("earlierPage"), ResultsPaging.EARLIER_PAGE)
        assertEquals(table.getInt("maximumFillLoads"), ResultsPaging.MAX_FILL_LOADS)
        assertEquals(table.getLong("minimumEarlierSpanMinutes"), ResultsPaging.MIN_EARLIER_SPAN_MINUTES)
        assertEquals(table.getInt("earlierJourneyResults"), ResultsPaging.EARLIER_JOURNEY_RESULTS)
    }

    @Test fun `later`() = runBlocking {
        val cases = rules.getJSONArray("later").objects()
        assertEquals(2, cases.size)
        cases.forEach { case ->
            val found = ResultsPaging.later<LocalDateTime>(at(case.getInt("last")), { it }, loader(case.getJSONObject("source")))
            assertEquals(case.getString("name"), expected(case), found)
        }
    }

    @Test fun `earlier`() = runBlocking {
        val cases = rules.getJSONArray("earlier").objects()
        assertEquals(8, cases.size)
        cases.forEach { case ->
            val on = dayOf(case)
            val found = ResultsPaging.earlier<LocalDateTime>(
                at(case.getInt("first"), on), { it }, notBefore = at(case.getInt("notBefore"), on), wanted = case.getInt("wanted"),
                load = loader(case.getJSONObject("source"), on)
            )
            assertEquals(case.getString("name"), expected(case), found)
        }
    }
}
