package it.binario.app.maps

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.core.Confidence
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.SourceRef
import it.binario.app.data.core.SourcedValue
import it.binario.app.data.core.StationBoardEntry
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.journey.ScheduledRailRun
import it.binario.app.data.journey.ScheduledRunOrigin
import it.binario.app.data.journey.ScheduledRunStop
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrainPositionsTest {
    private val day = LocalDate.of(2026, 11, 4)
    private val alfa = GeoPoint(45.0, 9.0)
    private val beta = GeoPoint(45.3, 9.3)
    private val gamma = GeoPoint(45.6, 9.6)
    private val everywhere = GeoBounds(north = 60.0, south = 30.0, east = 20.0, west = 0.0)

    private fun stop(name: String, point: GeoPoint?, arrival: Int, departure: Int = arrival) =
        ScheduledRunStop(name, point?.latitude, point?.longitude, arrival, departure)

    /** Alfa 10:00 → Beta 10:30/10:32 → Gamma 11:00. */
    private val stops = listOf(
        stop("Alfa", alfa, 600),
        stop("Beta", beta, 630, 632),
        stop("Gamma", gamma, 660)
    )

    private fun run(
        number: String = "2153",
        runStops: List<ScheduledRunStop> = stops,
        date: LocalDate = day,
        id: String = "pack:$number:$date",
        line: String? = "REG",
        category: TrainCategory = TrainCategory.REGIONALE
    ) = ScheduledRailRun(id, date, number, line, category, RailOperator.TRENITALIA_REGIONALE, "Trenitalia", runStops)

    private fun at(time: String, date: LocalDate = day): LocalDateTime = LocalDateTime.of(date, LocalTime.parse(time))

    private fun assertPoint(expected: GeoPoint, actual: GeoPoint?) {
        assertEquals(expected.latitude, actual!!.latitude, 1e-9)
        assertEquals(expected.longitude, actual.longitude, 1e-9)
    }

    @Test
    fun `before the departure a run is not under way and has no position`() {
        assertEquals(RunProgress.NotStarted, LiveTrainEstimator.progress(stops, 599.5))
        assertNull(LiveTrainEstimator.position(stops, 599.5))
    }

    @Test
    fun `at its stops a run sits on the station, the dwell included`() {
        assertEquals(RunProgress.AtStop(0), LiveTrainEstimator.progress(stops, 600.0))
        assertPoint(alfa, LiveTrainEstimator.position(stops, 600.0))
        assertEquals(RunProgress.AtStop(1), LiveTrainEstimator.progress(stops, 631.0))
        assertPoint(beta, LiveTrainEstimator.position(stops, 630.0))
        assertPoint(beta, LiveTrainEstimator.position(stops, 632.0))
        assertEquals(RunProgress.AtStop(2), LiveTrainEstimator.progress(stops, 660.0))
        assertPoint(gamma, LiveTrainEstimator.position(stops, 660.0))
    }

    @Test
    fun `between two stops the position is on the straight segment in proportion to the time`() {
        val progress = LiveTrainEstimator.progress(stops, 615.0) as RunProgress.Between
        assertEquals(0, progress.from)
        assertEquals(1, progress.to)
        assertEquals(0.5, progress.fraction, 1e-9)
        assertPoint(GeoPoint(45.15, 9.15), LiveTrainEstimator.position(stops, 615.0))
        // 10:39: a quarter of the 28 minutes from Beta's departure to Gamma.
        assertPoint(GeoPoint(45.375, 9.375), LiveTrainEstimator.position(stops, 639.0))
    }

    @Test
    fun `after the arrival at the last stop a run is over`() {
        assertEquals(RunProgress.Arrived, LiveTrainEstimator.progress(stops, 660.5))
        assertNull(LiveTrainEstimator.position(stops, 700.0))
    }

    @Test
    fun `a stop without coordinates is bridged by its located neighbours`() {
        val unlocated = listOf(stop("Alfa", alfa, 600), stop("Beta", null, 630, 632), stop("Gamma", gamma, 660))
        // The progress still names Beta; the point is on the line Alfa–Gamma, 31 of 60 minutes along.
        assertEquals(RunProgress.AtStop(1), LiveTrainEstimator.progress(unlocated, 631.0))
        val point = LiveTrainEstimator.position(unlocated, 631.0)!!
        assertEquals(45.0 + 0.6 * 31 / 60, point.latitude, 1e-9)
        // Without any located stop after the train there is no estimate.
        assertNull(LiveTrainEstimator.position(listOf(stop("Alfa", alfa, 600), stop("Beta", null, 630)), 610.0))
    }

    @Test
    fun `a run of yesterday's service day keeps moving after midnight`() {
        val night = run(
            number = "1959",
            runStops = listOf(stop("Alfa", alfa, 23 * 60 + 50), stop("Gamma", gamma, 24 * 60 + 20)),
            date = day
        )
        val now = at("00:05", day.plusDays(1))
        assertEquals(1445.0, LiveTrainEstimator.minuteOfServiceDay(day, now), 1e-9)
        val selection = LiveTrainSelector.select(listOf(night), emptyList(), now, everywhere, alfa)
        val train = selection.trains.single()
        assertEquals(0.5, (train.progress as RunProgress.Between).fraction, 1e-9)
        assertPoint(GeoPoint(45.3, 9.3), train.point)
        // A run of the new service day that leaves at 00:30 has not started yet.
        val early = run(number = "1961", runStops = listOf(stop("Alfa", alfa, 30), stop("Gamma", gamma, 60)), date = day.plusDays(1))
        assertTrue(LiveTrainSelector.select(listOf(early), emptyList(), now, everywhere, alfa).trains.isEmpty())
    }

    @Test
    fun `a night train read from the evening is matched from its true origin`() {
        // Left Alfa at 19:00; the map read yesterday's service day only from Beta at 20:30.
        val evening = listOf(stop("Beta", beta, 20 * 60 + 30), stop("Gamma", gamma, 22 * 60))
        val withOrigin = run(number = "1951", runStops = evening).copy(origin = ScheduledRunOrigin("Alfa", 19 * 60))
        val seenAtOrigin = listOf(LiveRunObservation("1951", at("19:00"), delayMinutes = 6))
        val now = at("21:00")
        assertEquals(6, LiveTrainSelector.select(listOf(withOrigin), seenAtOrigin, now, everywhere, alfa).trains.single().delayMinutes)
        // Without the origin, an observation from 19:00 falls before the run's span and is not this train's.
        assertNull(LiveTrainSelector.select(listOf(run(number = "1951", runStops = evening)), seenAtOrigin, now, everywhere, alfa).trains.single().delayMinutes)
        assertEquals("Alfa", withOrigin.originName)
        assertEquals("Beta", run(number = "1951", runStops = evening).originName)
    }

    @Test
    fun `a live delay shifts the timetable and keeps a late run on the map after its scheduled arrival`() {
        val delayed = listOf(LiveRunObservation("2153", at("10:00"), delayMinutes = 10, ref = 3))
        val train = LiveTrainSelector.select(listOf(run()), delayed, at("10:25"), everywhere, alfa).trains.single()
        assertPoint(GeoPoint(45.15, 9.15), train.point)
        assertEquals(10, train.delayMinutes)
        assertEquals(LiveTrainEstimate.LIVE_DELAY, train.estimate)
        assertEquals(3, train.observation?.ref)
        // 11:05: on time it has arrived; ten minutes late it is still five minutes from Gamma.
        assertTrue(LiveTrainSelector.select(listOf(run()), emptyList(), at("11:05"), everywhere, alfa).trains.isEmpty())
        val late = LiveTrainSelector.select(listOf(run()), delayed, at("11:05"), everywhere, alfa).trains.single()
        assertEquals(RunProgress.Between(1, 2, 23.0 / 28.0), late.progress)
        // A delayed run that has not left yet is not drawn as moving.
        assertTrue(LiveTrainSelector.select(listOf(run()), delayed, at("10:05"), everywhere, alfa).trains.isEmpty())
    }

    @Test
    fun `without a delay the estimate comes from the timetable alone`() {
        val noDelay = listOf(LiveRunObservation("2153", at("10:00"), delayMinutes = null, ref = 0))
        val train = LiveTrainSelector.select(listOf(run()), noDelay, at("10:15"), everywhere, alfa).trains.single()
        assertNull(train.delayMinutes)
        assertEquals(LiveTrainEstimate.TIMETABLE, train.estimate)
        assertEquals(0, train.observation?.ref)
        assertEquals("Alfa" to "Beta", train.between)
        assertEquals("Gamma", train.destination)
    }

    @Test
    fun `an observation of the same number on another day or a cancelled run does not move the train`() {
        val yesterday = listOf(LiveRunObservation("2153", at("10:00", day.minusDays(1)), delayMinutes = 30))
        val train = LiveTrainSelector.select(listOf(run()), yesterday, at("10:15"), everywhere, alfa).trains.single()
        assertNull(train.observation)
        assertNull(train.delayMinutes)
        val cancelled = listOf(LiveRunObservation("2153", at("10:00"), delayMinutes = null, cancelled = true))
        assertTrue(LiveTrainSelector.select(listOf(run()), cancelled, at("10:15"), everywhere, alfa).trains.isEmpty())
        // A source writing Trenord's "RE_11 - 2153" means the timetable's 2153.
        val trenord = LiveRunObservation("RE_11 - 2153", at("10:00"), delayMinutes = 4)
        assertEquals(4, LiveTrainSelector.select(listOf(run()), listOf(trenord), at("10:15"), everywhere, alfa).trains.single().delayMinutes)
        // A delay beyond what the timetable window can follow is not applied.
        val absurd = listOf(LiveRunObservation("2153", at("10:00"), delayMinutes = 900))
        assertNull(LiveTrainSelector.select(listOf(run()), absurd, at("10:15"), everywhere, alfa).trains.single().delayMinutes)
    }

    @Test
    fun `only runs placed inside the visible area are selected`() {
        val north = GeoBounds(north = 45.4, south = 45.2, east = 9.4, west = 9.2)
        // 10:15 the train is at 45.15, south of the box; 10:31 it is at Beta, inside.
        assertTrue(LiveTrainSelector.select(listOf(run()), emptyList(), at("10:15"), north, beta).trains.isEmpty())
        assertEquals(1, LiveTrainSelector.select(listOf(run()), emptyList(), at("10:31"), north, beta).trains.size)
        assertTrue(north.contains(beta))
        assertFalse(north.contains(GeoPoint(Double.NaN, 9.3)))
    }

    @Test
    fun `the selection keeps the nearest trains up to the cap and counts the others`() {
        // Five runs leaving Alfa one minute apart towards Gamma: at 10:10 the earliest is the farthest from Alfa.
        val runs = (0 until 5).map { offset ->
            run(number = "${100 + offset}", runStops = listOf(stop("Alfa", alfa, 600 + offset), stop("Gamma", gamma, 660 + offset)))
        }
        val selection = LiveTrainSelector.select(runs, emptyList(), at("10:10"), everywhere, alfa, cap = 3)
        assertEquals(listOf("104", "103", "102"), selection.trains.map { it.run.publicNumber })
        assertEquals(5, selection.inBounds)
        assertTrue(selection.capped)
        assertTrue(selection.trains.zipWithNext().all { (near, far) -> near.distanceKm <= far.distanceKm })
        assertFalse(LiveTrainSelector.select(runs, emptyList(), at("10:10"), everywhere, alfa).capped)
        assertEquals(LiveTrainSelector.MAX_TRAINS, 40)
    }

    @Test
    fun `two packs' copies of one train are drawn once, the one with the live delay first`() {
        val copy = run(id = "other-pack:2153:$day")
        val observations = listOf(LiveRunObservation("2153", at("10:00"), delayMinutes = 0))
        val selection = LiveTrainSelector.select(listOf(run(), copy), observations, at("10:15"), everywhere, alfa)
        assertEquals(1, selection.trains.size)
        // Far apart, two trains with one number (two countries) both stay.
        val elsewhere = run(id = "far:2153:$day", runStops = stops.map { it.copy(latitude = it.latitude!! - 5) })
        assertEquals(2, LiveTrainSelector.select(listOf(run(), elsewhere), emptyList(), at("10:15"), everywhere, alfa).trains.size)
    }

    @Test
    fun `marker labels carry category, number and delay in machine text`() {
        fun train(delay: Int?, line: String? = "REG", category: TrainCategory = TrainCategory.REGIONALE) =
            LiveTrain(run(line = line, category = category), alfa, RunProgress.AtStop(0), delay, null, 0.0)
        assertEquals("REG 2153 +5'", LiveTrainLabels.marker(train(5)))
        assertEquals("REG 2153 0'", LiveTrainLabels.marker(train(0)))
        assertEquals("REG 2153 -2'", LiveTrainLabels.marker(train(-2)))
        assertEquals("REG 2153", LiveTrainLabels.marker(train(null)))
        assertEquals("IC 2153", LiveTrainLabels.marker(train(null, line = null, category = TrainCategory.INTERCITY)))
        assertEquals("2153", LiveTrainLabels.marker(train(null, line = "2153", category = TrainCategory.OTHER)))
    }

    @Test
    fun `a station's area is about fifteen kilometres around it and is read again only when left`() {
        val area = LiveTrainAreas.around(GeoPoint(45.4864, 9.2042))
        assertEquals(0.27, area.north - area.south, 0.01)
        assertTrue(area.east - area.west > area.north - area.south)
        assertFalse(LiveTrainAreas.tooWide(area))
        assertTrue(LiveTrainAreas.tooWide(GeoBounds(47.0, 44.0, 12.0, 8.0)))
        val read = LiveTrainAreas.readArea(area)
        assertTrue(LiveTrainAreas.covers(read, area))
        assertEquals(0.0, Math.IEEEremainder(read.north, 0.25), 1e-9)
        val panned = GeoBounds(area.north + 0.05, area.south + 0.05, area.east, area.west)
        assertTrue(LiveTrainAreas.covers(read, panned))
        val far = GeoBounds(area.north + 1.0, area.south + 1.0, area.east, area.west)
        assertFalse(LiveTrainAreas.covers(read, far))
        val route = LiveTrainAreas.routeArea(alfa, gamma)
        assertTrue(route.contains(alfa) && route.contains(gamma) && route.contains(beta))
    }

    @Test
    fun `the timetable is read once per quarter of an hour`() {
        assertEquals(at("10:15"), LiveTrainRefresh.slotStart(LocalDateTime.of(day, LocalTime.of(10, 29, 59))))
        assertEquals(at("00:00"), LiveTrainRefresh.slotStart(LocalDateTime.of(day, LocalTime.of(0, 14))))
    }

    @Test
    fun `route board services give their verified delay, demonstration data none`() {
        val service = TrainService(
            id = "s1", number = "2153", operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.REGIONALE,
            origin = "Alfa", destination = "Gamma", scheduledDeparture = "10:00", expectedDeparture = "10:07", platform = null,
            delayMinutes = 7, status = ServiceStatus.DELAYED, stops = emptyList()
        )
        val scheduledOnly = service.copy(id = "s2", operationalStatusVerified = false, scheduledDepartureDateTime = at("11:00"))
        val cancelled = service.copy(id = "s3", status = ServiceStatus.CANCELLED)
        val observations = LiveTrainObservations.fromServices(listOf(service, scheduledOnly, cancelled), liveTrusted = true, today = day)
        assertEquals(LiveRunObservation("2153", at("10:00"), 7, false, 0), observations[0])
        assertEquals(LiveRunObservation("2153", at("11:00"), null, false, 1), observations[1])
        assertTrue(observations[2].cancelled)
        assertTrue(LiveTrainObservations.fromServices(listOf(service, cancelled), liveTrusted = false, today = day).all { it.delayMinutes == null && !it.cancelled })
        // After a refresh that reorders the board, a tap still opens the same row.
        val refreshed = LiveTrainObservations.fromServices(listOf(scheduledOnly, service), liveTrusted = true, today = day)
        assertEquals(1, LiveTrainObservations.rowOf(observations[0], refreshed))
        assertNull(LiveTrainObservations.rowOf(observations[2].copy(scheduledAt = at("12:00")), refreshed))
    }

    @Test
    fun `station board rows give the expected minus the scheduled time`() {
        val zone = ZoneId.of("Europe/Rome")
        val source = SourceRef(SourceId("board"), fetchedAt = Instant.parse("2026-11-04T09:00:00Z"), confidence = Confidence.OPEN_DATA, traceId = "t")
        val scheduled = at("10:00").atZone(zone)
        fun entry(number: String?, expected: Int?, status: RunStatus? = null) = StationBoardEntry(
            runId = TrainRunId("r$number"), scheduledTime = scheduled,
            expectedTime = expected?.let { SourcedValue(scheduled.plusMinutes(it.toLong()), source) },
            destinationOrOrigin = StationId("x"), publicNumber = number, status = status?.let { SourcedValue(it, source) }
        )
        val observations = LiveTrainObservations.fromBoard(listOf(entry("9558", 12), entry(null, 3), entry("2153", null, RunStatus.CANCELLED)))
        assertEquals(LiveRunObservation("9558", at("10:00"), 12, false, 0), observations[0])
        assertEquals(2, observations.size)
        assertEquals(2, observations[1].ref)
        assertTrue(observations[1].cancelled)
        assertNull(observations[1].delayMinutes)
    }
}
