package it.binario.wear.model

import it.binario.wear.R
import it.binario.wear.ResourceNameStrings
import it.binario.wear.accessibleLabel
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The optional reliability pair of the snapshot contract v1 («In orario 22 volte su 27») on the watch. */
class WearReliabilityTest {
    private val now = 1_893_484_800_000L

    @Test
    fun `the reliability fixture decodes the pair of its first service only`() {
        val decoded = requireNotNull(WearSnapshotCodec.decode(fixture("android-to-wear-v1-reliability.json"), now))
        assertEquals(WearReliability(onTime = 22, samples = 27), decoded.services[0].reliability)
        assertNull(decoded.services[1].reliability)
        // Everything else is the payload of the fixture without the pair.
        val plain = requireNotNull(WearSnapshotCodec.decode(fixture("android-to-wear-v1.json"), now))
        assertEquals(plain, decoded.copy(services = decoded.services.map { it.copy(reliability = null) }))
        assertTrue(plain.services.all { it.reliability == null })
    }

    @Test
    fun `payloads of producers without the pair are still accepted`() {
        listOf("android-to-wear-v1.json", "companion-to-wear-v1.json", "ios-to-apple-watch-v1.json").forEach { name ->
            assertTrue(name, WearSnapshotCodec.decode(fixture(name), now)?.services?.all { it.reliability == null } == true)
        }
    }

    @Test
    fun `half a pair, values out of range or a pair beyond the services reject the payload`() {
        val valid = fixture("android-to-wear-v1-reliability.json")
        assertNull(WearSnapshotCodec.decode(valid - "service0ReliabilitySamples", now))
        assertNull(WearSnapshotCodec.decode(valid - "service0ReliabilityOnTime", now))
        assertNull(WearSnapshotCodec.decode(valid + ("service0ReliabilitySamples" to 4), now))
        assertNull(WearSnapshotCodec.decode(valid + ("service0ReliabilitySamples" to 10_001), now))
        assertNull(WearSnapshotCodec.decode(valid + ("service0ReliabilityOnTime" to 28), now))
        assertNull(WearSnapshotCodec.decode(valid + ("service0ReliabilityOnTime" to -1), now))
        assertNull(WearSnapshotCodec.decode(valid + ("service0ReliabilityOnTime" to "22"), now))
        assertNull(WearSnapshotCodec.decode(valid + ("service0ReliabilityOnTime" to 21.5), now))
        assertNull(WearSnapshotCodec.decode(valid + mapOf("service2ReliabilityOnTime" to 1, "service2ReliabilitySamples" to 5), now))
        assertEquals(WearReliability(27, 27), WearSnapshotCodec.decode(valid + ("service0ReliabilityOnTime" to 27), now)?.services?.first()?.reliability)
        assertEquals(WearReliability(0, 5), WearSnapshotCodec.decode(valid + mapOf("service0ReliabilityOnTime" to 0, "service0ReliabilitySamples" to 5), now)?.services?.first()?.reliability)
    }

    @Test
    fun `the pair survives a round trip of the local cache`() {
        val decoded = requireNotNull(WearSnapshotCodec.decode(fixture("android-to-wear-v1-reliability.json"), now))
        val encoded = WearSnapshotCodec.encode(decoded)
        assertEquals(22, encoded["service0ReliabilityOnTime"])
        assertEquals(27, encoded["service0ReliabilitySamples"])
        assertTrue("service1ReliabilityOnTime" !in encoded)
        assertEquals(decoded, WearSnapshotCodec.decode(encoded, now))
    }

    @Test
    fun `the spoken label of the screen ends with the unofficial reliability, the complication's has none`() {
        val service = requireNotNull(WearSnapshotCodec.decode(fixture("android-to-wear-v1-reliability.json"), now)).services.first()
        val withReliability = service.accessibleLabel(ResourceNameStrings, "In orario 22 volte su 27")
        assertTrue(withReliability, withReliability.endsWith("accessibility_reliability(In orario 22 volte su 27)"))
        assertTrue(!service.accessibleLabel(ResourceNameStrings).contains("accessibility_reliability"))
        assertTrue(R.plurals.service_reliability_on_time != 0)
    }

    @Test
    fun `the watch advertises the capability the phone checks before sending the pair`() {
        val wear = File(resourceRoot, "values/wear.xml").readText()
        assertTrue(wear.contains("<string-array name=\"android_wear_capabilities\" translatable=\"false\">"))
        assertTrue(wear.contains("<item>binario_wear_reliability_v1</item>"))
        assertTrue(wear.contains("tools:keep=\"@array/android_wear_capabilities\""))
    }

    @Test
    fun `the reliability plural has the CLDR quantities of every language and keeps both numbers`() {
        val failures = QUANTITIES.flatMap { (qualifier, expected) ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resourceRoot, "$qualifier/strings.xml"))
            val plurals = document.getElementsByTagName("plurals")
            val plural = (0 until plurals.length).map { plurals.item(it) as Element }.singleOrNull { it.getAttribute("name") == "service_reliability_on_time" }
                ?: return@flatMap listOf("$qualifier: missing service_reliability_on_time")
            val items = plural.getElementsByTagName("item")
            val byQuantity = (0 until items.length).map { items.item(it) as Element }.associate { it.getAttribute("quantity") to it.textContent }
            listOfNotNull("$qualifier quantities ${byQuantity.keys}".takeIf { byQuantity.keys != expected }) +
                byQuantity.filterValues { !(it.contains("%1\$d") && it.contains("%2\$d")) }.keys.map { "$qualifier/$it placeholders" }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private val resourceRoot: File
        get() = sequenceOf(File("src/main/res"), File("wear/src/main/res")).first(File::isDirectory)

    private fun fixture(name: String): Map<String, Any?> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { key -> json.get(key) }
    }

    private companion object {
        val OTHER = setOf("other")
        val ONE_OTHER = setOf("one", "other")
        val ONE_MANY_OTHER = setOf("one", "many", "other")
        val QUANTITIES = mapOf(
            "values" to ONE_MANY_OTHER, "values-en" to ONE_OTHER, "values-de" to ONE_OTHER, "values-fr" to ONE_MANY_OTHER,
            "values-es" to ONE_MANY_OTHER, "values-ar" to setOf("zero", "one", "two", "few", "many", "other"), "values-hi" to ONE_OTHER,
            "values-in" to OTHER, "values-ja" to OTHER, "values-ko" to OTHER, "values-my" to OTHER, "values-pt" to ONE_MANY_OTHER,
            "values-ru" to setOf("one", "few", "many", "other"), "values-th" to OTHER, "values-tr" to ONE_OTHER, "values-vi" to OTHER,
            "values-zh-rCN" to OTHER
        )
    }
}
