package it.binario.wear

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class WearStringResourceParityTest {
    @Test
    fun `localized Wear string resources have exact base key parity`() {
        val baseKeys = stringKeys("values")

        listOf("values-en", "values-de", "values-fr", "values-es").forEach { qualifier ->
            assertEquals("$qualifier must contain exactly the base string keys", baseKeys, stringKeys(qualifier))
        }
    }

    private fun stringKeys(qualifier: String): Set<String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(resourceRoot, "$qualifier/strings.xml"))
        val stringNodes = document.getElementsByTagName("string")
        val names = (0 until stringNodes.length).map { index ->
            requireNotNull(stringNodes.item(index).attributes.getNamedItem("name")).nodeValue
        }
        check(names.size == names.toSet().size) { "Duplicate string names in $qualifier/strings.xml" }
        return names.toSet()
    }

    private val resourceRoot: File
        get() = sequenceOf(File("src/main/res"), File("wear/src/main/res"))
            .firstOrNull(File::isDirectory)
            ?: error("Unable to locate Wear string resources from ${File(".").absolutePath}")
}
