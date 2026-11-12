package it.binario.app.shortcuts

import it.binario.app.BinarioDeepLink
import it.binario.app.BinarioDeepLinkParser
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The launcher shortcuts of `res/xml/shortcuts.xml` open allowlisted destinations, the Agenda among them. */
class ManifestShortcutsTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test fun `every static shortcut opens an allowlisted destination and one opens the Agenda`() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(root, "app/src/main/res/xml/shortcuts.xml"))
        val shortcuts = document.getElementsByTagName("shortcut")
        val destinations = (0 until shortcuts.length).map { index ->
            val shortcut = shortcuts.item(index) as Element
            assertTrue(shortcut.getAttributeNS(androidNs, "shortcutShortLabel").startsWith("@string/"))
            val intent = shortcut.getElementsByTagName("intent").item(0) as Element
            val link = BinarioDeepLinkParser.parse(intent.getAttributeNS(androidNs, "action"), intent.getAttributeNS(androidNs, "data"))
            assertNotNull(shortcut.getAttributeNS(androidNs, "shortcutId"), link)
            shortcut.getAttributeNS(androidNs, "shortcutId") to link
        }
        assertEquals(BinarioDeepLink.Agenda, destinations.toMap()["agenda"])
        assertEquals(destinations.size, destinations.map { it.first }.toSet().size)
    }
}
