package it.binario.app.data.live

import com.google.transit.realtime.GtfsRealtime
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.GtfsServiceIdentity
import it.binario.app.data.GtfsStopIdentity
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.RealtimeStopUpdate
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.gtfs.EmbeddedGtfsPack
import it.binario.app.data.gtfs.EmbeddedGtfsRealtimeScheduleResolver
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.provider.TripUpdateProvider
import it.binario.app.data.repository.GtfsRealtimeEnrichedTrainDataSource
import it.binario.app.data.repository.RealtimeEnrichmentService
import it.binario.app.data.urban.UrbanGtfsRealtimeDecoder
import it.binario.app.data.urban.UrbanRealtimeFeeds
import it.binario.app.data.urban.expectedAt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * contracts/shared-rules/v1/gtfs-realtime-v1.json: the GTFS-Realtime rules Android and the iPhone share (the protobuf
 * reading, the SNCF, Trenitalia France and STA feeds, the join to the timetable, the city feeds), read from the same
 * file by both test suites and run here against the official bindings and the existing adapters.
 */
class GtfsRealtimeContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val cases = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/gtfs-realtime-v1.json").readText(Charsets.UTF_8))
    private val rome = ZoneId.of("Europe/Rome")

    @After fun clear() = SourcePauses.clearForTest()

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else getString(key)
    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun yyyymmdd(date: LocalDate?): Int? = date?.format(DateTimeFormatter.BASIC_ISO_DATE)?.toInt()

    @Test fun `the protobuf reading of the bindings`() {
        for (case in cases.getJSONArray("decoding").objects()) {
            val name = case.getString("name")
            val bytes = hex(case.getString("hex"))
            if (case.optBoolean("unreadable")) {
                try {
                    GtfsRealtime.FeedMessage.parseFrom(bytes)
                    fail("$name: leggibile")
                } catch (_: Exception) {
                }
                continue
            }
            val feed = GtfsRealtime.FeedMessage.parseFrom(bytes)
            assertEquals(name, case.getString("version"), feed.header.gtfsRealtimeVersion)
            assertEquals(name, case.longOrNull("timestamp"), feed.header.takeIf { it.hasTimestamp() }?.timestamp)
            val entities = case.getJSONArray("entities").objects()
            assertEquals(name, entities.size, feed.entityCount)
            entities.forEachIndexed { index, expected ->
                val entity = feed.getEntity(index)
                assertEquals(name, expected.getString("id"), entity.id)
                assertEquals(name, expected.getBoolean("isDeleted"), entity.isDeleted)
                assertEquals(name, !expected.isNull("tripUpdate"), entity.hasTripUpdate())
                if (expected.isNull("tripUpdate")) return@forEachIndexed
                val trip = expected.getJSONObject("tripUpdate")
                val update = entity.tripUpdate
                assertEquals(name, trip.getString("tripId"), update.trip.tripId)
                assertEquals(name, trip.getString("routeId"), update.trip.routeId)
                assertEquals(name, trip.getString("startDate"), update.trip.startDate)
                assertEquals(name, trip.getString("scheduleRelationship"), update.trip.scheduleRelationship.name)
                assertEquals(name, trip.longOrNull("timestamp"), update.takeIf { it.hasTimestamp() }?.timestamp)
                val stops = trip.getJSONArray("stops").objects()
                assertEquals(name, stops.size, update.stopTimeUpdateCount)
                stops.forEachIndexed { position, stop ->
                    val actual = update.getStopTimeUpdate(position)
                    assertEquals(name, stop.intOrNull("stopSequence"), actual.takeIf { it.hasStopSequence() }?.stopSequence)
                    assertEquals(name, stop.getString("stopId"), actual.stopId)
                    assertEquals(name, stop.getString("scheduleRelationship"), actual.scheduleRelationship.name)
                    assertEvent(name, stop.optJSONObject("arrival"), actual.takeIf { it.hasArrival() }?.arrival)
                    assertEvent(name, stop.optJSONObject("departure"), actual.takeIf { it.hasDeparture() }?.departure)
                }
            }
        }
    }

    private fun assertEvent(name: String, expected: JSONObject?, actual: GtfsRealtime.TripUpdate.StopTimeEvent?) {
        assertEquals(name, expected == null, actual == null)
        if (expected == null || actual == null) return
        assertEquals(name, expected.intOrNull("delay"), actual.takeIf { it.hasDelay() }?.delay)
        assertEquals(name, expected.longOrNull("time"), actual.takeIf { it.hasTime() }?.time)
    }

    @Test fun `the stop updates of the SNCF, Trenitalia France and STA feeds`() = runBlocking {
        for (case in cases.getJSONArray("protobufUpdates").objects() + cases.getJSONArray("jsonUpdates").objects()) {
            val name = case.getString("name")
            val clock = Clock.fixed(Instant.ofEpochSecond(case.getLong("fetchedAt")), ZoneOffset.UTC)
            val bytes = case.optString("hex").takeIf { it.isNotEmpty() }?.let(::hex)
            val result = when (case.getString("feed")) {
                "sncf-gtfs-rt" -> SncfGtfsRealtimeAdapter(fetcher = { bytes!! }, clock = clock).getTripUpdates()
                "trenitalia-france-gtfs-rt" -> TrenitaliaFranceGtfsRealtimeAdapter(fetcher = { bytes!! }, clock = clock).getTripUpdates()
                "alto-adige-sta-realtime" -> StaRealtimeAdapter(fetcher = { case.getString("json") }, clock = clock).getTripUpdates()
                else -> error("feed sconosciuto in $name")
            }
            // An unreadable feed is no data: a parse error, or a network one for a protobuf the bindings refuse.
            if (case.optBoolean("unreadable")) {
                assertTrue(name, result is ProviderResult.Failure)
                continue
            }
            val updates = (result as ProviderResult.Success).value
            val expected = case.getJSONArray("updates").objects()
            assertEquals(name, expected.size, updates.size)
            expected.zip(updates).forEach { (item, update) ->
                assertEquals(name, item.getString("tripId"), update.sourceTripId)
                assertEquals(name, item.textOrNull("stopId"), update.sourceStopId)
                assertEquals(name, item.intOrNull("stopSequence"), update.stopSequence)
                assertEquals(name, item.intOrNull("serviceDate"), yyyymmdd(update.serviceDate))
                assertEquals(name, item.longOrNull("predicted"), update.predictedAt?.value?.toEpochSecond())
                assertEquals(name, item.longOrNull("delaySeconds"), update.officialDelay?.value?.seconds)
                assertEquals(name, item.getLong("observedAt"), update.observedAt.epochSecond)
                assertEquals(name, item.getLong("expiresAt"), update.source.expiresAt?.epochSecond)
                assertEquals(name, item.getString("entityId"), update.source.nativeId)
                assertEquals(name, case.getString("feed"), update.source.sourceId.value)
            }
        }
    }

    @Test fun `the join of a Trip Update to the listed stop of a timetable train`() = runBlocking {
        for (case in cases.getJSONArray("join").objects()) {
            val name = case.getString("name")
            val now = Instant.ofEpochSecond(case.getLong("now"))
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            val pack = case.getString("pack")
            val serviceDate = LocalDate.parse(case.getInt("serviceDate").toString(), DateTimeFormatter.BASIC_ISO_DATE)
            val stops = case.getJSONArray("stops").objects()
            val listed = stops[case.getInt("listed")]
            val last = stops.last()
            val identity = GtfsServiceIdentity(pack, case.getString("tripId"), serviceDate, listOf(
                GtfsStopIdentity(listed.getString("stopId"), listed.getInt("sequence")),
                GtfsStopIdentity(last.getString("stopId"), last.getInt("sequence"))
            ))
            val service = TrainService(
                id = "$pack:${identity.tripId}:$serviceDate", number = "1", operator = RailOperator.OTHER,
                trainCategory = TrainCategory.REGIONALE, origin = listed.getString("stopId"), destination = last.getString("stopId"),
                scheduledDeparture = listed.getString("time").take(5), expectedDeparture = listed.getString("time").take(5),
                platform = null, delayMinutes = 0, officialDelayMinutes = null, status = ServiceStatus.ON_TIME,
                stops = listOf(TrainStop(listed.getString("stopId"), listed.getString("time").take(5), listed.getString("time").take(5), null, 0, false),
                    TrainStop(last.getString("stopId"), last.getString("time").take(5), last.getString("time").take(5), null, 0, false)),
                operationalStatusVerified = false, gtfsIdentity = identity
            )
            val updates = case.getJSONArray("updates").objects().map { item -> update(item, now) }
            val providers = updates.groupBy { it.source.sourceId.value }.map { (id, values) -> provider(id, values, now) }
            val stopTimes = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + stops.joinToString("") {
                "${identity.tripId},${it.getString("time")},${it.getString("time")},${it.getString("stopId")},${it.getInt("sequence")}\n"
            }
            val zip = zip(stopTimes, stops.map { it.getString("stopId") }.distinct(), case.optString("agencyTimeZone").takeIf { it.isNotEmpty() })
            val resolver = EmbeddedGtfsRealtimeScheduleResolver({ ByteArrayInputStream(zip) }, listOf(EmbeddedGtfsPack(SourceId(pack), pack)))
            val route = SavedRoute("contract", "Contract", Station("a", "A", "A"), Station("b", "B", "B"))
            val snapshot = RouteSnapshot(route, Direction.OUTBOUND, listOf(service), "GTFS", now.toEpochMilli(),
                now.plusSeconds(3600).toEpochMilli(), it.binario.app.data.Freshness.AGING)
            val planned = object : TrainDataSource {
                override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult = DataResult.Success(snapshot)
            }
            val enriched = GtfsRealtimeEnrichedTrainDataSource(planned, RealtimeEnrichmentService(providers, resolver), clock, rome)
            val query = JourneyQuery(now.atZone(rome).toLocalDate(), LocalTime.NOON)
            val result = (enriched.loadRoute(route, Direction.OUTBOUND, query) as DataResult.Success).snapshot.services.single()
            val expected = case.optJSONObject("expected")
            if (expected == null) {
                assertFalse(name, result.operationalStatusVerified)
            } else {
                assertTrue(name, result.operationalStatusVerified)
                assertEquals(name, expected.getInt("delayMinutes"), result.delayMinutes)
                assertEquals(name, expected.getString("expected"), result.expectedDeparture)
            }
        }
    }

    private fun update(item: JSONObject, now: Instant): RealtimeStopUpdate {
        val observed = Instant.ofEpochSecond(item.getLong("observedAt"))
        val source = SourceRef(SourceId(item.getString("feed")), nativeId = "contract", observedAt = observed, fetchedAt = now,
            expiresAt = Instant.ofEpochSecond(item.getLong("expiresAt")), confidence = Confidence.OPEN_DATA, traceId = "contract")
        val trip = item.getString("tripId")
        return RealtimeStopUpdate(
            runId = TrainRunId("contract:$trip"), sourceTripId = trip, sourceStopId = item.textOrNull("stopId"),
            stopSequence = item.intOrNull("stopSequence"),
            predictedAt = item.longOrNull("predicted")?.let { SourcedValue(Instant.ofEpochSecond(it).atZone(rome), source) },
            officialDelay = item.longOrNull("delaySeconds")?.let { SourcedValue(Duration.ofSeconds(it), source) },
            observedAt = observed, source = source,
            serviceDate = item.intOrNull("serviceDate")?.let { LocalDate.parse(it.toString(), DateTimeFormatter.BASIC_ISO_DATE) }
        )
    }

    private fun provider(id: String, updates: List<RealtimeStopUpdate>, now: Instant) = object : TripUpdateProvider {
        override val descriptor = ProviderDescriptor(SourceId(id), id, setOf(ProviderCapability.TRAIN_RUN_REALTIME),
            CoverageDescriptor(description = "contract"), AccessMode.OPEN_DIRECT, RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofSeconds(90)), 1)
        override suspend fun getTripUpdates(): ProviderResult<List<RealtimeStopUpdate>> =
            ProviderResult.Success(updates, ProviderResponseMeta(SourceId(id), now))
    }

    private fun zip(stopTimes: String, stops: List<String>, agencyTimeZone: String?): ByteArray {
        val out = ByteArrayOutputStream()
        val files = linkedMapOf(
            "stops.txt" to "stop_id,stop_name\n" + stops.joinToString("") { "$it,$it\n" },
            "stop_times.txt" to stopTimes
        )
        if (agencyTimeZone != null) files["agency.txt"] = "agency_id,agency_name,agency_url,agency_timezone\nA,A,https://example.invalid,$agencyTimeZone\n"
        ZipOutputStream(out).use { zip ->
            files.forEach { (file, contents) ->
                zip.putNextEntry(ZipEntry(file)); zip.write(contents.toByteArray()); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun `the city feeds of Rome and Mestre`() {
        for (case in cases.getJSONArray("urban").objects()) {
            val name = case.getString("name")
            val feed = UrbanRealtimeFeeds.all.single { it.sourceId.value == case.getString("feed") }
            val routes = case.getJSONArray("routes").strings().toSet()
            val snapshot = UrbanGtfsRealtimeDecoder.decode(File(repositoryRoot, case.getString("file")).readBytes(), feed,
                Instant.ofEpochSecond(case.getLong("fetchedAt"))) { it in routes }
            assertEquals(name, case.getLong("observedAt"), snapshot.observedAt.epochSecond)
            val trips = case.optJSONObject("trips")
            if (trips == null) {
                assertEquals(name, case.getJSONArray("tripIds").strings().toSet(), snapshot.trips.keys)
            } else {
                assertEquals(name, trips.keySet(), snapshot.trips.keys)
                for (trip in trips.keySet()) {
                    val expected = trips.getJSONObject(trip)
                    val actual = snapshot.trips.getValue(trip).single()
                    assertEquals(name, expected.getInt("startDate"), yyyymmdd(actual.startDate))
                    assertEquals(name, expected.getBoolean("cancelled"), actual.cancelled)
                    assertEquals(name, expected.getJSONArray("stops").strings(), actual.stops.map { it.stopId })
                }
            }
            for (item in case.getJSONArray("expected").objects()) {
                val date = LocalDate.parse(item.getInt("serviceDate").toString(), DateTimeFormatter.BASIC_ISO_DATE)
                val scheduled = ZonedDateTime.ofInstant(Instant.ofEpochSecond(item.getLong("scheduled")), rome)
                val actual = snapshot.trip(item.getString("trip"), date)?.expectedAt(item.getString("stop"), scheduled, item.getBoolean("departure"))
                val result = item.optJSONObject("result")
                if (result == null) {
                    assertNull(name, actual)
                } else {
                    assertEquals(name, result.longOrNull("time"), actual?.expected?.toEpochSecond())
                    assertEquals(name, result.getBoolean("cancelled"), actual?.cancelled)
                }
            }
        }
    }
}
