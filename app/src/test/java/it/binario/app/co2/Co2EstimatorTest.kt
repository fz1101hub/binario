package it.binario.app.co2

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Co2EstimatorTest {
    private val roma = Co2Point(41.9010, 12.5016)
    private val firenze = Co2Point(43.7764, 11.2480)
    private val milano = Co2Point(45.4862, 9.2045)

    @Test fun `great circle distance between Roma Termini and Milano Centrale`() {
        assertEquals(477.0, Co2Estimator.greatCircleKm(roma, milano), 3.0)
        assertEquals(0.0, Co2Estimator.greatCircleKm(roma, roma), 1e-9)
    }

    @Test fun `a direct train uses the rail detour and the published factors`() {
        val estimate = Co2Estimator.estimate(listOf(Co2Leg(roma, milano)))!!
        val km = Co2Estimator.greatCircleKm(roma, milano)
        assertEquals(km * 1.2, estimate.railKm, 1e-6)
        assertEquals(km * 1.2 * 18.0 / 1000, estimate.trainKg, 1e-6)
        assertEquals(km * 1.25 * 111.0 / 1000, estimate.carKg, 1e-6)
        val flightKm = km * 1.08
        assertEquals(flightKm * EmissionFactors.planeGramsPerPkm(flightKm) / 1000, estimate.planeKg, 1e-6)
        assertTrue(estimate.trainKg < estimate.carKg && estimate.carKg < estimate.planeKg)
    }

    @Test fun `a journey with a change sums its legs while car and plane go straight`() {
        val legs = Co2Estimator.estimate(listOf(Co2Leg(roma, firenze), Co2Leg(firenze, milano)))!!
        val direct = Co2Estimator.estimate(listOf(Co2Leg(roma, milano)))!!
        assertTrue(legs.railKm > direct.railKm)
        assertEquals(direct.carKg, legs.carKg, 1e-9)
        assertEquals(direct.planeKg, legs.planeKg, 1e-9)
    }

    @Test fun `no estimate without every station position or for a trip too short`() {
        assertNull(Co2Estimator.estimateOrNull(listOf(roma to firenze, null to milano)))
        assertNull(Co2Estimator.estimate(emptyList()))
        assertNull(Co2Estimator.estimate(listOf(Co2Leg(roma, Co2Point(41.9020, 12.5020)))))
    }

    @Test fun `the plane factor follows the two published distances`() {
        assertEquals(340.0, EmissionFactors.planeGramsPerPkm(300.0), 1e-9)
        assertEquals(305.0, EmissionFactors.planeGramsPerPkm(750.0), 1e-9)
        assertEquals(270.0, EmissionFactors.planeGramsPerPkm(1500.0), 1e-9)
    }

    @Test fun `kilograms are shown with the precision of an estimate`() {
        assertEquals("4,8", Co2Estimator.displayKg(4.8123, Locale.ITALIAN))
        assertEquals("4.8", Co2Estimator.displayKg(4.8123, Locale.ENGLISH))
        assertEquals("66", Co2Estimator.displayKg(66.2, Locale.ITALIAN))
    }
}
