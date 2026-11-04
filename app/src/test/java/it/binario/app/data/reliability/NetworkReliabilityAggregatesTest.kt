package it.binario.app.data.reliability

import it.binario.app.data.reliability.ReliabilityFixtures.document
import it.binario.app.data.reliability.ReliabilityFixtures.parse
import it.binario.app.data.reliability.ReliabilityFixtures.stop
import it.binario.app.data.reliability.ReliabilityFixtures.stop7
import it.binario.app.data.reliability.ReliabilityFixtures.train
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Delay distribution, route and hour bands, station arrivals: the aggregates of the network statistics. */
class NetworkReliabilityAggregatesTest {
    private val milano = "S01700"
    private val torino = "S00219"
    private val roma = "S08409"

    @Test fun `stops of five and of seven elements both parse, the new fields optional`() {
        val stats = requireNotNull(
            parse(
                document(
                    train(
                        "9600", roma, "FR", 30, 0,
                        stop7(roma, 30, 0.5, listOf(25, 28, 30, 30, 30, 30, 30, 30), maxDelay = 4, scheduledMinute = 420),
                        stop7(milano, 30, 4.0, listOf(10, 15, 20, 25, 28, 29, 30, 30), maxDelay = 47, scheduledMinute = null),
                        // Reached after midnight of the service day: 1450 is 00:10 the next day.
                        stop7(torino, 30, 6.0, listOf(8, 12, 20, 25, 27, 28, 29, 30), maxDelay = 63, scheduledMinute = 1450)
                    ),
                    train(
                        "2041", milano, "REG", 30, 0,
                        stop(milano, 30, 0.4, listOf(27, 29, 30, 30, 30, 30, 30, 30)),
                        stop(torino, 30, 3.0, listOf(15, 19, 24, 27, 29, 30, 30, 30))
                    )
                )
            )
        )
        val frecciarossa = stats.trainsNumbered("9600").single()
        assertEquals(listOf(4, 47, 63), (0 until 3).map { frecciarossa.maxDelayMinutes(it) })
        assertEquals(listOf(420, null, 1450), (0 until 3).map { frecciarossa.scheduledMinuteOfDay(it) })
        assertEquals(20, frecciarossa.cumulative(1, 2))
        val regional = stats.trainsNumbered("2041").single()
        assertNull(regional.maxDelayMinutes(1))
        assertNull(regional.scheduledMinuteOfDay(1))
        assertEquals(29, regional.cumulative(1, 4))
    }

    @Test fun `an implausible new field is left out, the stop and the train stay`() {
        val text = document(
            "{\"n\":\"9600\",\"o\":\"$roma\",\"c\":\"FR\",\"d\":30,\"x\":0,\"s\":[" +
                "[\"$roma\",30,0.5,[25,28,30,30,30,30,30,30],0,-3,5000]," +
                "[\"$milano\",30,4.0,[10,15,20,25,28,29,30,30],0,\"47\",12.5]," +
                "[\"$torino\",30,6.0,[8,12,20,25,27,28,29,30],0,1441,{\"x\":1}]]}"
        )
        val train = requireNotNull(parse(text)).trainsNumbered("9600").single()
        assertEquals(3, train.stopCount)
        assertEquals(listOf<Int?>(null, null, null), (0 until 3).map { train.maxDelayMinutes(it) })
        assertEquals(listOf<Int?>(null, null, null), (0 until 3).map { train.scheduledMinuteOfDay(it) })
    }

    @Test fun `the distribution splits the samples into the five bands of the cumulative counts`() {
        val stats = ReliabilityFixtures.stats(
            train("9600", roma, "FR", 30, 0, stop7(torino, 30, 6.0, listOf(12, 15, 20, 24, 26, 27, 29, 29), maxDelay = 72, scheduledMinute = 600))
        )
        val distribution = requireNotNull(NetworkReliabilityAggregates.distribution(stats.trains.single(), 0))
        // No delay c[0]; 1–5 c[2]−c[0]; 6–15 c[4]−c[2]; 16–30 c[6]−c[4]; over 30 samples−c[6].
        assertEquals(listOf(12, 8, 6, 3, 1), distribution.counts)
        assertEquals(30, distribution.counts.sum())
        // 40, 26.7, 20, 10, 3.3: the point left over goes to the largest remainder, and the shares add up to 100.
        assertEquals(listOf(40, 27, 20, 10, 3), distribution.percentages)
        assertEquals(DelayDistribution(3, 1, 1, 1, 0, 0).percentages, listOf(34, 33, 33, 0, 0))
        assertEquals(listOf(0, 0, 0, 0, 0), DelayDistribution(0, 0, 0, 0, 0, 0).percentages)
    }

    @Test fun `no distribution below five samples or without the 0, 5, 15 and 30 minute buckets`() {
        val few = ReliabilityFixtures.stats(train("9600", roma, "FR", 4, 0, stop(torino, 4, 2.0, listOf(1, 2, 3, 4, 4, 4, 4, 4))))
        assertNull(NetworkReliabilityAggregates.distribution(few.trains.single(), 0))
        assertNull(NetworkReliabilityAggregates.distribution(few.trains.single(), 3))
        val otherBuckets = requireNotNull(
            parse(document(train("9600", roma, "FR", 30, 0, stop(torino, 30, 2.0, listOf(10, 20, 30))), buckets = listOf(0, 10, 60)))
        )
        assertNull(NetworkReliabilityAggregates.distribution(otherBuckets.trains.single(), 0))
    }

    @Test fun `the train detail carries the distribution and the largest delay at the stop measured`() {
        val stats = ReliabilityFixtures.stats(
            train(
                "9600", roma, "FR", 30, 0,
                stop7(roma, 30, 0.5, listOf(25, 28, 30, 30, 30, 30, 30, 30), maxDelay = 4, scheduledMinute = 420),
                stop7(torino, 30, 6.0, listOf(12, 15, 20, 24, 26, 27, 29, 29), maxDelay = 72, scheduledMinute = 600)
            )
        )
        val train = stats.trains.single()
        assertEquals(listOf(12, 8, 6, 3, 1), NetworkReliabilityAggregates.distribution(train, 1)?.counts)
        assertEquals(72, train.maxDelayMinutes(1))
    }

    /** A train from [origin] measured at [destination] with [onTimeAt5]/[onTimeAt15] of [samples] within 5 and 15 minutes. */
    private fun measured(
        number: String,
        category: String,
        origin: String,
        destination: String,
        samples: Int,
        onTimeAt5: Int,
        onTimeAt15: Int,
        mean: Double,
        departureMinute: Int?
    ): String = train(
        number, origin, category, samples, 0,
        stop7(origin, samples, 0.5, List(8) { samples }, maxDelay = 3, scheduledMinute = departureMinute),
        stop7(
            destination, samples, mean, listOf(onTimeAt5 / 2, onTimeAt5, onTimeAt5, onTimeAt15, onTimeAt15, samples, samples, samples),
            maxDelay = 40, scheduledMinute = departureMinute?.plus(60)
        )
    )

    @Test fun `a route counts each train against its own limit and weights the mean delay by the samples`() {
        val stats = ReliabilityFixtures.stats(
            // Frecciarossa: 20 of 30 within 5 minutes (25 within 15, which do not count for it).
            measured("9600", "FR", milano, torino, 30, onTimeAt5 = 20, onTimeAt15 = 25, mean = 6.0, departureMinute = 480),
            // Regional: 29 of 30 within 15 minutes.
            measured("2041", "REG", milano, torino, 30, onTimeAt5 = 24, onTimeAt15 = 29, mean = 3.0, departureMinute = 1110),
            // The other way: Torino before Milano is not this route.
            measured("2042", "REG", torino, milano, 30, onTimeAt5 = 0, onTimeAt15 = 0, mean = 40.0, departureMinute = 600),
            // Through Milano to elsewhere only.
            measured("2043", "REG", milano, roma, 30, onTimeAt5 = 0, onTimeAt15 = 0, mean = 40.0, departureMinute = 600)
        )
        val route = requireNotNull(NetworkReliabilityAggregates.route(stats, milano, torino))
        assertEquals(2, route.trains)
        assertEquals(60, route.samples)
        assertEquals(49, route.onTime)
        assertEquals(82, route.onTimePercent)
        assertEquals(4.5, route.meanDelayMinutes, 1e-9)
        assertEquals(5, route.shownMeanDelayMinutes)
        assertEquals(30, route.days)
        // 6–9: 20 of 30 on time; 18–21: 29 of 30. Both have 20 samples or more and differ by far more than 5 points.
        assertEquals(listOf(HourBand.EARLY_MORNING, HourBand.EVENING), route.bands.map { it.band })
        assertEquals(HourBand.EVENING, route.bestBand)
        assertEquals(HourBand.EARLY_MORNING, route.worstBand)
        assertNull(NetworkReliabilityAggregates.route(stats, milano, milano))
        assertNull(NetworkReliabilityAggregates.route(stats, null, torino))
        assertNull(NetworkReliabilityAggregates.route(stats, milano, "Torino"))
    }

    @Test fun `nothing below twenty samples on the route`() {
        val nineteen = ReliabilityFixtures.stats(measured("2041", "REG", milano, torino, 19, onTimeAt5 = 10, onTimeAt15 = 18, mean = 3.0, departureMinute = 480))
        assertNull(NetworkReliabilityAggregates.route(nineteen, milano, torino))
        val twenty = ReliabilityFixtures.stats(measured("2041", "REG", milano, torino, 20, onTimeAt5 = 10, onTimeAt15 = 18, mean = 3.0, departureMinute = 480))
        val route = requireNotNull(NetworkReliabilityAggregates.route(twenty, milano, torino))
        assertEquals(90, route.onTimePercent)
        // One band only: no best and no worst.
        assertNull(route.bestBand)
        assertNull(route.worstBand)
    }

    @Test fun `hour bands follow the scheduled minute at the origin, wrapping after midnight`() {
        assertEquals(HourBand.NIGHT, HourBand.of(0))
        assertEquals(HourBand.NIGHT, HourBand.of(359))
        assertEquals(HourBand.EARLY_MORNING, HourBand.of(360))
        assertEquals(HourBand.MORNING, HourBand.of(540))
        assertEquals(HourBand.MIDDAY, HourBand.of(720))
        assertEquals(HourBand.AFTERNOON, HourBand.of(1079))
        assertEquals(HourBand.EVENING, HourBand.of(1080))
        assertEquals(HourBand.LATE_EVENING, HourBand.of(1439))
        assertEquals(HourBand.NIGHT, HourBand.of(1450))
        assertEquals(HourBand.EARLY_MORNING, HourBand.of(1440 + 400))
        assertEquals(listOf(0 to 6, 6 to 9, 9 to 12, 12 to 15, 15 to 18, 18 to 21, 21 to 24), HourBand.entries.map { it.startHour to it.endHour })
    }

    @Test fun `the best and the worst band need twenty samples each and five points between them`() {
        fun band(band: HourBand, samples: Int, onTime: Int) = HourBandPunctuality(band, samples, onTime)
        // 80% and 75%: exactly 5 points.
        assertEquals(
            HourBand.MORNING to HourBand.EVENING,
            NetworkReliabilityAggregates.bestAndWorst(listOf(band(HourBand.MORNING, 20, 16), band(HourBand.EVENING, 20, 15)))
        )
        // 80% and 76%: 4 points.
        assertEquals(null to null, NetworkReliabilityAggregates.bestAndWorst(listOf(band(HourBand.MORNING, 20, 16), band(HourBand.EVENING, 25, 19))))
        // 19 samples: the band does not take part, and one band is left.
        assertEquals(null to null, NetworkReliabilityAggregates.bestAndWorst(listOf(band(HourBand.MORNING, 20, 20), band(HourBand.EVENING, 19, 0))))
        // A small band is skipped, the other two still compare.
        assertEquals(
            HourBand.MORNING to HourBand.LATE_EVENING,
            NetworkReliabilityAggregates.bestAndWorst(
                listOf(band(HourBand.NIGHT, 5, 0), band(HourBand.MORNING, 40, 38), band(HourBand.MIDDAY, 30, 27), band(HourBand.LATE_EVENING, 20, 12))
            )
        )
        // Equal shares everywhere: no gap, nothing to name.
        assertEquals(null to null, NetworkReliabilityAggregates.bestAndWorst(listOf(band(HourBand.MORNING, 20, 18), band(HourBand.EVENING, 30, 27))))
        // The route leaves out of the bands a train whose file has no scheduled minute.
        val stats = ReliabilityFixtures.stats(
            measured("2041", "REG", milano, torino, 30, onTimeAt5 = 24, onTimeAt15 = 29, mean = 3.0, departureMinute = 1110),
            measured("2043", "REG", milano, torino, 30, onTimeAt5 = 10, onTimeAt15 = 15, mean = 9.0, departureMinute = null)
        )
        val route = requireNotNull(NetworkReliabilityAggregates.route(stats, milano, torino))
        assertEquals(60, route.samples)
        assertEquals(listOf(HourBand.EVENING to 30), route.bands.map { it.band to it.samples })
        assertNull(route.bestBand)
    }

    @Test fun `a station counts arrivals only and names up to three trains most often late there`() {
        fun arriving(number: String, category: String, samples: Int, onTimeAt5: Int, onTimeAt15: Int, mean: Double, origin: String = roma) = train(
            number, origin, category, samples, 0,
            stop(origin, samples, 0.5, List(8) { samples }),
            stop(milano, samples, mean, listOf(0, onTimeAt5, onTimeAt5, onTimeAt15, onTimeAt15, samples, samples, samples))
        )
        val stats = ReliabilityFixtures.stats(
            arriving("9600", "FR", 30, onTimeAt5 = 20, onTimeAt15 = 28, mean = 5.0),
            arriving("3000", "REG", 10, onTimeAt5 = 4, onTimeAt15 = 6, mean = 12.0),
            arriving("3001", "REG", 4, onTimeAt5 = 0, onTimeAt15 = 0, mean = 30.0),
            arriving("500", "IC", 20, onTimeAt5 = 10, onTimeAt15 = 20, mean = 4.0),
            arriving("3002", "REG", 20, onTimeAt5 = 10, onTimeAt15 = 12, mean = 11.0),
            arriving("3003", "REG", 20, onTimeAt5 = 10, onTimeAt15 = 12, mean = 9.0, origin = torino),
            // Leaves from the station: a departure, not an arrival.
            train("2153", milano, "REG", 30, 0, stop(milano, 30, 25.0, List(8) { 0 }), stop(torino, 30, 3.0, List(8) { 30 }))
        )
        val station = requireNotNull(NetworkReliabilityAggregates.station(stats, milano))
        assertEquals(6, station.trains)
        assertEquals(104, station.samples)
        // FR 20 (5 min) + REG 6 + REG 0 + IC 20 + REG 12 + REG 12 (15 min).
        assertEquals(70, station.onTime)
        assertEquals(67, station.onTimePercent)
        assertEquals((30 * 5.0 + 10 * 12.0 + 4 * 30.0 + 20 * 4.0 + 20 * 11.0 + 20 * 9.0) / 104, station.meanDelayMinutes, 1e-9)
        // 3001 has fewer than five samples, IC 500 is never late, FR 9600 is late 33% of the time: 3000, 3002 and 3003 are
        // late 40% of the time each, the higher mean delay first.
        assertEquals(listOf("3000", "3002", "3003"), station.oftenLate.map { it.number })
        assertEquals(listOf(4, 8, 8), station.oftenLate.map { it.late })
        assertEquals(torino, station.oftenLate[2].originCode)
        assertNull(NetworkReliabilityAggregates.station(stats, "S99999"))
        assertNull(NetworkReliabilityAggregates.station(stats, null))
        assertNotNull(NetworkReliabilityAggregates.station(stats, torino))
        // Below twenty arrivals in all.
        assertNull(NetworkReliabilityAggregates.station(ReliabilityFixtures.stats(arriving("3000", "REG", 19, 4, 6, 12.0)), milano))
    }
}
