package it.binario.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Guards production translations against missing keys, format drift, and locale-config drift. */
class AndroidStringResourceParityTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val resourceRoot = File(repositoryRoot, "app/src/main/res")
    private val supportedLocales = listOf("it", "en", "de", "fr", "es")

    @Test
    fun `localized catalogs have exact default key plural attribute and placeholder parity`() {
        val default = catalog(File(resourceRoot, "values/strings.xml"))
        supportedLocales.drop(1).forEach { locale ->
            val localized = catalog(File(resourceRoot, "values-$locale/strings.xml"))
            assertEquals("$locale string keys must match Italian default", default.strings.keys, localized.strings.keys)
            assertEquals("$locale plural keys must match Italian default", default.plurals.keys, localized.plurals.keys)
            default.strings.forEach { (key, base) ->
                val translated = localized.strings.getValue(key)
                assertEquals("$locale:$key attributes", base.attributes, translated.attributes)
                assertEquals("$locale:$key formatting placeholders", placeholders(base.text), placeholders(translated.text))
                assertTrue("$locale:$key is untranslated", base.text != translated.text || allowedIdenticalString(key, base.text))
            }
            default.plurals.forEach { (key, base) ->
                val translated = localized.plurals.getValue(key)
                assertEquals("$locale:$key plural quantities", quantitiesByLocale.getValue(locale), translated.items.keys)
                assertEquals("$locale:$key plural attributes", base.attributes, translated.attributes)
                translated.items.forEach { (quantity, localizedValue) ->
                    val baseValue = base.items[quantity] ?: base.items.getValue("other")
                    assertEquals("$locale:$key/$quantity item attributes", baseValue.attributes, localizedValue.attributes)
                    assertEquals("$locale:$key/$quantity formatting placeholders", placeholders(baseValue.text), placeholders(localizedValue.text))
                    assertTrue("$locale:$key/$quantity is untranslated", baseValue.text != localizedValue.text || allowedIdenticalPlural(key, baseValue.text))
                }
            }
        }
    }

    @Test
    fun `locale config exposes exactly Italian English German French and Spanish`() {
        val config = parse(File(resourceRoot, "xml/locale_config.xml"))
        val locales = elements(config.documentElement, "locale").map { it.getAttributeNS(ANDROID_NS, "name") }
        assertEquals(supportedLocales, locales)
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

    private fun allowedIdenticalString(key: String, text: String): Boolean = key in IDENTICAL_STRING_KEYS ||
        text.isBlank() || text.all { !it.isLetter() }

    private fun allowedIdenticalPlural(key: String, text: String): Boolean = key in IDENTICAL_PLURAL_KEYS ||
        text.isBlank() || text.all { !it.isLetter() }

    private data class Catalog(val strings: Map<String, Value>, val plurals: Map<String, Plural>)
    private data class Value(val attributes: Map<String, String>, val text: String)
    private data class Plural(val attributes: Map<String, String>, val items: Map<String, Value>)

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val FORMAT = Regex("%(?:\\d+\\$)?(?:[-#+ 0,(<]*\\d*(?:\\.\\d+)?[tT])?[a-zA-Z%]")
        val IDENTICAL_STRING_KEYS = setOf(
            "app_name", "app_brand", "nav_agenda", "screen_title_agenda", "planned_journey_checklist",
            "integration_apple_watch_title", "widget_previous_symbol", "widget_next_symbol", "widget_refresh_symbol",
            "delay_minutes", "planned_journey_route", "service_operator_category", "service_operator_category_number",
            "service_number_route", "service_data_summary", "station_board_entry_accessibility",
            "accessibility_optional_message", "accessibility_title_detail", "provider_updated_at", "distance_kilometers",
            "board_route_direction", "planner_round_trip_results", "widget_accessibility_target", "share_operator_category",
            "share_route", "planned_journey_checklist_item", "mobility_changes_max", "route_change_via",
            "share_category_frecce", "share_category_intercity", "share_stop_delay", "alert_repeat_weekdays",
            "commuter_alert_lead", "integration_wear_title",
            "expected_time_short", "theme_system_label", "train_category_intercity", "train_category_frecce",
            "unconfigured_route_display", "quality_band_score", "widget_status_on_time", "widget_status_delayed", "widget_status_demo",
            "widget_quality_score", "widget_confidence_open_data", "widget_confidence_cached", "widget_confidence_demonstration",
            "widget_warning_demonstration"
        )
        val IDENTICAL_PLURAL_KEYS = setOf("minutes_short", "gps_alarm_radius_km")
        val quantitiesByLocale = mapOf(
            "en" to setOf("one", "other"),
            "de" to setOf("one", "other"),
            "fr" to setOf("one", "many", "other"),
            "es" to setOf("one", "many", "other")
        )
    }
}
