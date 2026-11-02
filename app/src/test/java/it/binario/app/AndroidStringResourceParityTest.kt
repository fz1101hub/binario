package it.binario.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards production translations against missing keys, format drift, wrong plural quantities, invisible characters
 * and locale-config drift, for every language of docs/l10n/LOCALES.md.
 */
class AndroidStringResourceParityTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val resourceRoot = File(repositoryRoot, "app/src/main/res")

    @Test
    fun `localized catalogs have exact default key plural attribute and placeholder parity`() {
        val default = catalog(File(resourceRoot, "values/strings.xml"))
        val failures = mutableListOf<String>()
        AppLocales.translated.forEach { locale ->
            val file = File(resourceRoot, "${AppLocales.folder(locale)}/strings.xml")
            if (!file.isFile) {
                failures += "$locale: missing ${AppLocales.folder(locale)}/strings.xml"
                return@forEach
            }
            val localized = catalog(file)
            if (default.strings.keys != localized.strings.keys) {
                failures += "$locale string keys: missing ${default.strings.keys - localized.strings.keys}, extra ${localized.strings.keys - default.strings.keys}"
            }
            if (default.plurals.keys != localized.plurals.keys) {
                failures += "$locale plural keys: missing ${default.plurals.keys - localized.plurals.keys}, extra ${localized.plurals.keys - default.plurals.keys}"
            }
            default.strings.forEach { (key, base) ->
                val translated = localized.strings[key] ?: return@forEach
                if (base.attributes != translated.attributes) failures += "$locale:$key attributes ${translated.attributes}"
                if (signature(base.text) != signature(translated.text)) {
                    failures += "$locale:$key formatting placeholders ${placeholders(translated.text)}, expected ${placeholders(base.text)}"
                }
                if (base.text == translated.text && !allowedIdenticalString(locale, key, base.text)) failures += "$locale:$key is untranslated"
                if (translated.text.isBlank() && base.text.isNotBlank()) failures += "$locale:$key is empty"
            }
            default.plurals.forEach { (key, base) ->
                val translated = localized.plurals[key] ?: return@forEach
                val expectedQuantities = QUANTITIES_BY_LOCALE.getValue(locale)
                if (expectedQuantities != translated.items.keys) {
                    failures += "$locale:$key plural quantities ${translated.items.keys}, CLDR expects $expectedQuantities"
                }
                if (base.attributes != translated.attributes) failures += "$locale:$key plural attributes ${translated.attributes}"
                translated.items.forEach { (quantity, localizedValue) ->
                    val baseValue = base.items[quantity] ?: base.items.getValue("other")
                    if (baseValue.attributes != localizedValue.attributes) failures += "$locale:$key/$quantity item attributes"
                    if (signature(baseValue.text) != signature(localizedValue.text)) {
                        failures += "$locale:$key/$quantity formatting placeholders ${placeholders(localizedValue.text)}, expected ${placeholders(baseValue.text)}"
                    }
                    if (baseValue.text == localizedValue.text && !allowedIdenticalPlural(key, baseValue.text)) {
                        failures += "$locale:$key/$quantity is untranslated"
                    }
                }
            }
        }
        assertTrue(failures.take(MAX_REPORTED).joinToString("\n", "${failures.size} translation problems:\n"), failures.isEmpty())
    }

    @Test
    fun `the Italian default declares the plural quantities lint expects for Italian`() {
        val default = catalog(File(resourceRoot, "values/strings.xml"))
        default.plurals.forEach { (key, plural) -> assertEquals("it:$key", QUANTITIES_BY_LOCALE.getValue("it"), plural.items.keys) }
    }

    @Test
    fun `locale config lists every language of the app in order`() {
        val config = parse(File(resourceRoot, "xml/locale_config.xml"))
        val locales = elements(config.documentElement, "locale").map { it.getAttributeNS(ANDROID_NS, "name") }
        // Indonesian is declared with its legacy code "in", which Android and lint use for values-in.
        assertEquals(AppLocales.tags, locales.map { if (it == "in") "id" else it })
        assertEquals(AppLocales.tags.toSet(), QUANTITIES_BY_LOCALE.keys)
    }

    @Test
    fun `every language has its resource folder and no folder exists without a language`() {
        val expected = AppLocales.tags.map(AppLocales::folder).toSet()
        val present = resourceRoot.listFiles { file -> file.isDirectory && File(file, "strings.xml").isFile }.orEmpty().map { it.name }.toSet()
        assertEquals("values folders with strings.xml (Indonesian is values-in, never values-id)", expected, present)
    }

    @Test
    fun `string files carry no byte order mark and no invisible characters`() {
        val failures = AppLocales.tags.flatMap { locale ->
            val file = File(resourceRoot, "${AppLocales.folder(locale)}/strings.xml")
            invisibleCharacterProblems(file.takeIf(File::isFile)?.readBytes() ?: return@flatMap emptyList(), locale)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun catalog(file: File): Catalog {
        val root = parse(file).documentElement
        val strings = elements(root, "string").associate { element ->
            element.getAttribute("name") to Value(attributes(element, setOf("name")), element.textContent)
        }
        val plurals = elements(root, "plurals").associate { element ->
            element.getAttribute("name") to Plural(
                attributes(element, setOf("name")),
                elements(element, "item").associate { item ->
                    item.getAttribute("quantity") to Value(attributes(item, setOf("quantity")), item.textContent)
                }
            )
        }
        return Catalog(strings, plurals)
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().parse(file)

    private fun elements(parent: Element, tagName: String): List<Element> = buildList {
        val nodes = parent.childNodes
        for (index in 0 until nodes.length) (nodes.item(index) as? Element)?.takeIf { it.tagName == tagName }?.let(::add)
    }

    private fun attributes(element: Element, excluded: Set<String>): Map<String, String> = buildMap {
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.nodeName !in excluded) put(attribute.nodeName, attribute.nodeValue)
        }
    }

    private fun placeholders(text: String): List<String> = FORMAT.findAll(text)
        .map { match -> match.value }
        .toList()

    /**
     * The placeholders a translation must keep. Positional arguments (`%1$s`) may move in the sentence, as word order
     * requires in Japanese, Hindi or Turkish, so their order does not count; sequential ones (`%d`) must keep it.
     */
    private fun signature(text: String): List<String> = placeholders(text).let { found ->
        if (found.all { it == "%%" || POSITIONAL.matches(it) }) found.sorted() else found
    }

    private fun allowedIdenticalString(locale: String, key: String, text: String): Boolean = key in IDENTICAL_EVERYWHERE ||
        (locale in LATIN_SCRIPT_LOCALES && key in IDENTICAL_IN_LATIN_SCRIPT) || formatOnly(text)

    private fun allowedIdenticalPlural(key: String, text: String): Boolean = key in IDENTICAL_PLURAL_KEYS || formatOnly(text)

    /** Placeholders, digits, punctuation and symbols only ("%1$s → %2$s", "%1$s · %2$s"): nothing to translate. */
    private fun formatOnly(text: String): Boolean = FORMAT.replace(text, "").none(Char::isLetter)

    private data class Catalog(val strings: Map<String, Value>, val plurals: Map<String, Plural>)
    private data class Value(val attributes: Map<String, String>, val text: String)
    private data class Plural(val attributes: Map<String, String>, val items: Map<String, Value>)

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val MAX_REPORTED = 80
        /** A Java format specifier: optional argument index, flags, width and precision, then a conversion ("%1$.1f", "%tH"). */
        private val FORMAT = Regex("%(?:\\d+\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?(?:[tT][a-zA-Z]|[a-zA-Z%])")
        private val POSITIONAL = Regex("%\\d+\\$.*")

        /** Brands, product names, unit symbols and the code-like widget badges: the same text in every language. */
        private val IDENTICAL_EVERYWHERE = setOf(
            "app_name", "app_brand", "integration_apple_watch_title",
            "widget_previous_symbol", "widget_next_symbol", "widget_refresh_symbol",
            "delay_minutes", "share_stop_delay", "distance_kilometers", "quality_band_score",
            "widget_status_on_time", "widget_status_delayed", "widget_status_demo", "widget_quality_score",
            "widget_confidence_open_data", "widget_confidence_cached", "widget_confidence_demonstration",
            "widget_warning_demonstration", "trains_widget_status_early",
            "share_category_frecce", "share_category_intercity", "train_category_intercity", "train_category_frecce",
            "service_group_italo", "service_group_frecce", "journey_leg_freccialink", "journey_leg_italobus"
        )

        /**
         * Words Italian shares with other Latin-script languages ("Agenda", "Checklist", "Metro", "Tram", "Sistema", "Tema",
         * "Widget", "Via"); a non-Latin script never keeps them.
         */
        private val IDENTICAL_IN_LATIN_SCRIPT = setOf(
            "nav_agenda", "screen_title_agenda", "planned_journey_checklist", "planned_journey_checklist_item",
            "mobility_changes_max", "route_change_via", "alert_repeat_weekdays", "commuter_alert_lead", "integration_wear_title",
            "expected_time_short", "theme_system_label", "settings_theme", "settings_section_widgets",
            "journey_leg_metro", "journey_leg_tram", "journey_leg_funicular", "journey_leg_bus_trenitalia"
        )
        private val LATIN_SCRIPT_LOCALES = setOf("en", "de", "fr", "es", "id", "pt", "tr", "vi")
        private val IDENTICAL_PLURAL_KEYS = setOf("minutes_short", "gps_alarm_radius_km")

        /** The quantities Android lint (AGP 8.13, CLDR 42+) requires and accepts per language. */
        val QUANTITIES_BY_LOCALE: Map<String, Set<String>> = buildMap {
            val other = setOf("other")
            val oneOther = setOf("one", "other")
            val oneManyOther = setOf("one", "many", "other")
            put("it", oneManyOther)
            put("en", oneOther)
            put("de", oneOther)
            put("fr", oneManyOther)
            put("es", oneManyOther)
            put("ar", setOf("zero", "one", "two", "few", "many", "other"))
            put("hi", oneOther)
            put("id", other)
            put("ja", other)
            put("ko", other)
            put("my", other)
            put("pt", oneManyOther)
            put("ru", setOf("one", "few", "many", "other"))
            put("th", other)
            put("tr", oneOther)
            put("vi", other)
            put("zh-CN", other)
        }

        /**
         * Characters that must never be typed into a resource file: the byte order mark (lint ByteOrderMark), zero-width
         * spaces and joiners used as break hints, soft hyphens and literal bidirectional controls. Zero-width
         * (non-)joiners stay allowed because Devanagari, Myanmar and Arabic spelling uses them; a bidi mark a string
         * really needs is written as a visible `‏` escape.
         */
        private val FORBIDDEN = mapOf(
            '﻿' to "BYTE ORDER MARK", '​' to "ZERO WIDTH SPACE", '⁠' to "WORD JOINER", '­' to "SOFT HYPHEN",
            '‎' to "LEFT-TO-RIGHT MARK", '‏' to "RIGHT-TO-LEFT MARK", '؜' to "ARABIC LETTER MARK",
            '‪' to "LEFT-TO-RIGHT EMBEDDING", '‫' to "RIGHT-TO-LEFT EMBEDDING", '‬' to "POP DIRECTIONAL FORMATTING",
            '‭' to "LEFT-TO-RIGHT OVERRIDE", '‮' to "RIGHT-TO-LEFT OVERRIDE", '⁦' to "LEFT-TO-RIGHT ISOLATE",
            '⁧' to "RIGHT-TO-LEFT ISOLATE", '⁨' to "FIRST STRONG ISOLATE", '⁩' to "POP DIRECTIONAL ISOLATE",
            '�' to "REPLACEMENT CHARACTER"
        )

        /** Problems of one resource file's raw bytes: a UTF-8 BOM, malformed UTF-8 and the [FORBIDDEN] characters. */
        fun invisibleCharacterProblems(bytes: ByteArray, label: String): List<String> = buildList {
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                add("$label: UTF-8 byte order mark at the start of the file")
            }
            val decoder = Charsets.UTF_8.newDecoder()
            val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse {
                add("$label: not valid UTF-8")
                return@buildList
            }
            text.lineSequence().forEachIndexed { index, line ->
                line.forEach { character ->
                    FORBIDDEN[character]?.let { add("$label line ${index + 1}: U+%04X %s".format(character.code, it)) }
                }
            }
        }
    }
}
