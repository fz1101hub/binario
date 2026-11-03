package it.binario.app.widget

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.core.OperationalRouteChange
import it.binario.app.data.core.RouteChangeKind
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.ServiceKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTrainTypesTest {
    @Test
    fun `route categories and operators map onto the exclusion groups`() {
        assertEquals(TrainTypeGroup.HIGH_SPEED, WidgetTrainTypes.groupOf(TrainCategory.FRECCE, RailOperator.TRENITALIA_AV))
        assertEquals(TrainTypeGroup.ITALO, WidgetTrainTypes.groupOf(TrainCategory.AV_ITALO, RailOperator.ITALO))
        assertEquals(TrainTypeGroup.ITALO, WidgetTrainTypes.groupOf(TrainCategory.OTHER, RailOperator.ITALO))
        assertEquals(TrainTypeGroup.INTERCITY_NIGHT, WidgetTrainTypes.groupOf(TrainCategory.INTERCITY, RailOperator.TRENITALIA_LONG_DISTANCE))
        assertEquals(TrainTypeGroup.REGIONAL_SUBURBAN, WidgetTrainTypes.groupOf(TrainCategory.REGIONALE_VELOCE, RailOperator.TRENITALIA_REGIONALE))
        assertEquals(TrainTypeGroup.REGIONAL_SUBURBAN, WidgetTrainTypes.groupOf(TrainCategory.TRENORD_REGIO, RailOperator.TRENORD))
        assertEquals(TrainTypeGroup.REGIONAL_SUBURBAN, WidgetTrainTypes.groupOf(TrainCategory.REGIONALE, RailOperator.TILO))
        assertEquals(TrainTypeGroup.INTERNATIONAL, WidgetTrainTypes.groupOf(TrainCategory.OTHER, RailOperator.OEBB))
        assertEquals(TrainTypeGroup.INTERNATIONAL, WidgetTrainTypes.groupOf(TrainCategory.FRECCE, RailOperator.SNCF))
        assertEquals(TrainTypeGroup.REPLACEMENT_BUS, WidgetTrainTypes.groupOf(TrainCategory.REGIONALE, RailOperator.TRENITALIA_REGIONALE, replacementService = true))
        assertTrue(WidgetTrainTypes.isReplacement(RouteChangeKind.REPLACEMENT_SERVICE))
        assertFalse(WidgetTrainTypes.isReplacement(RouteChangeKind.DIVERTED))
    }

    @Test
    fun `board entries map through service kind, operator, line label and status`() {
        assertEquals(TrainTypeGroup.REPLACEMENT_BUS, WidgetTrainTypes.groupOf(ServiceKind.REGIONAL, "trenitalia", null, RunStatus.REPLACED_BY_BUS))
        assertEquals(TrainTypeGroup.REPLACEMENT_BUS, WidgetTrainTypes.groupOf(ServiceKind.UNKNOWN, null, "BUS 12", null))
        assertEquals(TrainTypeGroup.ITALO, WidgetTrainTypes.groupOf(ServiceKind.HIGH_SPEED, "Italo", null, RunStatus.ON_TIME))
        assertEquals(TrainTypeGroup.INTERNATIONAL, WidgetTrainTypes.groupOf(ServiceKind.REGIONAL, "sbb", "RE 80", null))
        assertEquals(TrainTypeGroup.HIGH_SPEED, WidgetTrainTypes.groupOf(ServiceKind.HIGH_SPEED, "trenitalia", null, null))
        assertEquals(TrainTypeGroup.INTERCITY_NIGHT, WidgetTrainTypes.groupOf(ServiceKind.NIGHT, "trenitalia", null, null))
        assertEquals(TrainTypeGroup.REGIONAL_SUBURBAN, WidgetTrainTypes.groupOf(ServiceKind.UNKNOWN, null, "S 10", null))
        assertEquals(TrainTypeGroup.HIGH_SPEED, WidgetTrainTypes.groupOf(ServiceKind.UNKNOWN, null, "FR", null))
        assertEquals(TrainTypeGroup.INTERNATIONAL, WidgetTrainTypes.groupOf(ServiceKind.UNKNOWN, null, "EC 33", null))
        assertEquals(TrainTypeGroup.INTERCITY_NIGHT, WidgetTrainTypes.groupOf(ServiceKind.UNKNOWN, null, "ICN", null))
    }

    @Test
    fun `unknown kinds are never hidden`() {
        val everything = TrainTypeGroup.entries.toSet()

        assertNull(WidgetTrainTypes.groupOf(TrainCategory.OTHER, RailOperator.OTHER))
        assertNull(WidgetTrainTypes.groupOf(TrainCategory.OTHER, RailOperator.TRENITALIA_LONG_DISTANCE))
        assertNull(WidgetTrainTypes.groupOf(ServiceKind.UNKNOWN, "trenitalia", null, null))
        assertNull(WidgetTrainTypes.groupOf(ServiceKind.TOURIST, null, "XYZ", null))
        assertFalse(WidgetTrainTypes.isHidden(null, everything))
        assertTrue(WidgetTrainTypes.isHidden(TrainTypeGroup.ITALO, setOf(TrainTypeGroup.ITALO)))
        assertFalse(WidgetTrainTypes.isHidden(TrainTypeGroup.ITALO, setOf(TrainTypeGroup.HIGH_SPEED)))
    }

    @Test
    fun `the station widget hides excluded departures but keeps unknown ones`() {
        val italo = StationWidgetDeparture(1_000_000L, null, "9921", "italo", "Napoli", null, null, TrainTypeGroup.ITALO)
        val unknown = StationWidgetDeparture(1_100_000L, null, "123", null, "Latina", null, null, null)
        val regional = StationWidgetDeparture(1_200_000L, null, "456", "trenitalia", "Frosinone", null, null, TrainTypeGroup.REGIONAL_SUBURBAN)

        val shown = StationWidgetPresenter.upcoming(listOf(italo, unknown, regional), it.binario.app.data.Freshness.FRESH, 0L, excluded = setOf(TrainTypeGroup.ITALO, TrainTypeGroup.REGIONAL_SUBURBAN))

        assertEquals(listOf("123"), shown.map { it.number })
    }
}

class RouteWidgetPresenterTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val day = LocalDate.of(2026, 11, 2)
    private fun millis(hour: Int, minute: Int, date: LocalDate = day) = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    private fun row(number: String, departure: Long?, arrival: Long? = null, status: ServiceStatus = ServiceStatus.ON_TIME, group: TrainTypeGroup? = TrainTypeGroup.REGIONAL_SUBURBAN) = WidgetService(
        number = number,
        operator = RailOperator.TRENITALIA_REGIONALE,
        category = TrainCategory.REGIONALE,
        destination = "Roma Termini",
        expectedDeparture = "08:00",
        status = status,
        delayMinutes = 0,
        platform = "3",
        operationalStatusVerified = true,
        departureEpochMillis = departure,
        arrivalEpochMillis = arrival,
        arrivalLabel = "09:10",
        typeGroup = group,
        serviceDate = day
    )

    @Test
    fun `a departed train leaves the list unless it is shown until arrival`() {
        val departed = row("2153", millis(8, 0), millis(9, 10))
        val now = millis(8, 30)

        assertTrue(RouteWidgetPresenter.visible(listOf(departed), now, RouteWidgetSettings()).isEmpty())
        val untilArrival = RouteWidgetPresenter.visible(listOf(departed), now, RouteWidgetSettings(untilArrival = true))
        assertEquals(listOf(RouteWidgetRow(departed, inTransit = true)), untilArrival)
        assertTrue(RouteWidgetPresenter.visible(listOf(departed), millis(9, 10), RouteWidgetSettings(untilArrival = true)).isEmpty())
    }

    @Test
    fun `the departure minute stays listed and is not yet in transit`() {
        val leaving = row("2153", millis(8, 0), millis(9, 10))

        assertEquals(listOf(RouteWidgetRow(leaving, false)), RouteWidgetPresenter.visible(listOf(leaving), millis(8, 0) + 30_000L, RouteWidgetSettings(untilArrival = true)))
    }

    @Test
    fun `cancelled trains and trains without an arrival are never in transit`() {
        val cancelled = row("1", millis(8, 0), millis(9, 0), ServiceStatus.CANCELLED)
        val noArrival = row("2", millis(8, 0), null)

        assertTrue(RouteWidgetPresenter.visible(listOf(cancelled, noArrival), millis(8, 20), RouteWidgetSettings(untilArrival = true)).isEmpty())
    }

    @Test
    fun `time ranges cut the list at three or six hours and the whole day keeps everything`() {
        val rows = listOf(row("a", millis(9, 0)), row("b", millis(11, 30)), row("c", millis(13, 0)), row("d", millis(20, 0)))
        val now = millis(8, 30)

        assertEquals(listOf("a", "b"), RouteWidgetPresenter.visible(rows, now, RouteWidgetSettings(timeRange = RouteTimeRange.NEXT_3H)).map { it.service.number })
        assertEquals(listOf("a", "b", "c"), RouteWidgetPresenter.visible(rows, now, RouteWidgetSettings(timeRange = RouteTimeRange.NEXT_6H)).map { it.service.number })
        assertEquals(4, RouteWidgetPresenter.visible(rows, now, RouteWidgetSettings(timeRange = RouteTimeRange.WHOLE_DAY)).size)
        assertEquals(4, RouteWidgetPresenter.visible(rows, now, RouteWidgetSettings(timeRange = RouteTimeRange.NEXT)).size)
    }

    @Test
    fun `excluded types disappear while unknown types and unknown times stay`() {
        val italo = row("9921", millis(9, 0), group = TrainTypeGroup.ITALO)
        val unknownType = row("777", millis(9, 10), group = null)
        val unknownTime = row("888", null)

        val shown = RouteWidgetPresenter.visible(
            listOf(italo, unknownType, unknownTime),
            millis(8, 0),
            RouteWidgetSettings(excludedTypes = TrainTypeGroup.entries.toSet() - TrainTypeGroup.REGIONAL_SUBURBAN)
        )

        assertEquals(listOf("777", "888"), shown.map { it.service.number })
    }

    @Test
    fun `clock times resolve around the reference, across midnight`() {
        assertEquals(millis(7, 40), WidgetClock.resolve("07:40", millis(8, 0)))
        assertEquals(millis(0, 15, day.plusDays(1)), WidgetClock.resolve("00:15", millis(23, 30)))
        assertEquals(millis(23, 50, day.minusDays(1)), WidgetClock.resolve("23:50", millis(0, 10)))
        assertNull(WidgetClock.resolve("—", millis(8, 0)))
        assertEquals(millis(1, 30, day.plusDays(1)), WidgetClock.onDate("01:30", day, millis(22, 0)))
        assertEquals(millis(7, 58), WidgetClock.onDate("07:58", day, millis(8, 0)))
    }

    @Test
    fun `the mapper stores expected departure and expected arrival at the destination`() {
        val service = TrainService(
            id = "2153", number = "2153", operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.REGIONALE,
            origin = "Roma Termini", destination = "Orte", scheduledDeparture = "23:50", expectedDeparture = "00:05",
            platform = "4", delayMinutes = 15, status = ServiceStatus.DELAYED,
            stops = listOf(
                TrainStop("Roma Termini", "23:50", "00:05", "4", 15, false),
                TrainStop("Orte", "00:40", "00:55", null, 15, false)
            ),
            scheduledDepartureDateTime = LocalDateTime.of(day, LocalTime.of(23, 50)),
            routeChange = OperationalRouteChange(RouteChangeKind.REPLACEMENT_SERVICE, "Bus tra Roma e Orte")
        )

        val mapped = WidgetServiceMapper.fromService(service, millis(23, 40))

        assertEquals("23:50", mapped.scheduledDeparture)
        assertEquals(millis(0, 5, day.plusDays(1)), mapped.departureEpochMillis)
        assertEquals(millis(0, 55, day.plusDays(1)), mapped.arrivalEpochMillis)
        assertEquals("00:55", mapped.arrivalLabel)
        assertEquals(TrainTypeGroup.REPLACEMENT_BUS, mapped.typeGroup)
        assertEquals(day, mapped.serviceDate)
    }

    @Test
    fun `scheduled only rows use timetable times`() {
        val service = TrainService(
            id = "x", number = "4120", operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.REGIONALE,
            origin = "Roma", destination = "Latina", scheduledDeparture = "10:20", expectedDeparture = "10:20",
            platform = null, delayMinutes = 0, status = ServiceStatus.ON_TIME,
            stops = listOf(TrainStop("Roma", "10:20", "10:20", null, 0, false), TrainStop("Latina", "11:05", "11:20", null, 0, false)),
            operationalStatusVerified = false
        )

        val mapped = WidgetServiceMapper.fromService(service, millis(10, 0))

        assertEquals(millis(10, 20), mapped.departureEpochMillis)
        assertEquals(millis(11, 5), mapped.arrivalEpochMillis)
    }

    @Test
    fun `paging fills the range from the last departure and stops at its limits`() {
        val now = ZonedDateTime.of(day, LocalTime.of(8, 0), zone)

        assertNull(RouteWidgetPaging.nextQuery(RouteTimeRange.NEXT, now, millis(9, 0), 0))
        val next = RouteWidgetPaging.nextQuery(RouteTimeRange.NEXT_3H, now, millis(9, 0), 0)!!
        assertEquals(day, next.serviceDate)
        assertEquals(LocalTime.of(9, 1), next.departureTime)
        assertNull("the range is already covered", RouteWidgetPaging.nextQuery(RouteTimeRange.NEXT_3H, now, millis(11, 0), 0))
        assertNull("one extra page for three hours", RouteWidgetPaging.nextQuery(RouteTimeRange.NEXT_3H, now, millis(9, 0), 1))
        assertEquals(LocalTime.of(15, 1), RouteWidgetPaging.nextQuery(RouteTimeRange.WHOLE_DAY, now, millis(15, 0), 2)!!.departureTime)
        assertNull(RouteWidgetPaging.nextQuery(RouteTimeRange.WHOLE_DAY, now, millis(15, 0), 3))
        assertNull("never into tomorrow", RouteWidgetPaging.nextQuery(RouteTimeRange.WHOLE_DAY, now, millis(0, 10, day.plusDays(1)), 0))
        assertNull(RouteWidgetPaging.nextQuery(RouteTimeRange.WHOLE_DAY, now, null, 0))
    }

    @Test
    fun `the whole day cache keeps earlier rows of the day and replaces refreshed runs`() {
        val morning = row("2101", millis(6, 0)).copy(scheduledDeparture = "06:00")
        val stale = row("2153", millis(8, 0)).copy(scheduledDeparture = "08:00", delayMinutes = 0)
        val yesterday = row("2199", millis(9, 0, day.minusDays(1))).copy(scheduledDeparture = "09:00", serviceDate = day.minusDays(1))
        val fresh = row("2153", millis(8, 10)).copy(scheduledDeparture = "08:00", delayMinutes = 10)
        val later = row("2177", millis(10, 0)).copy(scheduledDeparture = "10:00")

        val merged = RouteWidgetDayCache.merge(listOf(morning, stale, yesterday), listOf(fresh, later), day)

        assertEquals(listOf("2101", "2153", "2177"), merged.map { it.number })
        assertEquals(10, merged[1].delayMinutes)
    }
}
