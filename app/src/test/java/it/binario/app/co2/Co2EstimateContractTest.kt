package it.binario.app.co2

import it.binario.app.shared.SharedData
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * contracts/shared-rules/v1/co2-estimate-v1.json and the factors of contracts/shared-data/v1/co2-estimate-v1.json, read
 * as the iPhone's PersonalFeaturesContractTests read them.
 */
class Co2EstimateContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/co2-estimate-v1.json").readText(Charsets.UTF_8))
    private val tolerance = rules.getDouble("tolerance")

    private fun point(value: Any?): Co2Point? = (value as? JSONArray)?.let { Co2Point(it.getDouble(0), it.getDouble(1)) }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    @Test fun `the factors are the shared table`() {
        val table = JSONObject(File(repositoryRoot, "contracts/shared-data/v1/co2-estimate-v1.json").readText(Charsets.UTF_8))
        assertEquals(table.getString("source"), EmissionFactors.SOURCE)
        assertEquals(table.getString("sourceUrl"), EmissionFactors.SOURCE_URL)
        assertEquals(table.getDouble("trainGramsPerPkm"), EmissionFactors.TRAIN_GRAMS_PER_PKM, 0.0)
        assertEquals(table.getDouble("carGramsPerPkm"), EmissionFactors.CAR_GRAMS_PER_PKM, 0.0)
        assertEquals(table.getDouble("railDetour"), EmissionFactors.RAIL_DETOUR, 0.0)
        assertEquals(table.getDouble("minimumKm"), Co2Estimator.MIN_KM, 0.0)
        assertEquals(table.getDouble("earthRadiusKm"), SharedData.Co2Estimate.EARTH_RADIUS_KM, 0.0)
    }

    @Test fun `great circles`() {
        rules.getJSONArray("greatCircle").objects().forEach { case ->
            val km = Co2Estimator.greatCircleKm(point(case.get("from"))!!, point(case.get("to"))!!)
            assertEquals(case.getString("name"), case.getDouble("expectedKm"), km, tolerance)
        }
    }

    @Test fun `the plane factor`() {
        rules.getJSONArray("planeFactor").objects().forEach { case ->
            assertEquals(case.getDouble("expected"), EmissionFactors.planeGramsPerPkm(case.getDouble("km")), tolerance)
        }
    }

    @Test fun `estimates`() {
        val cases = rules.getJSONArray("estimates").objects()
        assertEquals(6, cases.size)
        cases.forEach { case ->
            val name = case.getString("name")
            val legs = case.getJSONArray("legs").let { array -> (0 until array.length()).map { array.getJSONArray(it) } }
                .map { leg -> point(leg.opt(0)) to point(leg.opt(1)) }
            val estimate = Co2Estimator.estimateOrNull(legs)
            val expected = case.optJSONObject("expected")
            if (expected == null) {
                assertNull(name, estimate)
            } else {
                requireNotNull(estimate) { name }
                assertEquals(name, expected.getDouble("trainKg"), estimate.trainKg, tolerance)
                assertEquals(name, expected.getDouble("carKg"), estimate.carKg, tolerance)
                assertEquals(name, expected.getDouble("planeKg"), estimate.planeKg, tolerance)
                assertEquals(name, expected.getDouble("railKm"), estimate.railKm, tolerance)
            }
        }
    }

    @Test fun `kilograms as shown`() {
        rules.getJSONArray("displayKg").objects().forEach { case ->
            val locale = Locale.forLanguageTag(case.getString("language"))
            assertEquals(case.getString("expected"), Co2Estimator.displayKg(case.getDouble("kg"), locale))
        }
    }
}
