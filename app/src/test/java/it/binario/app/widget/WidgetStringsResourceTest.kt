package it.binario.app.widget

import it.binario.app.data.DataConfidence
import it.binario.app.data.DataQualityBand
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.SnapshotWarning
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * The hand-written fixtures prove the model follows whatever catalogue it is given; this test
 * builds the catalogue from the shipped strings.xml files, so the resource values themselves are covered.
 */
class WidgetStringsResourceTest {
    private val state = WidgetState(
        route = SavedRoute(
            id = "roma-torino",
            name = "Roma Torino",
            outwardOrigin = Station("roma", "Roma Termini", "Roma"),
            outwardDestination = Station("torino", "Torino Porta Nuova", "Torino")
        ),
        snapshotDirection = Direction.OUTBOUND,
        direction = Direction.OUTBOUND,
        services = emptyList(),
        freshness = Freshness.AGING,
        confidence = DataConfidence.INFRASTRUCTURE_PRIMARY,
        providerName = "RFI",
        qualityScore = 84,
        qualityBand = DataQualityBand.RELIABLE,
        qualityWarnings = setOf(SnapshotWarning.SOURCE_CONFLICT),
        retrievedAt = 1L,
        rowOffset = 0
    )
    private val delayed = WidgetService("123", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Roma", "12:04", ServiceStatus.DELAYED, 4, "7", true)

    @Test fun `the Italian resources render the original meta line and TalkBack texts`() {
        val strings = widgetStrings(locale = null)

        assertEquals("RFI · INFRASTRUTTURA · Q84 · IN AGGIORNAMENTO · CONFLITTO FONTI · PAG 1/1", state.compactMeta(strings, "PAG 1/1"))
        assertEquals(
            "Provider RFI. Dati in aggiornamento. Fonte primaria del gestore dell’infrastruttura. Dato affidabile, punteggio qualità 84 su 100. Avvisi: Fonti in conflitto",
            state.accessibleDataSummary(strings)
        )
        assertEquals("Operatore Trenitalia Regionale. Regionale 123 verso Roma. Partenza prevista 12:04. In ritardo +4 min. Binario 7.", delayed.accessibleLabel(strings))
    }

    @Test fun `the English resources render the same texts in English`() {
        val strings = widgetStrings("en")

        assertEquals("RFI · INFRASTRUCTURE · Q84 · UPDATING · SOURCE CONFLICT · PAGE 1/1", state.compactMeta(strings, "PAGE 1/1"))
        assertEquals(
            "Source RFI. Data updating. Infrastructure manager primary source. Reliable data, quality score out of 100: 84. Warnings: Sources in conflict",
            state.accessibleDataSummary(strings)
        )
        assertEquals("Operator Trenitalia Regionale. Regional 123 to Roma. Expected departure 12:04. Delayed +4 min. Platform 7.", delayed.accessibleLabel(strings))
    }

    /** The catalogue WidgetStrings.from(context) would resolve for the given locale (null = Italian default), key by key. */
    private fun widgetStrings(locale: String?): WidgetStrings {
        val catalogue = catalogue(locale)
        return WidgetStrings(
            locale = locale?.let(Locale::forLanguageTag) ?: Locale.ITALIAN,
            statusOnTime = catalogue.getValue("widget_status_on_time"),
            statusDelayed = catalogue.getValue("widget_status_delayed"),
            statusCancelled = catalogue.getValue("widget_status_cancelled"),
            statusDemo = catalogue.getValue("widget_status_demo"),
            statusScheduled = catalogue.getValue("widget_status_scheduled"),
            qualityScore = catalogue.getValue("widget_quality_score"),
            serviceAccessibility = catalogue.getValue("widget_service_accessibility"),
            delayMinutes = catalogue.getValue("delay_minutes"),
            scheduledUnverified = catalogue.getValue("operational_status_unverified"),
            platform = catalogue.getValue("platform_label"),
            platformUnavailable = catalogue.getValue("platform_unavailable"),
            dataAccessibility = catalogue.getValue("widget_data_accessibility"),
            qualityScoreAccessibility = catalogue.getValue("widget_quality_score_accessibility"),
            warningsAccessibility = catalogue.getValue("widget_warnings_accessibility"),
            confidenceLabels = DataConfidence.entries.associateWith { catalogue.getValue("widget_confidence_${it.key}") },
            freshnessLabels = Freshness.entries.associateWith { catalogue.getValue("widget_freshness_${it.key}") },
            warningLabels = SnapshotWarning.entries.associateWith { catalogue.getValue("widget_warning_${it.key}") },
            statusLabels = ServiceStatus.entries.associateWith { catalogue.getValue("service_status_${it.key}") },
            categoryLabels = TrainCategory.entries.associateWith { catalogue.getValue(categoryKeys.getValue(it)) },
            directionLabels = Direction.entries.associateWith { catalogue.getValue("direction_${it.key}") },
            confidenceAccessibleLabels = DataConfidence.entries.associateWith { catalogue.getValue("data_confidence_${it.key}") },
            freshnessAccessibleLabels = Freshness.entries.associateWith { catalogue.getValue("freshness_${it.key}") },
            qualityBandLabels = DataQualityBand.entries.associateWith { catalogue.getValue("quality_band_${it.key}") },
            warningAccessibleLabels = SnapshotWarning.entries.associateWith { catalogue.getValue("snapshot_warning_${it.key}") }
        )
    }

    private val Enum<*>.key: String get() = name.lowercase()

    private val categoryKeys = mapOf(
        TrainCategory.REGIONALE to "train_category_regionale",
        TrainCategory.REGIONALE_VELOCE to "train_category_regionale_veloce",
        TrainCategory.INTERCITY to "train_category_intercity",
        TrainCategory.FRECCE to "train_category_frecce",
        TrainCategory.AV_ITALO to "train_category_italo_high_speed",
        TrainCategory.TRENORD_REGIO to "train_category_trenord_regional",
        TrainCategory.METROPOLITANO to "train_category_metropolitan",
        TrainCategory.OTHER to "train_category_other"
    )

    private fun catalogue(locale: String?): Map<String, String> {
        val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val file = File(repositoryRoot, "app/src/main/res/values${locale?.let { "-$it" }.orEmpty()}/strings.xml")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val nodes = root.childNodes
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                if (element.tagName == "string") put(element.getAttribute("name"), element.textContent.replace("\\'", "'"))
            }
        }
    }
}
