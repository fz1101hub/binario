package it.binario.app.journeys

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.JourneyId
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.repository.SourcedJourneyOption
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * contracts/shared-rules/v1/journey-live-v1.json and contracts/shared-data/v1/journey-live-v1.json, read as the iPhone's
 * JourneyLiveContractTests read them: the risk of a change, the run of a number search that is a leg's, the legs looked
 * up and how long their answers are shown.
 */
class JourneyLiveContractTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/journey-live-v1.json").readText(Charsets.UTF_8))
    private val day = LocalDate.parse(rules.getString("day"))

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    private fun time(value: String): LocalDateTime =
        if ('T' in value) LocalDateTime.parse(value) else day.atTime(LocalTime.parse(value))

    private fun instant(value: String): Instant = time(value).atZone(zone).toInstant()

    private fun live(value: JSONObject?): JourneyLegLive? = value?.let { live ->
        JourneyLegLive(
            expectedDeparture = live.textOrNull("expectedDeparture")?.let { instant(it) },
            expectedArrival = live.textOrNull("expectedArrival")?.let { instant(it) },
            cancelled = live.optBoolean("cancelled", false)
        )
    }

    @Test fun `the lookup and its refresh read the shared table`() {
        val table = JSONObject(File(repositoryRoot, "contracts/shared-data/v1/journey-live-v1.json").readText(Charsets.UTF_8))
        assertEquals(Duration.ofMinutes(table.getLong("lookBackMinutes")), JourneyLiveCandidates.LOOKBACK)
        assertEquals(Duration.ofMinutes(table.getLong("lookAheadMinutes")), JourneyLiveCandidates.LOOKAHEAD)
        assertEquals(table.getInt("maximumTrains"), JourneyLiveCandidates.MAX_TRAINS)
        assertEquals(Duration.ofMinutes(table.getLong("sameRunToleranceMinutes")), JourneyLiveCandidates.SAME_RUN_TOLERANCE)
        assertEquals(Duration.ofMinutes(table.getLong("maximumAgeMinutes")), JourneyLiveRefresh.MAX_AGE)
        assertEquals(Duration.ofSeconds(table.getLong("minimumIntervalSeconds")), JourneyLiveRefresh.MIN_INTERVAL)
    }

    @Test fun `the risk of a change`() {
        val cases = rules.getJSONArray("transferRisk").objects()
        assertEquals(11, cases.size)
        cases.forEach { case ->
            val name = case.getString("name")
            val assessment = TransferRiskCalculator.assess(
                instant(case.getString("scheduledArrival")), live(case.optJSONObject("arrivingLive")),
                instant(case.getString("scheduledDeparture")), live(case.optJSONObject("leavingLive")),
                Duration.ofMinutes(case.getLong("walkMinutes")), case.getLong("changeMinutes")
            )
            val expected = case.getJSONObject("expected")
            assertEquals(name, TransferRiskLevel.valueOf(expected.getString("level")), assessment.level)
            assertEquals(name, expected.getLong("marginMinutes"), assessment.marginMinutes)
            assertEquals(name, instant(expected.getString("expectedArrival")), assessment.expectedArrival)
            assertEquals(name, instant(expected.getString("expectedDeparture")), assessment.expectedDeparture)
        }
    }

    private fun leg(value: JSONObject) = JourneyLeg(
        mode = LegMode.TRAIN, runId = TrainRunId("contract:${value.getString("number")}"),
        origin = StationId("from"), destination = StationId("to"),
        scheduledDeparture = time(value.getString("departure")).atZone(zone), scheduledArrival = time(value.getString("arrival")).atZone(zone),
        publicNumber = value.getString("number"), originLabel = value.getString("from"), destinationLabel = value.getString("to"),
        serviceDate = LocalDate.parse(value.getString("serviceDate"))
    )

    private fun run(value: JSONObject, number: String): TrainService {
        val stops = value.getJSONArray("stops").objects().map { stop ->
            val verified = stop.getBoolean("verified")
            TrainStop(stop.getString("station"), stop.getString("scheduled"), stop.getString("expected"), null, 0,
                completed = verified, operationalStatusVerified = verified)
        }
        val verified = value.getBoolean("verified")
        val delay = value.getInt("delayMinutes")
        return TrainService(
            id = value.getString("id"), number = number, operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.INTERCITY,
            origin = stops.first().station, destination = stops.last().station, scheduledDeparture = stops.first().scheduled,
            expectedDeparture = stops.first().expected, platform = null, delayMinutes = delay,
            status = ServiceStatus.valueOf(value.getString("status")), stops = stops, operationalStatusVerified = verified,
            officialDelayMinutes = if (verified) delay else null, scheduledDepartureDateTime = time(value.getString("start"))
        )
    }

    @Test fun `the run of a number search that is the leg's`() {
        val cases = rules.getJSONArray("liveRuns").objects()
        assertEquals(8, cases.size)
        cases.forEach { case ->
            val name = case.getString("name")
            val legCase = case.getJSONObject("leg")
            val runs = case.getJSONArray("runs").objects().map { run(it, legCase.getString("number")) }
            val found = JourneyLiveCandidates.liveFor(leg(legCase), runs, zone)
            val expected = case.optJSONObject("expected")
            if (expected == null) {
                assertNull(name, found)
            } else {
                requireNotNull(found) { name }
                assertEquals(name, expected.textOrNull("expectedDeparture")?.let { instant(it) }, found.expectedDeparture)
                assertEquals(name, expected.textOrNull("expectedArrival")?.let { instant(it) }, found.expectedArrival)
                assertEquals(name, expected.getBoolean("cancelled"), found.cancelled)
            }
        }
    }

    private fun candidateLeg(value: JSONObject, id: String) = JourneyLeg(
        mode = LegMode.valueOf(value.getString("mode")), runId = TrainRunId(id), origin = StationId("$id:from"),
        destination = StationId("$id:to"), scheduledDeparture = time(value.getString("departure")).atZone(zone),
        scheduledArrival = time(value.getString("arrival")).atZone(zone), publicNumber = value.textOrNull("number"),
        serviceDate = day
    )

    private fun option(index: Int, legs: List<JourneyLeg>) = SourcedJourneyOption(
        option = JourneyOption(
            id = JourneyId("option-$index"), origin = legs.first().origin, destination = legs.last().destination,
            departure = legs.first().scheduledDeparture, arrival = legs.last().scheduledArrival, legs = legs,
            snapshot = SnapshotMeta(1, Instant.EPOCH, freshness = Freshness.AGING, completeness = Completeness.STATIC_ONLY, contributingSources = emptySet())
        ),
        sourceId = EmbeddedGtfsJourneyProvider.SOURCE_ID,
        sourceName = "Orario offline",
        scheduledOnly = true
    )

    @Test fun `the legs looked up and their trains`() {
        val cases = rules.getJSONArray("candidates").objects()
        assertEquals(2, cases.size)
        cases.forEach { case ->
            val name = case.getString("name")
            val options = (0 until case.getJSONArray("options").length()).map { index ->
                val legs = case.getJSONArray("options").getJSONArray(index).objects()
                    .mapIndexed { legIndex, leg -> candidateLeg(leg, "contract:$index:$legIndex") }
                option(index, legs)
            }
            val legs = JourneyLiveCandidates.legs(options, instant(case.getString("now")))
            val expected = case.getJSONObject("expected")
            assertEquals(name, expected.getJSONArray("legs").strings(), legs.map { "${it.publicNumber}@${it.scheduledDeparture.toLocalDateTime()}" })
            assertEquals(
                name,
                expected.getJSONArray("trains").objects().map { "${it.getString("number")}@${it.getString("serviceDate")}" },
                JourneyLiveCandidates.trains(legs, zone).map { "${it.number}@${it.serviceDate}" }
            )
        }
    }

    @Test fun `how long the answers are shown and how often they are asked`() {
        val refresh = rules.getJSONObject("refresh")
        val now = day.atTime(12, 0).atZone(zone).toInstant()
        refresh.getJSONArray("current").objects().forEach { case ->
            val fetched = case.getJSONObject("fetchedSecondsAgo")
            val keys = if (case.has("legs")) case.getJSONArray("legs").strings() else fetched.keys().asSequence().toList()
            val legs = keys.associateWith { JourneyLegLive(cancelled = true) }
            val fetchedAt = fetched.keys().asSequence().associateWith { now.minusSeconds(fetched.getLong(it)) }
            assertEquals(case.getString("name"), case.getJSONArray("expected").strings().sorted(),
                JourneyLiveRefresh.current(legs, fetchedAt, now).keys.sorted())
        }
        refresh.getJSONArray("isDue").objects().forEach { case ->
            val started = if (case.isNull("startedSecondsAgo")) null else now.minusSeconds(case.getLong("startedSecondsAgo"))
            assertEquals(case.getString("name"), case.getBoolean("expected"), JourneyLiveRefresh.isDue(started, now))
        }
        refresh.getJSONArray("focusedFirst").objects().forEach { case ->
            val focused = case.textOrNull("focused")
            assertEquals(case.getString("name"), case.getJSONArray("expected").strings(),
                JourneyLiveRefresh.focusedFirst(case.getJSONArray("ids").strings(), focused) { it })
        }
    }
}
