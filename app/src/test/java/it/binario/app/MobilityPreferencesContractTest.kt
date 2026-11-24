package it.binario.app

import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.shared.SharedData
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-rules/v1/mobility-preferences-v1.json and contracts/shared-data/v1/mobility-preferences-v1.json, read
 * as the iPhone's MobilityPreferencesContractTests read them: «Mobilità e accessibilità» in the local backup.
 */
class MobilityPreferencesContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/mobility-preferences-v1.json").readText(Charsets.UTF_8))
    private val milano = Station("S01700", "Milano Centrale", "Milano")
    private val pavia = Station("S01860", "Pavia", "Pavia")

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.ints(): List<Int> = (0 until length()).map { getInt(it) }

    private fun preferences(value: JSONObject) = MobilityPreferences(
        maxChanges = if (value.isNull("maxChanges")) null else value.getInt("maxChanges"),
        transferMarginMinutes = value.getInt("transferMarginMinutes"),
        stepFreePreferred = value.getBoolean("stepFreePreferred"),
        compactTimetablePreferred = value.getBoolean("compactTimetablePreferred")
    )

    private fun backup(mobility: MobilityPreferences, routes: Boolean = false) = LocalDataBackup(
        if (routes) listOf(SavedRoute("r1", "Lavoro", milano, pavia)) else emptyList(),
        emptySet(), emptyList(), emptyList(), emptyList(), mobility
    )

    @Test fun `the preferences read the shared table`() {
        val table = JSONObject(File(repositoryRoot, "contracts/shared-data/v1/mobility-preferences-v1.json").readText(Charsets.UTF_8))
        assertEquals(table.getInt("defaultTransferMarginMinutes"), MobilityPreferences().transferMarginMinutes)
        assertEquals(table.getInt("maximumTransferMarginMinutes"), SharedData.MobilityPreferences.MAXIMUM_TRANSFER_MARGIN_MINUTES)
        assertEquals(table.getJSONArray("changeChoices").ints(), SharedData.MobilityPreferences.CHANGE_CHOICES)
        assertEquals(table.getJSONArray("transferMarginChoices").ints(), SharedData.MobilityPreferences.TRANSFER_MARGIN_CHOICES)
    }

    @Test fun `from a backup`() {
        val cases = rules.getJSONArray("fromBackup").objects()
        assertEquals(9, cases.size)
        cases.forEach { case ->
            val root = JSONObject()
                .put("schemaVersion", 2)
                .put("kind", SharedData.LocalBackup.KIND)
                .put("routes", JSONArray().put(JSONObject().put("id", "r1").put("name", "Lavoro").put("originId", milano.id).put("destinationId", pavia.id)))
            if (!case.isNull("mobility")) root.put("mobility", case.getJSONObject("mobility"))
            val result = LocalDataBackupCodec.decode(root.toString(), listOf(milano, pavia))
            assertTrue(case.getString("name"), result is LocalBackupResult.Success)
            assertEquals(case.getString("name"), preferences(case.getJSONObject("expected")), (result as LocalBackupResult.Success).backup.mobilityPreferences)
        }
    }

    @Test fun `to a backup`() {
        val cases = rules.getJSONArray("toBackup").objects()
        assertEquals(3, cases.size)
        cases.forEach { case ->
            val written = JSONObject(LocalDataBackupCodec.encode(backup(preferences(case.getJSONObject("preferences")), routes = true)))
                .getJSONObject("mobility")
            val expected = case.getJSONObject("expected")
            assertEquals(case.getString("name"), expected.keys().asSequence().toSet(), written.keys().asSequence().toSet())
            expected.keys().forEach { key -> assertEquals("${case.getString("name")}: $key", expected.get(key), written.get(key)) }
        }
    }

    @Test fun `carries more than routes`() {
        val cases = rules.getJSONArray("carriesMoreThanRoutes").objects()
        assertEquals(5, cases.size)
        cases.forEach { case ->
            assertEquals(case.getString("name"), case.getBoolean("expected"), backup(preferences(case.getJSONObject("preferences"))).carriesMoreThanRoutes)
        }
    }
}
