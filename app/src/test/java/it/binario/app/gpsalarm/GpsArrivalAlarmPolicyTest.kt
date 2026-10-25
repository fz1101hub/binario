package it.binario.app.gpsalarm

import it.binario.app.data.RailOperator
import it.binario.app.data.TrainCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsArrivalAlarmPolicyTest {
    private val stationLatitude = 45.0
    private val stationLongitude = 9.0
    private val metersPerDegreeLatitude = 111_195.08

    private fun fixNorth(meters: Double, accuracy: Double? = 10.0, ageMillis: Long = 1_000L) = GpsFix(
        latitude = stationLatitude + meters / metersPerDegreeLatitude,
        longitude = stationLongitude,
        accuracyMeters = accuracy,
        ageMillis = ageMillis
    )

    private fun evaluate(fix: GpsFix, radius: Int) = GpsArrivalAlarmPolicy.evaluate(fix, stationLatitude, stationLongitude, radius)

    @Test
    fun `haversine distance matches known great-circle distances`() {
        assertEquals(111_195.0, GpsArrivalAlarmPolicy.distanceMeters(0.0, 0.0, 1.0, 0.0), 1.0)
        // Milano Centrale to Roma Termini, about 478.6 km as the crow flies.
        assertEquals(478_618.0, GpsArrivalAlarmPolicy.distanceMeters(45.4859, 9.2047, 41.9009, 12.5016), 50.0)
        assertEquals(0.0, GpsArrivalAlarmPolicy.distanceMeters(45.0, 9.0, 45.0, 9.0), 0.0)
        assertEquals(
            GpsArrivalAlarmPolicy.distanceMeters(45.0, 9.0, 43.7, 11.2),
            GpsArrivalAlarmPolicy.distanceMeters(43.7, 11.2, 45.0, 9.0),
            1e-6
        )
    }

    @Test
    fun `alarm triggers when distance minus accuracy is within the radius`() {
        val outside = evaluate(fixNorth(2_500.0, accuracy = 400.0), 2_000)
        assertTrue(outside is GpsFixDecision.Approaching)
        assertEquals(100.0, (outside as GpsFixDecision.Approaching).remainingMeters, 2.0)

        assertTrue(evaluate(fixNorth(2_500.0, accuracy = 600.0), 2_000) is GpsFixDecision.Arrived)
        assertTrue(evaluate(fixNorth(1_999.0, accuracy = 0.0), 2_000) is GpsFixDecision.Arrived)
        assertTrue(evaluate(fixNorth(0.0), 1_000) is GpsFixDecision.Arrived)
    }

    @Test
    fun `stale fixes are ignored`() {
        assertEquals(
            GpsFixDecision.Rejected(GpsFixRejection.STALE),
            evaluate(fixNorth(0.0, ageMillis = GpsArrivalAlarmPolicy.MAX_FIX_AGE_MILLIS + 1), 2_000)
        )
        assertTrue(evaluate(fixNorth(0.0, ageMillis = GpsArrivalAlarmPolicy.MAX_FIX_AGE_MILLIS), 2_000) is GpsFixDecision.Arrived)
    }

    @Test
    fun `fixes worse than five kilometres or without accuracy are ignored`() {
        val inaccurate = GpsFixDecision.Rejected(GpsFixRejection.INACCURATE)
        assertEquals(inaccurate, evaluate(fixNorth(6_000.0, accuracy = 5_000.1), 2_000))
        assertEquals(inaccurate, evaluate(fixNorth(0.0, accuracy = null), 2_000))
        assertEquals(inaccurate, evaluate(fixNorth(0.0, accuracy = Double.NaN), 2_000))
        assertEquals(inaccurate, evaluate(fixNorth(0.0, accuracy = -1.0), 2_000))
        assertTrue(evaluate(fixNorth(6_000.0, accuracy = 5_000.0), 2_000) is GpsFixDecision.Arrived)
    }

    @Test
    fun `invalid coordinates are ignored`() {
        val invalid = GpsFixDecision.Rejected(GpsFixRejection.INVALID)
        assertEquals(invalid, evaluate(GpsFix(Double.NaN, 9.0, 10.0, 0L), 2_000))
        assertEquals(invalid, evaluate(GpsFix(91.0, 9.0, 10.0, 0L), 2_000))
        assertEquals(invalid, evaluate(GpsFix(45.0, 181.0, 10.0, 0L), 2_000))
    }

    @Test
    fun `approximate location never shrinks the chosen radius`() {
        GpsArrivalAlarmPolicy.RADIUS_OPTIONS_METERS.forEach { radius ->
            assertEquals(radius, GpsArrivalAlarmPolicy.effectiveRadiusMeters(radius, precise = true))
            assertTrue(GpsArrivalAlarmPolicy.effectiveRadiusMeters(radius, precise = false) > radius)
        }
    }

    @Test
    fun `radius options go from one to twenty-five kilometres`() {
        assertEquals(listOf(1_000, 2_000, 3_000, 5_000, 10_000, 15_000, 20_000, 25_000), GpsArrivalAlarmPolicy.RADIUS_OPTIONS_METERS)
        assertTrue(GpsArrivalAlarmPolicy.isRadiusOption(25_000))
        assertTrue(!GpsArrivalAlarmPolicy.isRadiusOption(4_000))
    }

    @Test
    fun `interval is half the time a 300 km per hour train needs to reach the boundary of a 25 km radius`() {
        val radius = 25_000
        // 30 km away with 10 m accuracy: 4.99 km to the boundary, 4990 / 83.3 * 0.5 = 29.9 s, stepped to 25 s.
        assertEquals(25_000L, intervalAt(distance = 30_000.0, radius = radius))
        // 45 km away: 19.99 km to the boundary = 119.9 s, rounded down to 115 s.
        assertEquals(115_000L, intervalAt(distance = 45_000.0, radius = radius))
        assertEquals(120_000L, intervalAt(distance = 46_000.0, radius = radius))
        // Far away the interval is capped at two minutes.
        assertEquals(120_000L, intervalAt(distance = 200_000.0, radius = radius))
        // Close to the boundary it never drops below ten seconds.
        assertEquals(10_000L, intervalAt(distance = 25_500.0, radius = radius))
    }

    @Test
    fun `interval for a 1 km radius uses the same boundary distance`() {
        val radius = 1_000
        assertEquals(120_000L, intervalAt(distance = 30_000.0, radius = radius))
        // 11 km away: 9.99 km to the boundary, 59.9 s stepped to 55 s.
        assertEquals(55_000L, intervalAt(distance = 11_000.0, radius = radius))
        assertEquals(10_000L, intervalAt(distance = 2_000.0, radius = radius))
    }

    @Test
    fun `interval without a usable fix is the shortest one`() {
        assertEquals(GpsArrivalAlarmPolicy.MIN_INTERVAL_MILLIS, GpsArrivalAlarmPolicy.nextIntervalMillis(null))
        assertEquals(GpsArrivalAlarmPolicy.MIN_INTERVAL_MILLIS, GpsArrivalAlarmPolicy.nextIntervalMillis(Double.NaN))
        assertEquals(GpsArrivalAlarmPolicy.MAX_INTERVAL_MILLIS, GpsArrivalAlarmPolicy.nextIntervalMillis(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `next fix never comes after a 300 km per hour train could cross the boundary`() {
        var remaining = 0.0
        while (remaining <= 200_000.0) {
            val interval = GpsArrivalAlarmPolicy.nextIntervalMillis(remaining)
            val travelled = GpsArrivalAlarmPolicy.MAX_TRAIN_SPEED_METERS_PER_SECOND * interval / 1_000.0
            if (interval > GpsArrivalAlarmPolicy.MIN_INTERVAL_MILLIS) {
                assertTrue("remaining=$remaining interval=$interval", travelled <= remaining * GpsArrivalAlarmPolicy.INTERVAL_SAFETY_FACTOR + 1e-6)
            }
            remaining += 250.0
        }
    }

    @Test
    fun `simulated 300 km per hour approach rings before the train is deep inside the radius`() {
        listOf(25_000, 1_000).forEach { radius ->
            val speed = GpsArrivalAlarmPolicy.MAX_TRAIN_SPEED_METERS_PER_SECOND
            var distance = 120_000.0
            var ringAt: Double? = null
            var fixes = 0
            while (ringAt == null && distance > -10_000.0) {
                fixes += 1
                when (val decision = evaluate(fixNorth(distance), radius)) {
                    is GpsFixDecision.Arrived -> ringAt = distance
                    is GpsFixDecision.Approaching -> distance -= speed * GpsArrivalAlarmPolicy.nextIntervalMillis(decision.remainingMeters) / 1_000.0
                    is GpsFixDecision.Rejected -> error("unexpected $decision")
                }
            }
            val ring = requireNotNull(ringAt) { "radius $radius never rang" }
            // At worst the last step is the ten-second minimum: about 833 m at 300 km/h.
            assertTrue("radius=$radius rang at $ring", ring > radius - speed * GpsArrivalAlarmPolicy.MIN_INTERVAL_MILLIS / 1_000.0)
            assertTrue("radius=$radius took $fixes fixes", fixes < 100)
        }
    }

    @Test
    fun `service stops two hours after arrival with an eight hour hard cap`() {
        val start = 1_000_000L
        val hour = 60 * 60_000L
        assertEquals(start + 3 * hour, GpsArrivalAlarmPolicy.expiresAtEpochMillis(start, start + hour))
        assertEquals(start + 8 * hour, GpsArrivalAlarmPolicy.expiresAtEpochMillis(start, start + 7 * hour))
        val target = target(expires = start + hour)
        assertTrue(!GpsArrivalAlarmPolicy.isExpired(start + hour - 1, target))
        assertTrue(GpsArrivalAlarmPolicy.isExpired(start + hour, target))
    }

    @Test
    fun `gps option needs coordinates and an arrival within the cap`() {
        val now = 0L
        val hour = 60 * 60_000L
        assertEquals(GpsAlarmAvailability.NO_COORDINATES, GpsArrivalAlarmPolicy.availability(now, now + hour, hasCoordinates = false))
        assertEquals(GpsAlarmAvailability.TOO_EARLY, GpsArrivalAlarmPolicy.availability(now, now + 8 * hour + 1, hasCoordinates = true))
        assertEquals(GpsAlarmAvailability.AVAILABLE, GpsArrivalAlarmPolicy.availability(now, now + 8 * hour, hasCoordinates = true))
        assertEquals(8, GpsArrivalAlarmPolicy.maxDurationHours)
    }

    @Test
    fun `long distance and high speed trains default to a 20 km radius`() {
        val longDistance = listOf(
            TrainCategory.FRECCE to RailOperator.TRENITALIA_AV,
            TrainCategory.AV_ITALO to RailOperator.ITALO,
            TrainCategory.INTERCITY to RailOperator.TRENITALIA_LONG_DISTANCE,
            TrainCategory.OTHER to RailOperator.ITALO,
            TrainCategory.OTHER to RailOperator.OEBB,
            TrainCategory.OTHER to RailOperator.DB,
            TrainCategory.OTHER to RailOperator.EUROPEAN_SLEEPER
        )
        longDistance.forEach { (category, operator) ->
            val trainClass = GpsArrivalAlarmPolicy.trainClass(category, operator)
            assertEquals("$category/$operator", GpsAlarmTrainClass.LONG_DISTANCE, trainClass)
            assertEquals(20_000, GpsArrivalAlarmPolicy.defaultRadiusMeters(trainClass))
        }
    }

    @Test
    fun `regional and suburban trains default to a 2 km radius`() {
        val regional = listOf(
            TrainCategory.REGIONALE to RailOperator.TRENITALIA_REGIONALE,
            // Regional Trenitalia GTFS routes also carry the long-distance operator: the category wins.
            TrainCategory.REGIONALE to RailOperator.TRENITALIA_LONG_DISTANCE,
            TrainCategory.REGIONALE_VELOCE to RailOperator.TRENITALIA_REGIONALE,
            TrainCategory.TRENORD_REGIO to RailOperator.TRENORD,
            TrainCategory.METROPOLITANO to RailOperator.OTHER,
            TrainCategory.OTHER to RailOperator.TILO,
            TrainCategory.OTHER to RailOperator.OTHER
        )
        regional.forEach { (category, operator) ->
            val trainClass = GpsArrivalAlarmPolicy.trainClass(category, operator)
            assertEquals("$category/$operator", GpsAlarmTrainClass.REGIONAL, trainClass)
            assertEquals(2_000, GpsArrivalAlarmPolicy.defaultRadiusMeters(trainClass))
        }
    }

    @Test
    fun `remembered radius is used only while it is still an option`() {
        assertEquals(15_000, GpsArrivalAlarmPolicy.initialRadiusMeters(GpsAlarmTrainClass.LONG_DISTANCE, 15_000))
        assertEquals(20_000, GpsArrivalAlarmPolicy.initialRadiusMeters(GpsAlarmTrainClass.LONG_DISTANCE, 4_000))
        assertEquals(2_000, GpsArrivalAlarmPolicy.initialRadiusMeters(GpsAlarmTrainClass.REGIONAL, null))
    }

    @Test
    fun `providers follow the granted precision and the platform version`() {
        val both = listOf("passive", "network", "gps")
        assertEquals(listOf("gps", "network"), GpsArrivalAlarmPolicy.locationProviders(precise = true, sdkInt = 26, existingProviders = both))
        assertEquals(listOf("gps"), GpsArrivalAlarmPolicy.locationProviders(precise = true, sdkInt = 36, existingProviders = listOf("gps")))
        assertEquals(listOf("network"), GpsArrivalAlarmPolicy.locationProviders(precise = false, sdkInt = 36, existingProviders = both))
        // Android 12+ serves coarsened GPS fixes to approximate-only apps; earlier versions do not.
        assertEquals(listOf("gps"), GpsArrivalAlarmPolicy.locationProviders(precise = false, sdkInt = 31, existingProviders = listOf("gps")))
        assertEquals(emptyList<String>(), GpsArrivalAlarmPolicy.locationProviders(precise = false, sdkInt = 30, existingProviders = listOf("gps")))
    }

    private fun intervalAt(distance: Double, radius: Int, accuracy: Double = 10.0): Long {
        val decision = evaluate(fixNorth(distance, accuracy), radius) as GpsFixDecision.Approaching
        return GpsArrivalAlarmPolicy.nextIntervalMillis(decision.remainingMeters)
    }

    private fun target(expires: Long) = GpsArrivalAlarmTarget(
        trainNumber = "9512",
        trainDestination = "Napoli Centrale",
        stopName = "Roma Termini",
        stationId = "curated:S08409",
        latitude = 41.900636,
        longitude = 12.502026,
        radiusMeters = 20_000,
        expectedTime = "12:00",
        arrivalEpochMillis = expires - GpsArrivalAlarmPolicy.AFTER_ARRIVAL_MILLIS,
        timeAlarmTriggerEpochMillis = expires - GpsArrivalAlarmPolicy.AFTER_ARRIVAL_MILLIS - 300_000L,
        expiresAtEpochMillis = expires
    )
}
