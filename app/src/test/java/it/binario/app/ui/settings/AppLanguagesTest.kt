package it.binario.app.ui.settings

import it.binario.app.AppLocales
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguagesTest {
    @Test
    fun `the picker offers exactly the languages of locale_config once each`() {
        val tags = AppLanguages.all.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
        assertEquals(AppLocales.tags.toSet(), tags.toSet())
    }

    @Test
    fun `every language is named in its own language`() {
        val names = AppLanguages.all.associate { it.tag to it.nativeName }
        assertEquals(
            mapOf(
                "it" to "Italiano", "en" to "English", "de" to "Deutsch", "fr" to "Français", "es" to "Español",
                "ar" to "العربية", "hi" to "हिन्दी", "id" to "Bahasa Indonesia", "ja" to "日本語", "ko" to "한국어",
                "my" to "မြန်မာ", "pt" to "Português", "ru" to "Русский", "th" to "ไทย", "tr" to "Türkçe",
                "vi" to "Tiếng Việt", "zh-CN" to "中文 (简体)"
            ),
            names
        )
        AppLanguages.all.forEach { language ->
            assertTrue(language.tag, language.nativeName.none { it in '​'..'‏' || it == '﻿' || it in '⁦'..'⁩' })
        }
    }

    @Test
    fun `names are grouped by script and Latin names are alphabetical`() {
        val scripts = AppLanguages.all.map { Character.UnicodeScript.of(it.nativeName.first().code) }
        assertEquals(scripts.distinct(), SCRIPT_ORDER.filter { it in scripts })
        assertEquals("one block per script", scripts.distinct().size, scripts.zipWithNext().count { (a, b) -> a != b } + 1)
        val latin = AppLanguages.all.map { it.nativeName }.filter { Character.UnicodeScript.of(it.first().code) == Character.UnicodeScript.LATIN }
        val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }
        assertEquals(latin.sortedWith(collator), latin)
    }

    @Test
    fun `the per-app locale reported by Android selects its picker entry`() {
        assertNull(AppLanguages.match(null))
        assertNull(AppLanguages.match(""))
        assertEquals("it", AppLanguages.match("it")?.tag)
        assertEquals("it", AppLanguages.match("it-IT")?.tag)
        assertEquals("id", AppLanguages.match("id")?.tag)
        assertEquals("id", AppLanguages.match("in")?.tag)
        assertEquals("id", AppLanguages.match("in-ID")?.tag)
        assertEquals("pt", AppLanguages.match("pt-BR")?.tag)
        assertEquals("zh-CN", AppLanguages.match("zh-CN")?.tag)
        assertEquals("zh-CN", AppLanguages.match("zh-Hans-CN")?.tag)
        assertEquals("zh-CN", AppLanguages.match("zh-SG")?.tag)
        assertNull(AppLanguages.match("zh-TW"))
        assertNull(AppLanguages.match("zh-Hant-HK"))
        assertEquals("ar", AppLanguages.match("ar-EG")?.tag)
        assertNull(AppLanguages.match("nl"))
    }

    private companion object {
        val SCRIPT_ORDER = listOf(
            Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.ARABIC,
            Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.THAI, Character.UnicodeScript.MYANMAR,
            Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN
        )
    }
}
