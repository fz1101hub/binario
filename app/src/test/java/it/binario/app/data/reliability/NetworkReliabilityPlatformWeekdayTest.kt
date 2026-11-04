package it.binario.app.data.reliability

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.reliability.ReliabilityFixtures.document
import it.binario.app.data.reliability.ReliabilityFixtures.parse
import it.binario.app.data.reliability.ReliabilityFixtures.stop
import it.binario.app.data.reliability.ReliabilityFixtures.stop7
import it.binario.app.data.reliability.ReliabilityFixtures.stop9
import it.binario.app.data.reliability.ReliabilityFixtures.train
import it.binario.app.data.reliability.ReliabilityFixtures.weekdays
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extension 2 of the statistics file: the usual platform (stop element 7) and the weekday counts (element 8). */
class NetworkReliabilityPlatformWeekdayTest {
    private val roma = Station("curated:S08409", "Roma Termini", "Roma", railwayCode = "S08409")
    private val milano = Station("curated:S01700", "Milano Centrale", "Milano", railwayCode = "S01700")
    private val torino = Station("curated:S00219", "Torino Porta Nuova", "Torino", railwayCode = "S00219")
    private val catalogue = listOf(roma, milano, torino)

    /** At Torino 20 of 30 within 5 minutes (67%); Friday 1 of 5 (20%), every other day within 15 points. */
    private val torinoWeekdays = weekdays(
        Triple(5, 4, 5), Triple(4, 3, 4), Triple(4, 3, 4), Triple(4, 3, 4), Triple(5, 1, 4), Triple(4, 3, 4), Triple(4, 3, 4)
    )

    private val frecciarossa = train(
        "9558", "S08409", "FR", 31, 1,
        stop9("S08409", 30, 0.8, listOf(20, 24, 27, 29, 30, 30, 30, 30), platform = "[\"12\",24,30]", weekdays = "null"),
        stop9("S01700", 30, 4.6, listOf(9, 14, 22, 26, 28, 29, 30, 30), platform = "[\"3 Ovest\",4,5]", weekdays = "null"),
        stop9("S00219", 30, 6.2, listOf(8, 12, 20, 25, 27, 28, 29, 30), platform = "[\"I\",10,20]", weekdays = torinoWeekdays)
    )

    private fun stats(vararg trains: String) = requireNotNull(parse(document(*trains)))

    @Test fun `a nine-element stop gives its usual platform and weekday counts, older stops none`() {
        val mixed = train(
            "2041", "S01700", "REG", 30, 0,
            stop("S01700", 30, 0.4, listOf(27, 29, 30, 30, 30, 30, 30, 30)),
            stop7("S01820", 30, 1.0, listOf(25, 28, 30, 30, 30, 30, 30, 30), maxDelay = 12, scheduledMinute = 500),
            stop9("S00219", 30, 3.0, listOf(15, 19, 24, 27, 29, 30, 30, 30), platform = "[\" 7 \",20,28]", weekdays = torinoWeekdays)
        )
        val stats = stats(frecciarossa, mixed)
        val fr = stats.trainsNumbered("9558").single()
        assertEquals(UsualPlatform("12", 24, 30), fr.platform(0))
        assertEquals(UsualPlatform("3 Ovest", 4, 5), fr.platform(1))
        assertNull(fr.weekdays(0))
        val friday = fr.weekdays(2)!!.single { it.weekday == DayOfWeek.FRIDAY }
        assertEquals(WeekdayDelays(DayOfWeek.FRIDAY, 5, 1, 4), friday)
        assertEquals(30, fr.weekdays(2)!!.sumOf { it.samples })
        // The other fields of the stop are those of the 7-element form.
        assertEquals(30, fr.maxDelayMinutes(2))
        assertEquals(480, fr.scheduledMinuteOfDay(2))
        val regional = stats.trainsNumbered("2041").single()
        assertNull(regional.platform(0))
        assertNull(regional.platform(1))
        assertNull(regional.weekdays(1))
        assertEquals(12, regional.maxDelayMinutes(1))
        // Names are trimmed.
        assertEquals(UsualPlatform("7", 20, 28), regional.platform(2))
        assertEquals(7, regional.weekdays(2)!!.size)
    }

    @Test fun `null or malformed platform and weekdays are left out, never the stop`() {
        fun parsed(platform: String, weekdays: String): NetworkReliabilityTrain =
            stats(train("9558", "S08409", "FR", 31, 1, stop9("S00219", 30, 6.2, listOf(8, 12, 20, 25, 27, 28, 29, 30), platform, weekdays)))
                .trainsNumbered("9558").single()
        val nulls = parsed("null", "null")
        assertEquals(1, nulls.stopCount)
        assertEquals(30, nulls.samples(0))
        assertNull(nulls.platform(0))
        assertNull(nulls.weekdays(0))
        for (platform in listOf("\"12\"", "[\"Binario dodici\",8,10]", "[\"12\",11,10]", "[\"12\",0,10]", "[\"\",8,10]", "[null,8,10]", "[\"12\",8]", "{}")) {
            val stopKept = parsed(platform, torinoWeekdays)
            assertNull(platform, stopKept.platform(0))
            assertEquals(platform, 30, stopKept.samples(0))
            assertNotNull(platform, stopKept.weekdays(0))
        }
        val six = weekdays(Triple(5, 4, 5), Triple(5, 4, 5), Triple(5, 4, 5), Triple(5, 4, 5), Triple(5, 4, 5), Triple(5, 4, 5))
        val ahead = torinoWeekdays.replaceFirst("[5,4,5]", "[5,5,4]")
        val tooMany = torinoWeekdays.replaceFirst("[5,4,5]", "[31,4,5]")
        val pair = torinoWeekdays.replaceFirst("[5,4,5]", "[5,4]")
        for (days in listOf(six, ahead, tooMany, pair, "[1,2,3]", "7", "$torinoWeekdays".dropLast(1) + ",[0,0,0]]")) {
            val stopKept = parsed("[\"12\",24,30]", days)
            assertNull(days, stopKept.weekdays(0))
            assertEquals(days, UsualPlatform("12", 24, 30), stopKept.platform(0))
        }
        // A weekday without samples is [0, 0, 0]; trailing elements of a later pipeline are ignored.
        val quiet = parsed("[\"12\",24,30,\"extra\"]", torinoWeekdays.replaceFirst("[4,3,4]", "[0,0,0]").replaceFirst("[5,4,5]", "[9,8,9]"))
        assertEquals(0, quiet.weekdays(0)!![1].samples)
        assertEquals(UsualPlatform("12", 24, 30), quiet.platform(0))
    }

    @Test fun `the usual platform needs five days and sixty percent, and a known platform always wins`() {
        val fr = stats(frecciarossa).trainsNumbered("9558").single()
        assertEquals(UsualPlatform("12", 24, 30), NetworkReliability.usualPlatform(fr, 0))
        assertNull("4 days", NetworkReliability.usualPlatform(fr, 1))
        assertNull("50%", NetworkReliability.usualPlatform(fr, 2))
        assertNull(NetworkReliability.usualPlatform(fr, 3))
        val edge = stats(train("1", "S08409", "REG", 30, 0, stop9("S08409", 30, 0.8, List(8) { 30 }, platform = "[\"4\",6,10]", weekdays = "null")))
            .trainsNumbered("1").single()
        assertEquals(UsualPlatform("4", 6, 10), NetworkReliability.usualPlatform(edge, 0))
        val usual = UsualPlatform("12", 24, 30)
        assertEquals(usual, NetworkReliability.usualPlatformShown(usual, null, "", null))
        assertNull(NetworkReliability.usualPlatformShown(usual, "13", null))
        assertNull(NetworkReliability.usualPlatformShown(usual, null, "12"))
        assertNull(NetworkReliability.usualPlatformShown(null, null))
    }

    @Test fun `a weekday is remarked on with four samples and fifteen points from the stop's share`() {
        val fr = stats(frecciarossa).trainsNumbered("9558").single()
        val arrival = NetworkReliability.at(fr, 2)!!
        assertEquals(20, arrival.onTime)
        val remark = NetworkReliability.weekdayRemark(fr, 2, arrival)!!
        assertEquals(DayOfWeek.FRIDAY, remark.weekday)
        assertEquals(1, remark.onTime)
        assertEquals(5, remark.samples)
        assertFalse(remark.better)
        // The day of the run is preferred only when it qualifies itself.
        assertEquals(DayOfWeek.FRIDAY, NetworkReliability.weekdayRemark(fr, 2, arrival, preferred = DayOfWeek.MONDAY)?.weekday)
        assertNull(NetworkReliability.weekdayRemark(fr, 0, NetworkReliability.at(fr, 0)!!))
        // Within 15 points (Monday 80% against 67%) nothing; 3 samples are too few even at 0%.
        val days = listOf(
            WeekdayDelays(DayOfWeek.MONDAY, 5, 4, 5), WeekdayDelays(DayOfWeek.TUESDAY, 3, 0, 0), WeekdayDelays(DayOfWeek.WEDNESDAY, 4, 4, 4),
            WeekdayDelays(DayOfWeek.THURSDAY, 4, 0, 4)
        )
        val remarks = NetworkReliability.weekdayRemarks(days, limitMinutes = 5, overallShare = 2.0 / 3)
        assertEquals(listOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), remarks.map { it.weekday })
        assertEquals(listOf(true, false), remarks.map { it.better })
        // A regional train is measured at 15 minutes: Thursday is then on time every day, like Monday and Wednesday.
        val regional = NetworkReliability.weekdayRemarks(days, limitMinutes = 15, overallShare = 0.8)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), regional.map { it.weekday })
        assertTrue(regional.all { it.better })
        // Exactly 15 points is enough.
        assertEquals(1, NetworkReliability.weekdayRemarks(listOf(WeekdayDelays(DayOfWeek.SUNDAY, 4, 2, 2)), 5, 0.65).size)
        assertTrue(NetworkReliability.weekdayRemarks(listOf(WeekdayDelays(DayOfWeek.SUNDAY, 4, 2, 2)), 5, 0.64).isEmpty())
    }

    @Test fun `the route aggregates the weekdays of its trains and remarks on the day searched`() {
        val route = NetworkReliabilityAggregates.route(stats(frecciarossa), "S08409", "S00219")!!
        assertEquals(7, route.weekdays.size)
        assertEquals(WeekdayPunctuality(DayOfWeek.FRIDAY, 5, 1), route.weekdays[4])
        assertEquals(DayOfWeek.FRIDAY, route.weekdayRemark(DayOfWeek.FRIDAY)?.weekday)
        assertNull(route.weekdayRemark(DayOfWeek.MONDAY))
        // Without weekday counts there is nothing to remark on.
        val older = NetworkReliabilityAggregates.route(stats(ReliabilityFixtures.FR_9558), "S08409", "S00219")!!
        assertTrue(older.weekdays.isEmpty())
        assertNull(older.weekdayRemark(DayOfWeek.FRIDAY))
    }

    @Test fun `the train detail has the usual platform where it is boarded and the weekday remark of its arrival`() {
        val stats = stats(frecciarossa)
        val stations = NetworkReliabilityStations(catalogue)
        val lookup = { name: String -> catalogue.firstOrNull { it.name == name } }
        val day = LocalDate.of(2026, 11, 6) // a Friday
        val service = TrainService(
            "train:9558", "9558", RailOperator.TRENITALIA_AV, TrainCategory.FRECCE, roma.name, torino.name, "08:00", "08:00", null, 0,
            ServiceStatus.ON_TIME,
            listOf(TrainStop(roma.name, "08:00", "08:00", null, 0, false), TrainStop(milano.name, "11:00", "11:00", null, 0, false), TrainStop(torino.name, "12:00", "12:00", null, 0, false)),
            operationalStatusVerified = false, scheduledDepartureDateTime = day.atTime(LocalTime.of(8, 0))
        )
        val detail = TrainNetworkStatistics.of(stats, service, lookup, stations)!!
        assertEquals(UsualPlatform("12", 24, 30), detail.usualPlatform)
        assertEquals(DayOfWeek.FRIDAY, detail.card?.weekdayRemark?.weekday)
        assertEquals(LocalDate.of(2026, 11, 9), detail.windowEnd)
        // Boarded at Milano: 4 days on its platform are not enough.
        val fromMilano = service.copy(origin = milano.name, stops = service.stops.drop(1))
        assertNull(TrainNetworkStatistics.of(stats, fromMilano, lookup, stations)?.usualPlatform)
        // A coach has no statistics at all.
        assertNull(TrainNetworkStatistics.of(stats, service.copy(scheduledBus = true), lookup, stations))
    }
}
