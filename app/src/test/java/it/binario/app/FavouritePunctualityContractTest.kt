package it.binario.app

import it.binario.app.notifications.DigestTrain
import it.binario.app.notifications.PunctualityRecordingPlanner
import it.binario.app.notifications.RecordingCandidate
import it.binario.app.notifications.WeeklyDigestBuilder
import it.binario.app.notifications.WeeklyDigestPlanner
import it.binario.app.widget.ReliabilityMark
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * contracts/shared-rules/v1/favourite-punctuality-v1.json: the runs of the favourite trains recorded on this phone, the
 * trend, the Sunday digest and the background recording, read as the iPhone's FavouritePunctualityContractTests read them.
 */
class FavouritePunctualityContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/favourite-punctuality-v1.json").readText(Charsets.UTF_8))
    private val rome = ZoneId.of("Europe/Rome")

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)
    private fun at(text: String): ZonedDateTime = LocalDateTime.parse(text).atZone(rome)

    private fun history(case: JSONObject): List<PunctualityObservation> = case.getJSONArray("history").objects().map {
        PunctualityObservation(LocalDate.parse(it.getString("date")), it.getString("train"), "", it.getInt("delay"), it.getBoolean("cancelled"))
    }

    private fun local(json: JSONObject?): LocalPunctuality? =
        json?.let { LocalPunctuality(it.getInt("runs"), it.getInt("onTime"), it.getInt("cancelled")) }

    @Test fun `runs recorded between two days`() {
        rules.getJSONArray("local").objects().forEach { case ->
            val found = FavouriteTrainPunctuality.local(
                history(case), case.getString("train"), LocalDate.parse(case.getString("from")), LocalDate.parse(case.getString("to"))
            )
            val expected = case.optJSONObject("expected")
            assertEquals(case.getString("name"), local(expected), found)
            if (expected != null) assertEquals(case.getString("name"), expected.getInt("onTimePercent"), found?.onTimePercent)
        }
    }

    @Test fun `the trend of the last weeks`() {
        rules.getJSONArray("trend").objects().forEach { case ->
            val found = FavouriteTrainPunctuality.trend(history(case), case.getString("train"), LocalDate.parse(case.getString("today")))
            val expected = case.optJSONObject("expected")
            if (expected == null) {
                assertNull(case.getString("name"), found)
            } else {
                assertEquals(case.getString("name"), local(expected.getJSONObject("recent")), found?.recent)
                assertEquals(case.getString("name"), local(expected.getJSONObject("earlier")), found?.earlier)
                assertEquals(case.getString("name"), expected.getBoolean("better"), found?.better)
            }
        }
    }

    @Test fun `the week and the next run of the digest`() {
        rules.getJSONArray("digestWeekEnd").objects().forEach { case ->
            val now = at(case.getString("now"))
            assertEquals(case.getString("now"), case.text("expected")?.let(LocalDate::parse), WeeklyDigestPlanner.weekEndAt(now))
            assertEquals(case.getString("now"), case.getBoolean("mayNotify"), WeeklyDigestPlanner.mayNotify(now))
        }
        rules.getJSONArray("digestNextRun").objects().forEach { case ->
            val next = WeeklyDigestPlanner.nextRunAt(at(case.getString("now")), case.text("lastSent")?.let(LocalDate::parse))
            assertEquals(case.getString("now"), at(case.getString("expected")), next)
        }
    }

    @Test fun `the train the digest names as worse`() {
        rules.getJSONArray("digestWorse").objects().forEach { case ->
            val trains = case.getJSONArray("trains").objects().map { train ->
                val network = train.optJSONObject("network")?.let {
                    ReliabilityMark(it.getInt("onTime"), it.getInt("samples"), it.getInt("limitMinutes"), it.getInt("onTimeWithin5"), it.getInt("days"))
                }
                DigestTrain(ServiceMonitor(train.getString("number"), train.getString("number")), local(train.optJSONObject("week")), local(train.optJSONObject("earlier")), network)
            }
            val worse = WeeklyDigestBuilder.worse(trains)
            val expected = case.optJSONObject("expected")
            if (expected == null) {
                assertNull(case.getString("name"), worse)
            } else {
                assertEquals(case.getString("name"), expected.getString("number"), worse?.train?.monitor?.number)
                assertEquals(case.getString("name"), expected.getInt("weekPercent"), worse?.weekPercent)
                assertEquals(case.getString("name"), expected.getInt("usualPercent"), worse?.usualPercent)
            }
        }
    }

    @Test fun `when a favourite's final delay is recorded`() {
        rules.getJSONArray("recordingDueAt").objects().forEach { case ->
            val json = case.getJSONObject("candidate")
            val candidate = RecordingCandidate(
                number = "2153",
                departure = json.text("departure")?.let(LocalTime::parse),
                durationMinutes = if (json.isNull("duration")) null else json.getInt("duration"),
                recordedOn = json.text("recordedOn")?.let(LocalDate::parse),
                attemptDate = json.text("attemptDate")?.let(LocalDate::parse),
                attempts = json.getInt("attempts"),
                lastAttempt = json.text("lastAttempt")?.let { at(it).toInstant() }
            )
            val due = PunctualityRecordingPlanner.dueAt(candidate, LocalDate.parse(case.getString("date")), rome)
            assertEquals(case.getString("name"), case.text("expected")?.let { at(it).toInstant() }, due)
        }
    }
}
