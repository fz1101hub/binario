package it.binario.app

import it.binario.app.data.RailOperator
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import java.time.LocalDate
import java.time.LocalDateTime
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctualityHistoryTest {
    private val today = LocalDate.of(2026, 10, 25)

    @Test
    fun `only operational data gives an observation, with the delay of the last verified stop`() {
        val live = service(verified = true, stops = listOf(stop("Roma Termini", 2, true), stop("Latina", 9, true), stop("Napoli Centrale", 0, false)))

        assertEquals(
            PunctualityObservation(today, "2345", "Trenitalia", 9, false, listOf(StopDelay("Roma Termini", 2), StopDelay("Latina", 9))),
            PunctualityHistory.observationOf(live, today)
        )
        assertNull(PunctualityHistory.observationOf(live.copy(operationalStatusVerified = false), today))
        assertNull(PunctualityHistory.observationOf(live.copy(scheduledDepartureDateTime = today.plusDays(1).atTime(8, 0)), today))
        assertNull(PunctualityHistory.observationOf(live.copy(number = "  "), today))
    }

    @Test
    fun `a cancelled or early train is recorded without a negative delay and numbers are normalized`() {
        val cancelled = service(verified = true, status = ServiceStatus.CANCELLED, number = "reg  2345", delay = -3, stops = emptyList())

        assertEquals(PunctualityObservation(today, "REG 2345", "Trenitalia", 0, true), PunctualityHistory.observationOf(cancelled, today))
    }

    @Test
    fun `one observation per train and day, the latest replacing the earlier one`() {
        val morning = observation("2345", today, 4)
        val evening = observation("2345", today, 12)
        val yesterday = observation("2345", today.minusDays(1), 0)

        val history = PunctualityHistory.record(PunctualityHistory.record(listOf(yesterday), listOf(morning), today), listOf(evening), today)

        assertEquals(listOf(evening, yesterday), history)
    }

    @Test
    fun `history keeps a year and drops future service days`() {
        val kept = observation("1", today.minusDays(364), 1)
        val expired = observation("1", today.minusDays(365), 1)
        val future = observation("1", today.plusDays(1), 1)

        assertEquals(listOf(kept), PunctualityHistory.bound(listOf(expired, kept, future), today))
    }

    @Test
    fun `history keeps a year per train and fifteen hundred observations in total`() {
        val oneTrain = (0 until 400).map { observation("7", today.minusDays(it.toLong()), it % 60) }
        val bounded = PunctualityHistory.bound(oneTrain, today)
        assertEquals(PunctualityHistory.MAX_PER_TRAIN, bounded.size)
        assertEquals(today.minusDays(364), bounded.last().serviceDate)

        val manyTrains = (0 until 30).flatMap { train -> (0 until 60).map { day -> observation("T$train", today.minusDays(day.toLong()), 0) } }
        val total = PunctualityHistory.bound(manyTrains, today)
        assertEquals(PunctualityHistory.MAX_TOTAL, total.size)
        assertTrue(total.all { !it.serviceDate.isBefore(today.minusDays(50)) })
    }

    @Test
    fun `per-stop delays are kept only for the newest observations of each train`() {
        val stops = listOf(StopDelay("Roma Termini", 0), StopDelay("Latina", 4))
        val detailed = (0 until 40).map { observation("7", today.minusDays(it.toLong()), 4).copy(stopDelays = stops) }

        val bounded = PunctualityHistory.bound(detailed, today)

        assertEquals(PunctualityHistory.DETAILED_PER_TRAIN, bounded.count { it.stopDelays.isNotEmpty() })
        assertTrue(bounded.take(PunctualityHistory.DETAILED_PER_TRAIN).all { it.stopDelays == stops })
        assertEquals(40, bounded.size)
    }

    @Test
    fun `statistics give the share over fifteen minutes, the distribution and the stop where the delay grows`() {
        fun run(day: Long, vararg delays: Pair<String, Int>) = observation("2345", today.minusDays(day), delays.last().second)
            .copy(stopDelays = delays.map { StopDelay(it.first, it.second) })
        val history = listOf(
            run(0, "Roma Termini" to 0, "Latina" to 6, "Napoli Centrale" to 7),
            run(1, "Roma Termini" to 1, "Latina" to 9, "Napoli Centrale" to 9),
            run(2, "Roma Termini" to 0, "Latina" to 20, "Napoli Centrale" to 31),
            observation("2345", today.minusDays(3), 3),
            observation("2345", today.minusDays(4), 0, cancelled = true)
        )

        val stats = PunctualityHistory.stats(history, "2345")!!

        assertEquals(5, stats.count)
        assertEquals(20, stats.onTimePercent)
        assertEquals(20, stats.latePercent)
        assertEquals(13, stats.averageDelayMinutes)
        assertEquals(
            mapOf(PunctualityBucket.ON_TIME to 1, PunctualityBucket.UP_TO_15 to 2, PunctualityBucket.UP_TO_30 to 0, PunctualityBucket.OVER_30 to 1, PunctualityBucket.CANCELLED to 1),
            stats.distribution
        )
        // Latina gains 6, 8 and 20 minutes (average 11), Napoli 1, 0 and 11 (average 4).
        assertEquals(PunctualityWorstStop("Latina", 11, 3), stats.worstStop)
        assertEquals((0L..4L).map { today.minusDays(it) }, stats.history.map { it.serviceDate })
        assertEquals(PunctualityBucket.UP_TO_30, PunctualityHistory.bucketOf(observation("1", today, 16)))
    }

    @Test
    fun `no worst stop without two detailed observations or a growing delay`() {
        val one = listOf(observation("1", today, 5).copy(stopDelays = listOf(StopDelay("A", 0), StopDelay("B", 5))))
        val flat = (0L..2L).map { observation("1", today.minusDays(it), 0).copy(stopDelays = listOf(StopDelay("A", 3), StopDelay("B", 3))) }

        assertNull(PunctualityHistory.worstStop(one))
        assertNull(PunctualityHistory.worstStop(flat))
    }

    @Test
    fun `the store keeps what the background recorder wrote over an older in-memory history`() {
        val recorded = listOf(observation("2345", today, 12).copy(stopDelays = listOf(StopDelay("Roma Termini", 0), StopDelay("Napoli Centrale", 12))))
        val stale = listOf(observation("2345", today, 3))

        val merged = PunctualityHistory.bound(stale.asReversed() + recorded.asReversed(), today)

        assertEquals(recorded, merged)
    }

    @Test
    fun `statistics need two observations and count cancellations as not on time`() {
        val history = listOf(
            observation("2345", today, 0),
            observation("2345", today.minusDays(1), 5),
            observation("2345", today.minusDays(2), 6),
            observation("2345", today.minusDays(3), 13),
            observation("2345", today.minusDays(4), 0, cancelled = true),
            observation("2345", today.minusDays(5), 2),
            observation("9999", today, 40)
        )

        val stats = PunctualityHistory.stats(history, " 2345 ")!!

        assertEquals(6, stats.count)
        assertEquals(50, stats.onTimePercent)
        assertEquals(5, stats.averageDelayMinutes)
        assertEquals(1, stats.cancelledCount)
        assertEquals((0L..4L).map { today.minusDays(it) }, stats.recent.map { it.serviceDate })
        assertNull(PunctualityHistory.stats(history, "9999"))
    }

    @Test
    fun `a history of cancellations has no average delay`() {
        val history = listOf(observation("1", today, 0, cancelled = true), observation("1", today.minusDays(1), 0, cancelled = true))

        val stats = PunctualityHistory.stats(history, "1")!!

        assertNull(stats.averageDelayMinutes)
        assertEquals(0, stats.onTimePercent)
    }

    @Test
    fun `json round trip skips damaged entries and clamps values`() {
        val history = listOf(
            observation("2345", today, 7).copy(stopDelays = listOf(StopDelay("Roma Termini", 1), StopDelay("Latina", 7))),
            observation("REG 12", today.minusDays(3), 0, cancelled = true)
        )
        assertEquals(history, PunctualityHistory.fromJson(PunctualityHistory.toJson(history)))
        val withBrokenStops = JSONArray("""[{"date":"2026-10-20","train":"5","delayMinutes":3,"stops":[["A",1],"x",[""],["B",-4]]}]""")
        assertEquals(listOf(StopDelay("A", 1), StopDelay("B", 0)), PunctualityHistory.fromJson(withBrokenStops).single().stopDelays)

        val raw = JSONArray(
            """[{"date":"2026-10-20","train":"1","operator":"Italo","delayMinutes":99999,"cancelled":false},
               {"date":"not a date","train":"2","delayMinutes":1},{"date":"2026-10-20","train":"","delayMinutes":1},
               {"date":"2026-10-20","train":"3"},"text",{"date":"2026-10-20","train":"4","delayMinutes":-8}]"""
        )
        val decoded = PunctualityHistory.fromJson(raw)
        assertEquals(listOf("1", "4"), decoded.map { it.trainNumber })
        assertEquals(listOf(24 * 60, 0), decoded.map { it.delayMinutes })
        assertEquals(emptyList<PunctualityObservation>(), PunctualityHistory.decode("{broken"))
        assertEquals(emptyList<PunctualityObservation>(), PunctualityHistory.decode(null))
    }

    @Test
    fun `deleting one train keeps the others`() {
        val history = listOf(observation("1", today, 0), observation("2", today, 3))

        assertEquals(listOf(observation("2", today, 3)), PunctualityHistory.withoutTrain(history, "1"))
    }

    @Test
    fun `the local backup carries the history and an older file leaves it alone`() {
        val roma = Station("roma", "Roma Termini", "Roma")
        val napoli = Station("napoli", "Napoli Centrale", "Napoli")
        val history = listOf(observation("2345", today, 7))
        val backup = LocalDataBackup(listOf(SavedRoute("route", "Casa", roma, napoli)), emptySet(), emptyList(), emptyList(), emptyList(), MobilityPreferences(), punctualityHistory = history)

        val restored = (LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(backup), listOf(roma, napoli)) as LocalBackupResult.Success).backup
        val legacy = (LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(backup.copy(punctualityHistory = null)), listOf(roma, napoli)) as LocalBackupResult.Success).backup

        assertEquals(history, restored.punctualityHistory)
        assertNull(legacy.punctualityHistory)
    }

    private fun observation(number: String, date: LocalDate, delay: Int, cancelled: Boolean = false) =
        PunctualityObservation(date, number, "Trenitalia", delay, cancelled)

    private fun stop(station: String, delay: Int, verified: Boolean) =
        TrainStop(station, "08:00", "08:00", null, delay, completed = false, operationalStatusVerified = verified)

    private fun service(
        verified: Boolean,
        stops: List<TrainStop>,
        status: ServiceStatus = ServiceStatus.DELAYED,
        number: String = "2345",
        delay: Int = 2
    ) = TrainService(
        id = "svc", number = number, operator = RailOperator.TRENITALIA_REGIONALE, trainCategory = TrainCategory.REGIONALE,
        origin = "Roma Termini", destination = "Napoli Centrale", scheduledDeparture = "08:00", expectedDeparture = "08:02",
        platform = null, delayMinutes = delay, status = status, stops = stops, operatorDisplayName = "Trenitalia",
        operationalStatusVerified = verified, scheduledDepartureDateTime = LocalDateTime.of(today, java.time.LocalTime.of(8, 0))
    )
}
