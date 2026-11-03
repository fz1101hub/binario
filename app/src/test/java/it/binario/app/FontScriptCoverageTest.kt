package it.binario.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arabic, Devanagari, Thai, Myanmar, Hangul, kana and Han text (docs/l10n/LOCALES.md) renders because the app and
 * Wear only use the system font families, whose fallback chains cover those scripts. A bundled font usually covers
 * Latin only and would need its own per-script fallback: this test makes adding one a deliberate decision.
 */
class FontScriptCoverageTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `app and Wear bundle no font files and declare no custom font family`() {
        val modules = listOf("app", "wear").map { File(repositoryRoot, "$it/src") }
        val fontFiles = modules.flatMap { module ->
            module.walkTopDown().filter { file ->
                (file.isFile && file.extension.lowercase() in FONT_EXTENSIONS) || (file.isDirectory && file.name.startsWith("font") && file.parentFile?.name == "res")
            }.map { it.relativeTo(repositoryRoot).path }.toList()
        }
        assertTrue("bundled fonts need a fallback for every app script: $fontFiles", fontFiles.isEmpty())

        val customFamilies = modules.flatMap { module ->
            module.walkTopDown().filter { it.isFile && it.extension == "kt" && "/main/" in it.invariantSeparatorsPath }.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    "${file.relativeTo(repositoryRoot).path}:${index + 1}".takeIf { CUSTOM_FONT.containsMatchIn(line) }
                }
            }.toList()
        }
        assertTrue("custom font families need a fallback for every app script: $customFamilies", customFamilies.isEmpty())
    }

    private companion object {
        val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "woff", "woff2")
        val CUSTOM_FONT = Regex("""R\.font\.|\bFont\(\s*(R\.|resId|assetManager|path|file)|Typeface\.createFrom""")
    }
}
