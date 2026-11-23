package it.binario.app.data.gtfs

import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-rules/v1/gtfs-station-codes-v1.json: the station-code cases the iPhone index builder
 * (tools/android_station_codes.py, read by tests/test_ios_gtfs_schedule.py) shares with [GtfsStationCodeIndex], so a
 * stop of the Trenitalia and Italo packs is the same catalogue station on both phones.
 */
class GtfsStationCodesContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/gtfs-station-codes-v1.json").readText(Charsets.UTF_8))

    @Test fun `every shared stop code names the shared catalogue station`() {
        val catalogue = File(repositoryRoot, "app/src/main/assets")
        assertEquals("app/src/main/assets/${EmbeddedCuratedRailStationCatalog.ASSET_PATH}", rules.getString("catalogue"))
        val index = GtfsStationCodeIndex(EmbeddedCuratedRailStationCatalog({ File(catalogue, it).inputStream() }).load())
        val cases = rules.getJSONArray("stationCodes")
        assertTrue(cases.length() > 0)
        for (position in 0 until cases.length()) {
            val case = cases.getJSONObject(position)
            val code = case.getString("stopCode")
            val latitude = if (case.isNull("latitude")) null else case.getDouble("latitude")
            val longitude = if (case.isNull("longitude")) null else case.getDouble("longitude")
            val expected = if (case.isNull("expected")) null else case.getString("expected")
            val station = index.resolve(GtfsStopCodeScheme.valueOf(case.getString("scheme")), code, latitude, longitude, case.getString("name"))
            assertEquals("$code «${case.getString("name")}»: ${case.getString("why")}", expected, station?.id?.removePrefix("curated:"))
        }
    }
}
