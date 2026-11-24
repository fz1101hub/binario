package it.binario.app.maps

import it.binario.app.data.RailOperator
import it.binario.app.data.TrainCategory
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.journey.ScheduledRailRun
import it.binario.app.data.journey.ScheduledRunOrigin
import it.binario.app.data.journey.ScheduledRunStop
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * contracts/shared-rules/v1/live-train-positions-v1.json: progress, estimated position, selection, labels and areas of
 * the map of trains in motion, as the iPhone's LiveTrainPositionsContractTests read them.
 */
class LiveTrainPositionsContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/live-train-positions-v1.json").readText(Charsets.UTF_8))
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)
    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    private fun stops(array: JSONArray) = array.objects().map {
        ScheduledRunStop(it.getString("name"), it.doubleOrNull("latitude"), it.doubleOrNull("longitude"), it.getInt("arrival"), it.getInt("departure"))
    }

    private fun progress(json: JSONObject): RunProgress = when (json.getString("kind")) {
        "notStarted" -> RunProgress.NotStarted
        "arrived" -> RunProgress.Arrived
        "atStop" -> RunProgress.AtStop(json.getInt("stop"))
        else -> RunProgress.Between(json.getInt("from"), json.getInt("to"), json.getDouble("fraction"))
    }

    private fun assertProgress(message: String, expected: RunProgress, actual: RunProgress) {
        if (expected is RunProgress.Between && actual is RunProgress.Between) {
            assertEquals(message, expected.from, actual.from)
            assertEquals(message, expected.to, actual.to)
            assertEquals(message, expected.fraction, actual.fraction, 1e-9)
        } else {
            assertEquals(message, expected, actual)
        }
    }

    private fun bounds(json: JSONObject) = GeoBounds(json.getDouble("north"), json.getDouble("south"), json.getDouble("east"), json.getDouble("west"))
    private fun point(json: JSONObject) = GeoPoint(json.getDouble("latitude"), json.getDouble("longitude"))

    private fun assertBounds(expected: GeoBounds, actual: GeoBounds) {
        assertEquals(expected.north, actual.north, 1e-9)
        assertEquals(expected.south, actual.south, 1e-9)
        assertEquals(expected.east, actual.east, 1e-9)
        assertEquals(expected.west, actual.west, 1e-9)
    }

    @Test fun `progress and position on the timetable`() {
        val stops = stops(rules.getJSONArray("stops"))
        for (case in rules.getJSONArray("progress").objects()) {
            assertProgress("${case.getDouble("minute")}", progress(case.getJSONObject("expected")), LiveTrainEstimator.progress(stops, case.getDouble("minute")))
        }
        for (case in rules.getJSONArray("positions").objects()) {
            val actual = LiveTrainEstimator.position(stops(case.getJSONArray("stops")), case.getDouble("minute"))
            val expected = case.optJSONObject("expected")
            if (expected == null) {
                assertNull(actual)
            } else {
                assertEquals(expected.getDouble("latitude"), actual!!.latitude, 1e-9)
                assertEquals(expected.getDouble("longitude"), actual.longitude, 1e-9)
            }
        }
        for (case in rules.getJSONArray("minuteOfServiceDay").objects()) {
            assertEquals(case.getDouble("expected"),
                LiveTrainEstimator.minuteOfServiceDay(LocalDate.parse(case.getString("serviceDate")), LocalDateTime.parse(case.getString("now"))), 1e-9)
        }
        for (case in rules.getJSONArray("runNumberKeys").objects()) {
            assertEquals(case.getString("number"), case.textOrNull("expected"), EmbeddedGtfsJourneyProvider.runNumberKey(case.getString("number")))
        }
    }

    @Test fun `the trains selected for the map`() {
        for (case in rules.getJSONArray("selections").objects()) {
            val name = case.getString("name")
            val runs = case.getJSONArray("runs").objects().map { run ->
                ScheduledRailRun(
                    id = run.getString("id"),
                    serviceDate = LocalDate.parse(run.getString("serviceDate")),
                    publicNumber = run.getString("number"),
                    lineLabel = run.textOrNull("line"),
                    category = TrainCategory.REGIONALE,
                    operator = RailOperator.TRENITALIA_REGIONALE,
                    operatorName = "Trenitalia",
                    stops = stops(run.getJSONArray("stops")),
                    origin = run.optJSONObject("origin")?.let { ScheduledRunOrigin(it.getString("name"), it.getInt("departure")) }
                )
            }
            val observations = case.getJSONArray("observations").objects().map {
                LiveRunObservation(it.getString("number"), LocalDateTime.parse(it.getString("scheduledAt")), it.intOrNull("delayMinutes"),
                    it.getBoolean("cancelled"), it.getInt("ref"))
            }
            val now = LocalDateTime.parse(case.getString("now"))
            val selection = case.intOrNull("cap")?.let { cap ->
                LiveTrainSelector.select(runs, observations, now, bounds(case.getJSONObject("bounds")), point(case.getJSONObject("focus")), cap)
            } ?: LiveTrainSelector.select(runs, observations, now, bounds(case.getJSONObject("bounds")), point(case.getJSONObject("focus")))
            val expected = case.getJSONObject("expected")
            assertEquals(name, expected.getInt("inBounds"), selection.inBounds)
            val trains = expected.getJSONArray("trains").objects()
            assertEquals(name, trains.map { it.getString("id") }, selection.trains.map { it.run.id })
            for ((train, actual) in trains.zip(selection.trains)) {
                assertEquals(name, train.getDouble("latitude"), actual.point.latitude, 1e-9)
                assertEquals(name, train.getDouble("longitude"), actual.point.longitude, 1e-9)
                assertEquals(name, train.intOrNull("delayMinutes"), actual.delayMinutes)
                assertEquals(name, train.intOrNull("ref"), actual.observation?.ref)
                assertProgress(name, progress(train.getJSONObject("progress")), actual.progress)
            }
        }
    }

    @Test fun `marker labels`() {
        for (case in rules.getJSONArray("markers").objects()) {
            val run = ScheduledRailRun("pack:x:2026-11-04", LocalDate.of(2026, 11, 4), case.getString("number"), case.textOrNull("line"),
                TrainCategory.OTHER, RailOperator.TRENITALIA_REGIONALE, null,
                listOf(ScheduledRunStop("Alfa", 45.0, 9.0, 600, 600), ScheduledRunStop("Beta", 45.3, 9.3, 630, 630)))
            val train = LiveTrain(run, GeoPoint(45.0, 9.0), RunProgress.AtStop(0), case.intOrNull("delayMinutes"), null, 0.0)
            assertEquals(case.getString("expected"), LiveTrainLabels.marker(train))
        }
    }

    @Test fun `the area of a map and its timetable slot`() {
        val areas = rules.getJSONObject("areas")
        for (case in areas.getJSONArray("around").objects()) {
            assertBounds(bounds(case.getJSONObject("expected")), LiveTrainAreas.around(GeoPoint(case.getDouble("latitude"), case.getDouble("longitude"))))
        }
        for (case in areas.getJSONArray("routes").objects()) {
            assertBounds(bounds(case.getJSONObject("expected")), LiveTrainAreas.routeArea(point(case.getJSONObject("origin")), point(case.getJSONObject("destination"))))
        }
        for (case in areas.getJSONArray("tooWide").objects()) {
            assertEquals(case.getBoolean("expected"), LiveTrainAreas.tooWide(bounds(case.getJSONObject("bounds"))))
        }
        for (case in areas.getJSONArray("readAreas").objects()) {
            assertBounds(bounds(case.getJSONObject("expected")), LiveTrainAreas.readArea(bounds(case.getJSONObject("visible"))))
        }
        for (case in areas.getJSONArray("covers").objects()) {
            assertEquals(case.getBoolean("expected"), LiveTrainAreas.covers(bounds(case.getJSONObject("read")), bounds(case.getJSONObject("visible"))))
        }
        for (case in rules.getJSONArray("slots").objects()) {
            assertEquals(LocalDateTime.parse(case.getString("expected")), LiveTrainRefresh.slotStart(LocalDateTime.parse(case.getString("now"))))
        }
    }
}
