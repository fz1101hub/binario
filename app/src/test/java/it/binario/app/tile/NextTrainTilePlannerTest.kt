package it.binario.app.tile

import it.binario.app.TestStringCatalogue
import it.binario.app.data.Direction
import it.binario.app.data.RailOperator
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextTrainTilePlannerTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val day = LocalDate.of(2026, 11, 4)
    private fun at(hour: Int, minute: Int, date: LocalDate = day) = ZonedDateTime.of(date.atTime(hour, minute), rome).toInstant()

    private val route = TileRoute(
        SavedRoute("casa", "Casa", Station("curated:roma", "Roma Termini", "Roma"), Station("curated:napoli", "Napoli Centrale", "Napoli")),
        Direction.OUTBOUND
    )

    private fun service(number: String, departure: String, category: TrainCategory = TrainCategory.REGIONALE, date: LocalDate? = day, bus: Boolean = false) = TrainService(
        id = number,
        number = number,
        operator = RailOperator.TRENITALIA_AV,
        trainCategory = category,
        origin = "Roma Termini",
        destination = "Napoli Centrale",
        scheduledDeparture = departure,
        expectedDeparture = departure,
        platform = null,
        delayMinutes = 0,
        status = ServiceStatus.ON_TIME,
        stops = emptyList(),
        operationalStatusVerified = false,
        scheduledDepartureDateTime = date?.let { LocalDateTime.of(it, java.time.LocalTime.parse(departure)) },
        scheduledBus = bus
    )

    private val strings = TileStrings(
        locale = Locale.ITALIAN,
        title = TestStringCatalogue.of(null).getValue("tile_next_train_label"),
        noRoute = TestStringCatalogue.of(null).getValue("tile_next_train_no_route"),
        noTrain = TestStringCatalogue.of(null).getValue("tile_next_train_none"),
        departure = TestStringCatalogue.of(null).getValue("tile_next_train_departure"),
        train = TestStringCatalogue.of(null).getValue("service_category_number"),
        description = TestStringCatalogue.of(null).getValue("tile_next_train_description"),
        categoryLabel = { if (it == TrainCategory.REGIONALE) "Regionale" else "Frecce" }
    )

    @Test fun `the timetable keeps today's trains still to leave, earliest first, without coaches`() {
        val services = listOf(
            service("2153", "08:12"), service("9505", "07:55", TrainCategory.FRECCE), service("2155", "07:10"),
            service("B1", "07:58", bus = true), service("2157", "09:00", date = day.plusDays(1)), service("2159", "10:30", date = null)
        )
        val timetable = NextTrainTilePlanner.timetable(route.key, services, at(7, 40), rome)
        assertEquals(listOf("9505", "2153", "2159"), timetable.departures.map { it.number })
        assertEquals(listOf("07:55", "08:12", "10:30"), timetable.departures.map { it.time })
        assertEquals(day, timetable.serviceDate)
        assertEquals(at(7, 40).toEpochMilli(), timetable.computedAtEpochMillis)
    }

    @Test fun `the next train comes from the stored timetable while it lasts, of the same route and day`() {
        val timetable = NextTrainTilePlanner.timetable(route.key, listOf(service("9505", "07:55", TrainCategory.FRECCE), service("2153", "08:12")), at(7, 40), rome)
        assertEquals("9505", NextTrainTilePlanner.next(timetable, route.key, at(7, 50), rome)?.number)
        assertEquals("2153", NextTrainTilePlanner.next(timetable, route.key, at(7, 56), rome)?.number)
        assertNull(NextTrainTilePlanner.next(timetable, route.key, at(8, 13), rome))
        assertNull(NextTrainTilePlanner.next(timetable, "other", at(7, 50), rome))
        assertNull(NextTrainTilePlanner.next(timetable, route.key, at(7, 50, day.plusDays(1)), rome))
    }

    @Test fun `the offline timetable is read again only for another route or day, or once it ran out`() {
        val timetable = NextTrainTilePlanner.timetable(route.key, listOf(service("2153", "08:12")), at(7, 40), rome)
        assertTrue(NextTrainTilePlanner.needsRefresh(null, route.key, at(7, 41), rome))
        assertFalse(NextTrainTilePlanner.needsRefresh(timetable, route.key, at(7, 41), rome))
        assertTrue(NextTrainTilePlanner.needsRefresh(timetable, "other", at(7, 41), rome))
        assertTrue(NextTrainTilePlanner.needsRefresh(timetable, route.key, at(0, 5, day.plusDays(1)), rome))
        // Ran out: not at every swipe, half an hour after it was read.
        val late = NextTrainTilePlanner.timetable(route.key, listOf(service("2153", "08:12")), at(8, 5), rome)
        assertFalse(NextTrainTilePlanner.needsRefresh(late, route.key, at(8, 13), rome))
        assertTrue(NextTrainTilePlanner.needsRefresh(late, route.key, at(8, 35), rome))
        assertTrue(NextTrainTilePlanner.needsRefresh(timetable, route.key, at(8, 13), rome))
    }

    @Test fun `the tile shows the next train with the route, or says why there is none`() {
        val timetable = NextTrainTilePlanner.timetable(route.key, listOf(service("2153", "08:12")), at(7, 40), rome)
        assertEquals(
            TileDisplay(
                label = "08:12 · Regionale 2153",
                subtitle = "Roma Termini → Napoli Centrale",
                description = "Prossimo treno dall’orario programmato: Regionale 2153 alle 08:12, Roma Termini → Napoli Centrale",
                active = true
            ),
            NextTrainTilePlanner.display(route, timetable, at(7, 50), strings, rome)
        )
        val none = NextTrainTilePlanner.display(route, timetable, at(9, 0), strings, rome)
        assertEquals(TileDisplay("Prossimo treno", "Nessun altro treno oggi", "Prossimo treno. Nessun altro treno oggi", false), none)
        assertEquals("Nessun altro treno oggi", none.labelWithoutSubtitle)
        val full = NextTrainTilePlanner.timetable(
            route.key,
            (0 until NextTrainTilePlanner.MAX_DEPARTURES + 2).map { service("${2100 + it}", String.format(Locale.ROOT, "08:%02d", it)) },
            at(7, 40),
            rome
        )
        assertEquals(NextTrainTilePlanner.MAX_DEPARTURES, full.departures.size)
        // Every stored train has left, but the list was cut: later trains may exist, so only the route until it is read again.
        assertEquals("Roma Termini → Napoli Centrale", NextTrainTilePlanner.display(route, full, at(9, 0), strings, rome).subtitle)
        val reading = NextTrainTilePlanner.display(route, null, at(9, 0), strings, rome)
        assertEquals("Roma Termini → Napoli Centrale", reading.subtitle)
        assertFalse(reading.active)
        val noRoute = NextTrainTilePlanner.display(null, timetable, at(7, 50), strings, rome)
        assertEquals(TileDisplay("Prossimo treno", "Nessuna tratta attiva", "Prossimo treno. Nessuna tratta attiva", false), noRoute)
        assertEquals("08:12 · Regionale 2153", NextTrainTilePlanner.display(route, timetable, at(7, 50), strings, rome).labelWithoutSubtitle)
    }

    @Test fun `route and timetable round trip, the return direction is another key`() {
        assertEquals(route, NextTrainTileCodec.decodeRoute(NextTrainTileCodec.encodeRoute(route)))
        assertTrue(route.key != route.copy(direction = Direction.RETURN).key)
        assertEquals("Napoli Centrale → Roma Termini", route.copy(direction = Direction.RETURN).label)
        val timetable = NextTrainTilePlanner.timetable(route.key, listOf(service("9505", "07:55", TrainCategory.FRECCE), service("2153", "08:12")), at(7, 40), rome)
        assertEquals(timetable, NextTrainTileCodec.decodeTimetable(NextTrainTileCodec.encodeTimetable(timetable)))
        assertNull(NextTrainTileCodec.decodeTimetable("{}"))
        assertNull(NextTrainTileCodec.decodeRoute(null))
    }
}
