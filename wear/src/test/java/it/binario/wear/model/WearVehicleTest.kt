package it.binario.wear.model

import it.binario.wear.ResourceNameStrings
import it.binario.wear.WearStringProvider
import it.binario.wear.WearUrbanLabel
import it.binario.wear.accessibleLabel
import it.binario.wear.complication.longTextComplicationTexts
import it.binario.wear.glanceNumber
import it.binario.wear.shownCategory
import it.binario.wear.tile.WearTileContent
import it.binario.wear.tile.binarioTileLayout
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The optional vehicle of the snapshot contract v1 (`service{n}Vehicle`) on the watch. */
class WearVehicleTest {
    private val now = 1_893_484_800_000L

    @Test
    fun `the vehicle fixture decodes a Trenitalia bus and a train`() {
        val decoded = requireNotNull(WearSnapshotCodec.decode(fixture("android-to-wear-v1-vehicle.json"), now))
        assertEquals(WearVehicle.BUS_TRENITALIA, decoded.services[0].vehicle)
        assertTrue(decoded.services[0].isBus)
        assertEquals("Bus Trenitalia", decoded.services[0].category)
        assertEquals(WearVehicle.TRAIN, decoded.services[1].vehicle)
        assertFalse(decoded.services[1].isBus)
    }

    @Test
    fun `the iPhone fixture with every optional field decodes too`() {
        val decoded = requireNotNull(WearSnapshotCodec.decode(fixture("ios-to-apple-watch-v1-optional.json"), now))
        assertEquals(WearReliability(22, 27), decoded.services[0].reliability)
        assertEquals(WearVehicle.TRAIN, decoded.services[0].vehicle)
        assertEquals(WearVehicle.ITALOBUS, decoded.services[1].vehicle)
    }

    @Test
    fun `payloads without the vehicle are still accepted and their services are trains`() {
        listOf("android-to-wear-v1.json", "android-to-wear-v1-reliability.json", "companion-to-wear-v1.json", "ios-to-apple-watch-v1.json").forEach { name ->
            val services = requireNotNull(WearSnapshotCodec.decode(fixture(name), now), { name }).services
            assertTrue(name, services.all { it.vehicle == null && !it.isBus })
        }
    }

    @Test
    fun `an unknown vehicle is shown as a train, a malformed one or one beyond the services rejects the payload`() {
        val valid = fixture("android-to-wear-v1-vehicle.json")
        val future = requireNotNull(WearSnapshotCodec.decode(valid + ("service0Vehicle" to "FERRY"), now)).services[0]
        assertEquals(WearVehicle.UNKNOWN, future.vehicle)
        assertFalse(future.isBus)
        // Each service carries its own: one without it is a train, the other keeps its bus.
        assertNull(requireNotNull(WearSnapshotCodec.decode(valid - "service1Vehicle", now)).services[1].vehicle)
        assertNull(WearSnapshotCodec.decode(valid + ("service0Vehicle" to ""), now))
        assertNull(WearSnapshotCodec.decode(valid + ("service0Vehicle" to " BUS"), now))
        assertNull(WearSnapshotCodec.decode(valid + ("service0Vehicle" to "B".repeat(33)), now))
        assertNull(WearSnapshotCodec.decode(valid + ("service0Vehicle" to 3), now))
        assertNull(WearSnapshotCodec.decode(valid + ("service2Vehicle" to "BUS"), now))
    }

    @Test
    fun `the vehicle survives a round trip of the local cache`() {
        val decoded = requireNotNull(WearSnapshotCodec.decode(fixture("android-to-wear-v1-vehicle.json"), now))
        val encoded = WearSnapshotCodec.encode(decoded)
        assertEquals("BUS_TRENITALIA", encoded["service0Vehicle"])
        assertEquals("TRAIN", encoded["service1Vehicle"])
        assertEquals(decoded, WearSnapshotCodec.decode(encoded, now))
        val plain = requireNotNull(WearSnapshotCodec.decode(fixture("android-to-wear-v1.json"), now))
        assertTrue(WearSnapshotCodec.encode(plain).keys.none { it.endsWith("Vehicle") })
    }

    @Test
    fun `a bus is said and glanced at with its label, never as a train`() {
        val snapshot = requireNotNull(WearSnapshotCodec.decode(fixture("android-to-wear-v1-vehicle.json"), now))
        val (bus, train) = snapshot.services
        assertEquals("Bus Trenitalia 3601", bus.glanceNumber(ResourceNameStrings))
        assertEquals("R 3473", train.glanceNumber(ResourceNameStrings))
        val spoken = bus.accessibleLabel(ResourceNameStrings)
        assertFalse(spoken, spoken.contains("accessibility_train"))
        assertTrue(spoken, spoken.contains("Bus Trenitalia 3601"))
        assertEquals(1, Regex("Bus Trenitalia").findAll(spoken).count())
        assertTrue(train.accessibleLabel(ResourceNameStrings).contains("accessibility_train(R 3473)"))
        // The glances are text only: the label stands where a train shows its number.
        assertEquals("service_time_number(08:10, Bus Trenitalia 3601) · ^1", longTextComplicationTexts(snapshot, ResourceNameStrings, now).text)
        val device = DeviceParametersBuilders.DeviceParameters.Builder().setScreenWidthDp(192).setScreenHeightDp(192)
            .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND).build()
        val tile = binarioTileLayout(WearTileContent.Current(snapshot, bus), device, ResourceNameStrings, "it.binario.wear") as LayoutElementBuilders.Box
        val lines = (tile.contents.single() as LayoutElementBuilders.Column).contents.map { (it as LayoutElementBuilders.Text).text?.value }
        assertTrue(lines.toString(), "tile_service_details(08:10, Bus Trenitalia 3601, Potenza Centrale)" in lines)
    }

    @Test
    fun `the city fixture decodes a metro and a tram, neither a bus nor a train`() {
        val decoded = requireNotNull(WearSnapshotCodec.decode(fixture("ios-to-apple-watch-v1-city.json"), now))
        val (metro, tram) = decoded.services
        assertEquals(WearVehicle.METRO, metro.vehicle)
        assertEquals(WearVehicle.TRAM, tram.vehicle)
        assertTrue(metro.isUrban && tram.isUrban)
        assertFalse(metro.isBus || tram.isBus)
        // The phone sends the line as the number and the mode as the category, for a watch without the field too.
        assertEquals("M2", metro.number)
        assertEquals("Metro", metro.category)
        // A funicular too; each service carries its own.
        val funicular = requireNotNull(WearSnapshotCodec.decode(fixture("ios-to-apple-watch-v1-city.json") + ("service1Vehicle" to "FUNICULAR"), now))
        assertEquals(listOf(WearVehicle.METRO, WearVehicle.FUNICULAR), funicular.services.map { it.vehicle })
        assertTrue(funicular.services[1].isUrban)
        // The cache keeps them.
        val encoded = WearSnapshotCodec.encode(decoded)
        assertEquals(listOf("METRO", "TRAM"), listOf(encoded["service0Vehicle"], encoded["service1Vehicle"]))
        assertEquals(decoded, WearSnapshotCodec.decode(encoded, now))
    }

    @Test
    fun `a city line is said and glanced at with its mode and line, never as a train`() {
        val snapshot = requireNotNull(WearSnapshotCodec.decode(fixture("ios-to-apple-watch-v1-city.json"), now))
        val (metro, tram) = snapshot.services
        // The watch names the mode itself, in its own language (vehicle_metro is the phone's journey_leg_metro).
        assertEquals("vehicle_metro", metro.shownCategory(ResourceNameStrings))
        assertEquals("vehicle_metro M2", metro.glanceNumber(ResourceNameStrings))
        assertEquals("vehicle_tram 10", tram.glanceNumber(ResourceNameStrings))
        assertEquals("Metro M2", metro.glanceNumber(italian))
        assertEquals("Tram 10", tram.glanceNumber(italian))
        // A line that only repeats the mode is left out, as on the phones; a bus and a train keep the category the phone sent.
        assertEquals("Metro", metro.copy(number = "METRO").glanceNumber(italian))
        assertEquals("Funicolare", metro.copy(number = "Zecca - Righi", vehicle = WearVehicle.FUNICULAR).glanceNumber(italian))
        assertEquals("Metro", metro.copy(vehicle = WearVehicle.UNKNOWN).shownCategory(italian))
        val spoken = metro.accessibleLabel(ResourceNameStrings)
        assertFalse(spoken, spoken.contains("accessibility_train"))
        assertTrue(spoken, spoken.contains("vehicle_metro M2"))
        assertFalse(spoken, spoken.contains(". Metro."))
        // An unknown vehicle, as an earlier watch app read a city line, is a train.
        assertTrue(metro.copy(vehicle = WearVehicle.UNKNOWN).accessibleLabel(ResourceNameStrings).contains("accessibility_train(M2)"))
        assertEquals("service_time_number(09:04, vehicle_metro M2) · ^1", longTextComplicationTexts(snapshot, ResourceNameStrings, now).text)
        val device = DeviceParametersBuilders.DeviceParameters.Builder().setScreenWidthDp(192).setScreenHeightDp(192)
            .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND).build()
        val tile = binarioTileLayout(WearTileContent.Current(snapshot, metro), device, ResourceNameStrings, "it.binario.wear") as LayoutElementBuilders.Box
        val lines = (tile.contents.single() as LayoutElementBuilders.Column).contents.map { (it as LayoutElementBuilders.Text).text?.value }
        assertTrue(lines.toString(), "tile_service_details(09:04, vehicle_metro M2, Abbiategrasso)" in lines)
    }

    @Test
    fun `the label of a city line follows the phones' shared cases`() {
        val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/journey-leg-vehicles-v1.json").readText())
        val cases = rules.getJSONArray("urbanLabels")
        assertTrue(cases.length() > 0)
        for (position in 0 until cases.length()) {
            val case = cases.getJSONObject(position)
            val why = case.getString("why")
            assertEquals(why, case.getBoolean("repeatsMode"), WearUrbanLabel.repeatsMode(case.getString("line"), case.getString("label")))
            assertEquals(why, case.getString("shown"), WearUrbanLabel.of(case.getString("label"), case.getString("line")))
        }
        assertTrue(rules.getJSONObject("readers").getString("wearOs").endsWith("WearVehicleTest.kt"))
    }

    @Test
    fun `the watch names the city modes with the phone's texts in every language`() {
        val phone = File(repositoryRoot, "app/src/main/res")
        val names = mapOf("vehicle_metro" to "journey_leg_metro", "vehicle_tram" to "journey_leg_tram", "vehicle_funicular" to "journey_leg_funicular")
        val folders = resourceRoot.listFiles { file -> file.isDirectory && File(file, "strings.xml").isFile }.orEmpty()
        assertEquals(17, folders.size)
        folders.forEach { folder ->
            val watch = strings(File(folder, "strings.xml"))
            val phoneTexts = File(phone, folder.name).listFiles { file -> file.extension == "xml" }.orEmpty().map { strings(it) }.fold(emptyMap<String, String>()) { all, map -> all + map }
            names.forEach { (watchName, phoneName) ->
                assertEquals("${folder.name}/$watchName", phoneTexts.getValue(phoneName), watch.getValue(watchName))
            }
        }
    }

    @Test
    fun `the watch advertises the capability the phone checks before sending the vehicle`() {
        val wear = File(resourceRoot, "values/wear.xml").readText()
        assertTrue(wear.contains("<item>binario_wear_vehicle_v1</item>"))
    }

    private val resourceRoot: File
        get() = sequenceOf(File("src/main/res"), File("wear/src/main/res")).first(File::isDirectory)

    private val repositoryRoot: File
        get() = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    /** The Italian texts of the city modes (values/strings.xml), every other string by its resource name. */
    private val italian = WearStringProvider { id, formatArgs ->
        val name = ResourceNameStrings.string(id)
        strings(File(resourceRoot, "values/strings.xml"))[name]?.takeIf { formatArgs.isEmpty() } ?: ResourceNameStrings.string(id, *formatArgs)
    }

    /** The plain `<string>` entries of a resource file. */
    private fun strings(file: File): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun fixture(name: String): Map<String, Any?> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { key -> json.get(key) }
    }
}
