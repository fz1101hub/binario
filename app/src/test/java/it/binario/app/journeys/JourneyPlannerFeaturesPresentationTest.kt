package it.binario.app.journeys

import it.binario.app.LocalBackupResult
import it.binario.app.LocalDataBackup
import it.binario.app.LocalDataBackupCodec
import it.binario.app.MobilityPreferences
import it.binario.app.TravelPreferencesCodec
import it.binario.app.data.GtfsServiceIdentity
import it.binario.app.data.RailOperator
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.Freshness
import it.binario.app.data.core.InSeatContinuation
import it.binario.app.data.core.JourneyId
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.LegWheelchairAccess
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.StationId
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.journey.StaticDayDepartures
import it.binario.app.data.journey.StaticDeparture
import it.binario.app.data.provider.JourneyPreferences
import it.binario.app.data.provider.JourneyRanking
import it.binario.app.data.provider.TravelMode
import it.binario.app.data.provider.WalkingSpeed
import it.binario.app.data.repository.JourneySearchResult
import it.binario.app.data.repository.JourneySearchStatus
import it.binario.app.data.repository.SourcedJourneyOption
import it.binario.app.ui.planner.ResultEntry
import it.binario.app.ui.planner.ResultsMerge
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The results side of "Resta a bordo", travel preferences, wheelchair access and "Ultimo di oggi". */
class JourneyPlannerFeaturesPresentationTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-11-02T08:00:00Z")
    private val day = LocalDate.of(2026, 11, 10)

    private fun leg(
        number: String?,
        line: String?,
        departs: String,
        arrives: String,
        mode: LegMode = LegMode.TRAIN,
        kind: ServiceKind = ServiceKind.REGIONAL,
        operator: String? = "SAD",
        serviceDate: LocalDate = day,
        plusDays: Long = 0,
        wheelchair: LegWheelchairAccess? = null,
        continuations: List<InSeatContinuation> = emptyList()
    ) = JourneyLeg(
        mode = mode,
        origin = StationId("curated:A"),
        destination = StationId("curated:B"),
        scheduledDeparture = LocalDateTime.of(day.plusDays(plusDays), LocalTime.parse(departs)).atZone(zone),
        scheduledArrival = LocalDateTime.of(day.plusDays(plusDays), LocalTime.parse(arrives)).atZone(zone),
        publicNumber = number,
        lineLabel = line,
        originLabel = "Alfa",
        destinationLabel = "Beta",
        serviceKind = kind,
        operatorName = operator,
        serviceDate = serviceDate,
        wheelchair = wheelchair,
        continuations = continuations
    )

    private fun option(id: String, vararg legs: JourneyLeg) = SourcedJourneyOption(
        option = JourneyOption(
            id = JourneyId(id), origin = legs.first().origin, destination = legs.last().destination,
            departure = legs.first().scheduledDeparture, arrival = legs.last().scheduledArrival, legs = legs.toList(),
            snapshot = SnapshotMeta(1, now, freshness = Freshness.AGING, completeness = Completeness.STATIC_ONLY, contributingSources = emptySet())
        ),
        sourceId = EmbeddedGtfsJourneyProvider.SOURCE_ID,
        sourceName = "Offline",
        scheduledOnly = true
    )

    private fun result(vararg options: SourcedJourneyOption) =
        JourneySearchResult(JourneySearchStatus.FOUND, options.toList(), listOf(EmbeddedGtfsJourneyProvider.SOURCE_ID), false, now)

    private fun continuation(number: String?, line: String?, operator: String? = "SAD") = InSeatContinuation(
        station = StationId("curated:BR"), stationLabel = "Brennero", runId = null, publicNumber = number, lineLabel = line, operatorName = operator
    )

    // --- "Resta a bordo" ---

    @Test fun `a continuation under another label is a note and one under the same label is none`() {
        val row = JourneyResultsPresenter.row(option("a", leg("1826", "REG", "08:00", "09:30", continuations = listOf(
            continuation("1826", "REX"),
            continuation("1826", "REX")
        ))), zone)
        assertEquals(0, row.changes)
        val note = row.legs.single().continuations.single()
        assertEquals("REX" to "1826", note.lineLabel to note.number)
        assertEquals("Brennero", note.stationLabel)
        // The same number and line continuing in another pack is one train: no note.
        val same = JourneyResultsPresenter.row(option("b", leg("88", "EC", "08:00", "09:30", continuations = listOf(continuation("88", "EC")))), zone)
        assertTrue(same.legs.single().continuations.isEmpty())
        // Another operator is worth saying.
        val operator = JourneyResultsPresenter.row(option("c", leg("88", "EC", "08:00", "09:30", continuations = listOf(continuation("88", "EC", "ÖBB")))), zone)
        assertEquals("ÖBB", operator.legs.single().continuations.single().operatorName)
    }

    // --- Wheelchair access ---

    @Test fun `a journey is accessible only when every leg and walk is published accessible`() {
        val yes = LegWheelchairAccess(vehicle = true, boarding = true, alighting = true)
        val unknownStop = LegWheelchairAccess(vehicle = true, boarding = true, alighting = false)
        val accessible = JourneyResultsPresenter.row(option("a",
            leg("1", "R", "08:00", "08:30", wheelchair = yes),
            leg(null, null, "08:30", "08:36", mode = LegMode.WALK, wheelchair = yes),
            leg("2", "R", "08:40", "09:00", wheelchair = yes)
        ), zone)
        assertTrue(accessible.wheelchairAccessible)
        assertTrue(accessible.legs.first().wheelchairTrip)
        assertFalse(accessible.legs[1].wheelchairTrip)
        assertEquals(6L, accessible.walkMinutes)
        assertFalse(JourneyResultsPresenter.row(option("b", leg("1", "R", "08:00", "08:30", wheelchair = unknownStop)), zone).wheelchairAccessible)
        // No data at all is never accessible.
        val none = JourneyResultsPresenter.row(option("c", leg("1", "R", "08:00", "08:30")), zone)
        assertFalse(none.wheelchairAccessible)
        assertFalse(none.legs.single().wheelchairTrip)
    }

    // --- "Ultimo di oggi" ---

    @Test fun `the latest option of the day is the last only when the next day is listed`() {
        val evening = option("e1", leg("100", "R", "22:00", "22:40"))
        val night = option("e2", leg("102", "R", "23:30", "23:59"))
        val tomorrow = option("t1", leg("104", "R", "06:05", "06:45", serviceDate = day.plusDays(1), plusDays = 1))
        val marked = JourneyResultsPresenter.present(result(evening, night, tomorrow), zone = zone, serviceDate = day)
        assertEquals(listOf("e2"), marked.rows.filter { it.lastOfDay }.map { it.id })
        assertNull(marked.firstDepartureNextDay)
        // Without an option of the next day nothing proves that no later train leaves.
        val unproven = JourneyResultsPresenter.present(result(evening, night), zone = zone, serviceDate = day)
        assertTrue(unproven.rows.none { it.lastOfDay })
        // Without a requested day (another day than today) nothing is marked.
        assertTrue(JourneyResultsPresenter.present(result(evening, night, tomorrow), zone = zone).rows.none { it.lastOfDay })
        // After the last train: "primo domani alle 06:05".
        val afterLast = JourneyResultsPresenter.present(result(tomorrow), zone = zone, serviceDate = day)
        assertEquals("06:05", afterLast.firstDepartureNextDay)
        assertTrue(afterLast.rows.none { it.lastOfDay })
        // A run after midnight of the same service day still belongs to the day.
        val afterMidnight = option("e3", leg("106", "R", "00:20", "00:50", serviceDate = day, plusDays = 1))
        val late = JourneyResultsPresenter.present(result(night, afterMidnight, tomorrow), zone = zone, serviceDate = day)
        assertEquals(listOf("e3"), late.rows.filter { it.lastOfDay }.map { it.id })
    }

    private fun train(number: String, departure: String, category: TrainCategory = TrainCategory.REGIONALE, gtfsDay: LocalDate? = null, plusDays: Long = 0) = TrainService(
        "train:$number", number, RailOperator.OTHER, category, "Alfa", "Beta",
        departure, departure, null, 0, ServiceStatus.ON_TIME,
        listOf(TrainStop("Alfa", departure, departure, null, 0, false), TrainStop("Beta", departure, departure, null, 0, false)),
        operationalStatusVerified = false,
        scheduledDepartureDateTime = day.plusDays(plusDays).atTime(LocalTime.parse(departure)),
        gtfsIdentity = gtfsDay?.let { GtfsServiceIdentity("src", "trip-$number", it, emptyList()) }
    )

    @Test fun `the merged results keep the mark on the direct train and drop it when a later train is listed`() {
        val night = option("e2", leg("102", "R", "23:30", "23:59"))
        val tomorrow = option("t1", leg("104", "R", "06:05", "06:45", serviceDate = day.plusDays(1), plusDays = 1))
        val rows = JourneyResultsPresenter.present(result(night, tomorrow), zone = zone, serviceDate = day).rows
        // The last journey is the direct train 102 of the route search: the mark moves to its card.
        val merged = ResultsMerge.entries(listOf(train("102", "23:30")), rows, day)
        assertEquals(listOf("train-train:102"), merged.filter { it.lastOfDay }.map { it.key })
        // A live train later the same day, unknown to the offline timetable: the mark cannot be confirmed.
        val later = ResultsMerge.entries(listOf(train("102", "23:30"), train("990", "23:50")), rows, day)
        assertTrue(later.none { it.lastOfDay })
        // Nothing left today: the earliest entry, a timetable train of the next day, gives "primo domani".
        val onlyTomorrow = JourneyResultsPresenter.present(result(tomorrow), zone = zone, serviceDate = day).rows
        val first = ResultsMerge.entries(listOf(train("900", "05:50", gtfsDay = day.plusDays(1), plusDays = 1)), onlyTomorrow, day)
        assertEquals(LocalDateTime.of(day.plusDays(1), LocalTime.of(5, 50)), ResultsMerge.firstDepartureNextDay(first, onlyTomorrow, day))
        assertNull(ResultsMerge.firstDepartureNextDay(merged, rows, day))
    }

    @Test fun `board rows are marked by number and minute of the static last departures`() {
        val static = StaticDayDepartures(
            lastByTerminus = listOf(StaticDeparture(LocalDateTime.of(day, LocalTime.of(22, 30)), "REG 104", "Merano")),
            firstNextDay = StaticDeparture(LocalDateTime.of(day.plusDays(1), LocalTime.of(5, 40)), "100", "Merano")
        )
        assertTrue(LastOfDayMarks.isLast(static, "104", LocalDateTime.of(day, LocalTime.of(22, 30))))
        assertFalse(LastOfDayMarks.isLast(static, "104", LocalDateTime.of(day, LocalTime.of(22, 31))))
        assertFalse(LastOfDayMarks.isLast(static, "106", LocalDateTime.of(day, LocalTime.of(22, 30))))
        assertFalse(LastOfDayMarks.isLast(null, "104", LocalDateTime.of(day, LocalTime.of(22, 30))))
        assertNull(LastOfDayMarks.firstNextDay(static, LocalDateTime.of(day, LocalTime.of(22, 0))))
        assertEquals("100", LastOfDayMarks.firstNextDay(static, LocalDateTime.of(day, LocalTime.of(23, 0)))?.publicNumber)
        assertNull(LastOfDayMarks.firstNextDay(StaticDayDepartures(emptyList(), null), LocalDateTime.of(day, LocalTime.of(23, 0))))
    }

    // --- Travel preferences in the results ---

    @Test fun `preferences open the matching chip, hide avoided direct trains and break ties`() {
        assertEquals(JourneyResultsFilter.ALL, JourneyResultsFilter.forRanking(JourneyRanking.FASTEST))
        assertEquals(JourneyResultsFilter.FEWEST_CHANGES, JourneyResultsFilter.forRanking(JourneyRanking.FEWEST_CHANGES))
        assertEquals(JourneyResultsFilter.LEAST_WALKING, JourneyResultsFilter.forRanking(JourneyRanking.LEAST_WALKING))

        val italo = train("9901", "08:00", TrainCategory.AV_ITALO)
        val regional = train("2001", "08:10")
        val unknown = train("123", "08:20", TrainCategory.OTHER)
        val avoidItalo = JourneyPreferences(avoidedModes = setOf(TravelMode.ITALO))
        assertEquals(listOf(regional, unknown), ResultsMerge.shownServices(listOf(italo, regional, unknown), avoidItalo))
        assertTrue(ResultsMerge.shownServices(listOf(italo, regional), JourneyPreferences(wheelchairOnly = true)).isEmpty())
        assertEquals(3, ResultsMerge.shownServices(listOf(italo, regional, unknown), null).size)

        // Two options of the same duration: the one on a preferred kind first.
        val walking = option("w", leg("5", "R", "08:00", "08:20"), leg(null, null, "08:20", "08:30", mode = LegMode.WALK), leg("6", "M1", "08:30", "09:00", mode = LegMode.METRO, kind = ServiceKind.URBAN))
        val train = option("t", leg("7", "R", "08:05", "09:05", kind = ServiceKind.INTERCITY))
        val rows = JourneyResultsPresenter.present(result(walking, train), zone = zone).rows
        fun order(filter: JourneyResultsFilter, preferences: JourneyPreferences?) = JourneyResultsPresenter.apply(rows, filter, preferences).map { it.id }
        assertEquals(listOf("t", "w"), order(JourneyResultsFilter.LEAST_WALKING, null))
        assertEquals(listOf("w", "t"), order(JourneyResultsFilter.FASTEST, JourneyPreferences(preferredModes = setOf(TravelMode.METRO))))
        assertEquals(listOf("t", "w"), order(JourneyResultsFilter.FASTEST, JourneyPreferences(preferredModes = setOf(TravelMode.INTERCITY))))
        assertEquals(setOf(TravelMode.REGIONAL, TravelMode.METRO), rows.single { it.id == "w" }.modes)
        // "Tutti" stays in order of departure whatever the preferences.
        assertEquals(listOf("w", "t"), order(JourneyResultsFilter.ALL, JourneyPreferences(preferredModes = setOf(TravelMode.INTERCITY))))
        val merged = ResultsMerge.entries(emptyList(), rows, day)
        assertEquals(listOf("journey-t", "journey-w"), ResultsMerge.apply(merged, JourneyResultsFilter.LEAST_WALKING).map(ResultEntry::key))
    }

    @Test fun `travel preferences survive the codec and the local backup and tolerate unknown values`() {
        val preferences = JourneyPreferences(
            walkingSpeed = WalkingSpeed.SLOW, maxWalkMinutes = 15, ranking = JourneyRanking.LEAST_WALKING,
            preferredModes = setOf(TravelMode.REGIONAL), avoidedModes = setOf(TravelMode.ITALO, TravelMode.OPERATOR_BUS), wheelchairOnly = true
        )
        assertEquals(preferences, TravelPreferencesCodec.fromJson(TravelPreferencesCodec.toJson(preferences)))
        val odd = TravelPreferencesCodec.fromJson(JSONObject("""{"walkingSpeed":"RUNNING","maxWalkMinutes":7,"ranking":"?","preferredModes":["METRO","ROCKET"],"avoidedModes":["METRO"]}"""))
        assertEquals(JourneyPreferences(preferredModes = setOf(TravelMode.METRO)), odd)
        assertEquals(JourneyPreferences(), TravelPreferencesCodec.decode("not json"))
        assertEquals(JourneyPreferences(), TravelPreferencesCodec.decode(null))

        val alfa = Station("curated:A", "Alfa", "Alfa")
        val beta = Station("curated:B", "Beta", "Beta")
        val backup = LocalDataBackup(
            listOf(SavedRoute("route", "Casa", alfa, beta)), emptySet(), emptyList(), emptyList(), emptyList(), MobilityPreferences(),
            travelPreferences = preferences
        )
        val decoded = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(backup), listOf(alfa, beta)) as LocalBackupResult.Success
        assertEquals(preferences, decoded.backup.travelPreferences)
        // A file without them keeps the device's preferences.
        val older = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(backup.copy(travelPreferences = null)), listOf(alfa, beta)) as LocalBackupResult.Success
        assertNull(older.backup.travelPreferences)
    }
}
