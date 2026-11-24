package it.binario.app.data.live

import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.core.BoardKind
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.OperatorId
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoard
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.StationBoardProvider
import it.binario.app.data.provider.StationBoardRequest
import it.binario.app.data.repository.LiveRunMerge
import it.binario.app.shared.SharedData
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-rules/v1/live-route-v1.json and contracts/shared-data/v1/live-route-v1.json, read as the iPhone's
 * LiveRouteContractTests read them: the RFI route of two boards ([RfiLiveRouteDataSource]) and the live runs merged into
 * the timetable list ([LiveRunMerge]).
 */
class LiveRouteContractTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/live-route-v1.json").readText(Charsets.UTF_8))
    private val day = LocalDate.parse(rules.getString("day"))
    private val route = SavedRoute(
        "contract", "Contract", Station("curated:S01234", "Origine", "Italia", railwayCode = "S01234"),
        Station("curated:S05678", "Destinazione", "Italia", railwayCode = "S05678")
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    private fun time(value: String): LocalDateTime = if ('T' in value) LocalDateTime.parse(value) else day.atTime(LocalTime.parse(value))

    @Test fun `the live route reads the shared table`() {
        val table = JSONObject(File(repositoryRoot, "contracts/shared-data/v1/live-route-v1.json").readText(Charsets.UTF_8))
        assertEquals(table.getInt("retimingWindowMinutes"), SharedData.LiveRoute.RETIMING_WINDOW_MINUTES)
        val families = table.getJSONObject("operatorFamilies")
        assertEquals(families.keys().asSequence().associateWith { families.getString(it) }, SharedData.LiveRoute.OPERATOR_FAMILIES)
        assertEquals(table.getInt("rfiMaximumRuns"), SharedData.LiveRoute.RFI_MAXIMUM_RUNS)
        assertEquals(table.getInt("viaggiaTrenoMaximumRuns"), SharedData.LiveRoute.VIAGGIA_TRENO_MAXIMUM_RUNS)
        // The timetable names Android's legacyOperator reads (EmbeddedGtfsRouteAdapter) are operators of the families.
        val timetable = table.getJSONObject("timetableOperators")
        assertEquals(timetable.keys().asSequence().associateWith { timetable.getString(it) }, SharedData.LiveRoute.TIMETABLE_OPERATORS)
        SharedData.LiveRoute.TIMETABLE_OPERATORS.values.forEach { id -> assertTrue(id, SharedData.LiveRoute.OPERATOR_FAMILIES.containsKey(id)) }
    }

    private fun entry(value: JSONObject, id: String, now: java.time.Instant): StationBoardEntry {
        val scheduled = time(value.getString("scheduled")).atZone(zone)
        val source = SourceRef(
            SourceId("rfi-station-board"), observedAt = now.minusSeconds(value.optLong("observedSecondsAgo", 0)), fetchedAt = now,
            expiresAt = now.plusSeconds(value.optLong("expiresInSeconds", 45)), confidence = Confidence.INFRASTRUCTURE_PRIMARY, traceId = "contract"
        )
        val delay = if (value.isNull("delay")) null else value.getLong("delay")
        val status = value.textOrNull("status")?.let { RunStatus.valueOf(it) }
        return StationBoardEntry(
            TrainRunId(id), scheduled, delay?.let { SourcedValue(scheduled.plusMinutes(it), source) }, StationId("capolinea"),
            destinationOrOriginLabel = "Capolinea", publicNumber = value.textOrNull("number"),
            operatorId = value.textOrNull("operator")?.let { OperatorId(it) }, status = status?.let { SourcedValue(it, source) }
        )
    }

    private fun provider(departures: List<StationBoardEntry>, arrivals: List<StationBoardEntry>, now: java.time.Instant) = object : StationBoardProvider {
        override val descriptor = RfiStationBoardAdapter().descriptor
        override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
            val entries = if (request.kind == BoardKind.DEPARTURES) departures else arrivals
            return ProviderResult.Success(
                StationBoard(request.stationId, request.kind, request.from, request.to, entries,
                    SnapshotMeta(1, now, now.plusSeconds(45), it.binario.app.data.core.Freshness.FRESH, Completeness.PARTIAL, emptySet())),
                ProviderResponseMeta(descriptor.id, now)
            )
        }
    }

    @Test fun `the RFI route`() = runBlocking {
        val cases = rules.getJSONArray("rfiRoute").objects()
        assertEquals(11, cases.size)
        cases.forEach { case ->
            val name = case.getString("name")
            val now = time(case.getString("now")).atZone(zone).toInstant()
            val departures = case.getJSONArray("departures").objects().mapIndexed { index, value -> entry(value, "contract:d:$index", now) }
            val arrivals = case.getJSONArray("arrivals").objects().mapIndexed { index, value -> entry(value, "contract:a:$index", now) }
            val query = JourneyQuery(day, LocalTime.parse(case.getString("requested")))
            val result = RfiLiveRouteDataSource(provider(departures, arrivals, now), Clock.fixed(now, ZoneOffset.UTC))
                .loadRoute(route, Direction.OUTBOUND, query)
            val expected = case.optJSONArray("expected")
            if (expected == null) {
                assertTrue(name, result is DataResult.Failure)
            } else {
                val services = (result as DataResult.Success).snapshot.services
                assertEquals(name, expected.length(), services.size)
                expected.objects().zip(services).forEach { (wanted, service) ->
                    assertEquals(name, wanted.getString("number"), service.number)
                    assertEquals(name, wanted.getString("scheduledDeparture"), service.scheduledDeparture)
                    assertEquals(name, wanted.getString("expectedDeparture"), service.expectedDeparture)
                    assertEquals(name, if (wanted.isNull("delayMinutes")) null else wanted.getInt("delayMinutes"), service.officialDelayMinutes)
                    assertEquals(name, wanted.getString("scheduledArrival"), service.stops.last().scheduled)
                    assertEquals(name, wanted.getString("expectedArrival"), service.stops.last().expected)
                    assertEquals(name, ServiceStatus.valueOf(wanted.getString("status")), service.status)
                    assertEquals(name, wanted.getBoolean("verified"), service.operationalStatusVerified)
                    assertEquals(name, wanted.getBoolean("lateWithoutMinutes"), service.delayMinutesUnpublished)
                    assertEquals(name, wanted.getBoolean("departureVerified"), service.stops.first().operationalStatusVerified)
                    assertEquals(name, wanted.getBoolean("arrivalVerified"), service.stops.last().operationalStatusVerified)
                }
            }
        }
    }

    private fun operator(id: String?): RailOperator = when (id) {
        null -> RailOperator.OTHER
        "trenitalia" -> RailOperator.TRENITALIA_REGIONALE
        "trenord" -> RailOperator.TRENORD
        "italo" -> RailOperator.ITALO
        else -> error("unknown operator $id")
    }

    private fun service(value: JSONObject): TrainService {
        val text = value.getString("scheduled")
        val dated = value.optBoolean("dated", true)
        val at = time(text)
        val clock = at.toLocalTime().toString()
        return TrainService(
            id = value.getString("id"), number = value.getString("number"), operator = operator(value.textOrNull("operator")),
            trainCategory = TrainCategory.OTHER, origin = "Origine", destination = "Destinazione", scheduledDeparture = clock,
            expectedDeparture = clock, platform = null, delayMinutes = 0, status = ServiceStatus.ON_TIME, stops = emptyList(),
            operationalStatusVerified = value.getBoolean("verified"), scheduledDepartureDateTime = if (dated) at else null
        )
    }

    private fun snapshot(services: List<TrainService>) = RouteSnapshot(route, Direction.OUTBOUND, services, "contract", 0L, Long.MAX_VALUE, Freshness.AGING)

    @Test fun `the live runs merged into the timetable`() {
        val cases = rules.getJSONArray("merge").objects()
        assertEquals(11, cases.size)
        cases.forEach { case ->
            val name = case.getString("name")
            val planned = case.getJSONArray("planned").objects().map { service(it) }
            val live = case.getJSONArray("live").objects().map { service(it) }
            val query = JourneyQuery(day, LocalTime.parse(case.getString("requested")))
            val result = LiveRunMerge.merge(DataResult.Success(snapshot(planned)), DataResult.Success(snapshot(live)), query)
            val ids = (result as DataResult.Success).snapshot.services.map { it.id }.toSet()
            val expected = case.getJSONObject("expected")
            val replaced = expected.getJSONObject("replaced")
            val added = expected.getJSONArray("added").let { array -> (0 until array.length()).map { array.getString(it) } }
            val wanted = planned.map { it.id }.filter { !replaced.has(it) }.toSet() +
                replaced.keys().asSequence().map { replaced.getString(it) }.toSet() + added.toSet()
            assertEquals(name, wanted, ids)
        }
    }
}
