package it.binario.app.data.journey

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-rules/v1/connection-scan-v1.json: the Connection Scan of the journeys with changes, the same cases
 * the iPhone's port reads (JourneyPlannerContractTests.swift).
 */
class ConnectionScanContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/connection-scan-v1.json").readText(Charsets.UTF_8))

    @Test fun `every shared case gives the shared journeys`() {
        val cases = rules.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (position in 0 until cases.length()) {
            val case = cases.getJSONObject(position)
            val name = case.getString("name")
            val outcome = ConnectionScanPlanner(timetable(case.getJSONObject("timetable"))).planWithOutcome(query(case))
            val expected = case.getJSONObject("expected")
            assertEquals(name, expected.getBoolean("exhausted"), outcome.exhausted)
            assertEquals(name, describe(expected.getJSONArray("journeys")), outcome.journeys.map(::describe))
        }
    }

    private fun timetable(json: JSONObject): ConnectionScanTimetable {
        val nodes = json.getInt("nodes")
        val connections = json.getJSONArray("connections")
        val size = connections.length()
        val row = { index: Int -> connections.getJSONArray(index) }
        val noPickup = json.optJSONArray("noPickup").ints().toSet()
        val noDropOff = json.optJSONArray("noDropOff").ints().toSet()
        val trips = (0 until size).maxOfOrNull { row(it).getInt(4) }?.plus(1) ?: 0
        val walks = json.optJSONArray("footpaths")?.let { list -> (0 until list.length()).map(list::getJSONArray) }.orEmpty()
        val footStart = IntArray(nodes + 1)
        val footTarget = ArrayList<Int>()
        val footMinutes = ArrayList<Int>()
        for (node in 0 until nodes) {
            footStart[node] = footTarget.size
            walks.filter { it.getInt(0) == node }.forEach {
                footTarget += it.getInt(1)
                footMinutes += it.getInt(2)
            }
        }
        footStart[nodes] = footTarget.size
        val links = json.optJSONArray("continuations")?.let { list -> (0 until list.length()).map(list::getJSONArray) }.orEmpty()
        val start = IntArray(size) { -1 }
        val continuationTrip = ArrayList<Int>()
        val continuationNext = ArrayList<Int>()
        for (link in links) {
            val connection = link.getInt(0)
            continuationNext += start[connection]
            continuationTrip += link.getInt(1)
            start[connection] = continuationTrip.size - 1
        }
        return ConnectionScanTimetable(
            nodeCount = nodes,
            tripCount = trips,
            transferMinutes = json.getJSONArray("transferMinutes").ints().toIntArray(),
            departureNode = IntArray(size) { row(it).getInt(0) },
            arrivalNode = IntArray(size) { row(it).getInt(1) },
            departureMinute = IntArray(size) { row(it).getInt(2) },
            arrivalMinute = IntArray(size) { row(it).getInt(3) },
            trip = IntArray(size) { row(it).getInt(4) },
            boarding = ByteArray(size) { ConnectionScanTimetable.boardingFlags(it !in noPickup, it !in noDropOff) },
            footStart = footStart,
            footTarget = footTarget.toIntArray(),
            footMinutes = footMinutes.toIntArray(),
            continuationStart = if (links.isEmpty()) null else start,
            continuationTrip = continuationTrip.toIntArray(),
            continuationNext = continuationNext.toIntArray()
        )
    }

    private fun query(case: JSONObject): ConnectionScanQuery {
        val json = case.getJSONObject("query")
        val nodes = case.getJSONObject("timetable").getInt("nodes")
        fun marks(key: String) = json.getJSONArray(key).ints().toSet().let { set -> BooleanArray(nodes) { it in set } }
        fun walks(key: String): IntArray? = json.optJSONObject(key)?.let { walk ->
            IntArray(nodes) { node -> if (walk.has(node.toString())) walk.getInt(node.toString()) else -1 }
        }
        val latest = json.getInt("latestDepartureMinute")
        return ConnectionScanQuery(
            origins = marks("origins"),
            destinations = marks("destinations"),
            departureMinute = json.getInt("departureMinute"),
            latestDepartureMinute = latest,
            maxTransfers = json.getInt("maxTransfers"),
            maxResults = json.getInt("maxResults"),
            maxIterations = json.getInt("maxIterations"),
            lastConnectionMinute = if (json.has("lastConnectionMinute")) json.getInt("lastConnectionMinute") else latest,
            originWalkMinutes = walks("originWalk"),
            destinationWalkMinutes = walks("destinationWalk"),
            preferLessWalking = json.optBoolean("preferLessWalking", false),
            maxChangeWaitMinutes = if (json.has("maxChangeWaitMinutes")) json.getInt("maxChangeWaitMinutes") else Int.MAX_VALUE
        )
    }

    private fun describe(expected: JSONArray): List<String> = (0 until expected.length()).map { index ->
        val journey = expected.getJSONObject(index)
        fun walk(value: Any?): String = if (value is JSONArray) value.ints().joinToString(",") else "-"
        val legs = journey.getJSONArray("legs").let { list -> (0 until list.length()).map { list.getJSONArray(it).ints().joinToString(">") } }
        val transfers = journey.getJSONArray("transferWalks").let { list -> (0 until list.length()).map { walk(list.opt(it)) } }
        "${legs.joinToString(" ")} ${journey.getInt("departure")}-${journey.getInt("arrival")} " +
            "access ${walk(journey.opt("accessWalk"))} transfers ${transfers.joinToString(" ")} egress ${walk(journey.opt("egressWalk"))}"
    }

    private fun describe(journey: ScannedJourney): String {
        fun walk(value: ScannedWalk?): String = value?.let { "${it.fromNode},${it.toNode},${it.minutes}" } ?: "-"
        val legs = journey.legs.joinToString(" ") { "${it.boardConnection}>${it.alightConnection}" }
        return "$legs ${journey.departureMinute}-${journey.arrivalMinute} access ${walk(journey.accessWalk)} " +
            "transfers ${journey.transferWalks.joinToString(" ") { walk(it) }} egress ${walk(journey.egressWalk)}"
    }

    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map(::getInt)
}
