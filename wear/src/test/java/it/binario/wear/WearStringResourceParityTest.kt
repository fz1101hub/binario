package it.binario.wear

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The Wear strings in every language of the phone app (docs/l10n/LOCALES.md): same keys, placeholders, clean text. */
class WearStringResourceParityTest {
    @Test
    fun `localized Wear string resources have exact base key parity`() {
        val baseKeys = strings("values").keys
        val missing = QUALIFIERS.filterNot { File(resourceRoot, "$it/strings.xml").isFile }
        assertTrue("missing Wear string files: $missing", missing.isEmpty())

        QUALIFIERS.forEach { qualifier ->
            assertEquals("$qualifier must contain exactly the base string keys", baseKeys, strings(qualifier).keys)
        }
    }

    @Test
    fun `localized Wear strings keep the placeholders and are translated`() {
        val base = strings("values")
        val failures = QUALIFIERS.flatMap { qualifier ->
            if (!File(resourceRoot, "$qualifier/strings.xml").isFile) return@flatMap listOf("$qualifier: missing strings.xml")
            val localized = strings(qualifier)
            base.flatMap { (key, text) ->
                val translated = localized[key] ?: return@flatMap emptyList()
                listOfNotNull(
                    "$qualifier:$key placeholders ${placeholders(translated)}".takeIf { placeholders(text) != placeholders(translated) },
                    "$qualifier:$key is untranslated".takeIf {
                        text == translated && key !in IDENTICAL_KEYS && FORMAT.replace(text, "").any(Char::isLetter)
                    },
                    "$qualifier:$key is empty".takeIf { translated.isBlank() }
                )
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `every language has a Wear folder and no folder exists without a language`() {
        val present = resourceRoot.listFiles { file -> file.isDirectory && File(file, "strings.xml").isFile }.orEmpty().map { it.name }.toSet()
        assertEquals((QUALIFIERS + "values").toSet(), present)
    }

    @Test
    fun `Wear string files carry no byte order mark and no invisible characters`() {
        val failures = (QUALIFIERS + "values").flatMap { qualifier ->
            val bytes = File(resourceRoot, "$qualifier/strings.xml").takeIf(File::isFile)?.readBytes() ?: return@flatMap emptyList()
            buildList {
                if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                    add("$qualifier: UTF-8 byte order mark")
                }
                String(bytes, Charsets.UTF_8).forEach { character ->
                    if (character in FORBIDDEN) add("$qualifier: U+%04X".format(character.code))
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun strings(qualifier: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(resourceRoot, "$qualifier/strings.xml"))
        val stringNodes = document.getElementsByTagName("string")
        val entries = (0 until stringNodes.length).map { index ->
            val element = stringNodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
        check(entries.size == entries.map { it.first }.toSet().size) { "Duplicate string names in $qualifier/strings.xml" }
        return entries.toMap()
    }

    private fun placeholders(text: String): List<String> = FORMAT.findAll(text).map { it.value }.toList().sorted()

    private val resourceRoot: File
        get() = sequenceOf(File("src/main/res"), File("wear/src/main/res"))
            .firstOrNull(File::isDirectory)
            ?: error("Unable to locate Wear string resources from ${File(".").absolutePath}")

    private companion object {
        /** The phone app's languages as resource folders: Indonesian is values-in, Simplified Chinese values-zh-rCN. */
        val QUALIFIERS = listOf(
            "en", "de", "fr", "es", "ar", "hi", "in", "ja", "ko", "my", "pt", "ru", "th", "tr", "vi", "zh-rCN"
        ).map { "values-$it" }
        val IDENTICAL_KEYS = setOf("app_name", "complication_label")
        val FORMAT = Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")
        val FORBIDDEN = setOf(
            '﻿', '​', '⁠', '­', '‎', '‏', '؜', '‪', '‫', '‬', '‭', '‮',
            '⁦', '⁧', '⁨', '⁩', '�'
        )
    }
}
