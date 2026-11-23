package it.binario.wear.model

import it.binario.wear.ResourceNameStrings
import it.binario.wear.accessibleLabel
import it.binario.wear.complication.longTextComplicationTexts
import it.binario.wear.glanceNumber
import it.binario.wear.tile.WearTileContent
import it.binario.wear.tile.binarioTileLayout
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("Bus Trenitalia 3601", bus.glanceNumber)
        assertEquals("R 3473", train.glanceNumber)
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
    fun `the watch advertises the capability the phone checks before sending the vehicle`() {
        val wear = File(resourceRoot, "values/wear.xml").readText()
        assertTrue(wear.contains("<item>binario_wear_vehicle_v1</item>"))
    }

    private val resourceRoot: File
        get() = sequenceOf(File("src/main/res"), File("wear/src/main/res")).first(File::isDirectory)

    private fun fixture(name: String): Map<String, Any?> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { key -> json.get(key) }
    }
}
