package it.binario.app.widget

import it.binario.app.ServiceMonitor
import it.binario.app.TestStringCatalogue
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.reliability.ReliabilityFixtures
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «83% in orario» in the route and «I miei treni» widgets: matching, texts and the column switch. */
class WidgetReliabilityTest {
    private val stats = ReliabilityFixtures.stats(ReliabilityFixtures.FR_9558, ReliabilityFixtures.REG_2153_LOMBARDIA, ReliabilityFixtures.REG_2153_SICILIA)

    private val italian = WidgetReliabilityStrings(
        locale = Locale.ITALIAN,
        mark = TestStringCatalogue.of(null).getValue("widget_reliability_mark"),
        spoken = { onTime, samples -> "In orario $onTime volte su $samples (statistiche non ufficiali)" }
    )

    private fun widgetService(number: String, category: TrainCategory = TrainCategory.FRECCE, operator: RailOperator = RailOperator.TRENITALIA_AV) = WidgetService(
        number = number,
        operator = operator,
        category = category,
        destination = "Milano Centrale",
        expectedDeparture = "08:00",
        status = ServiceStatus.ON_TIME,
        delayMinutes = 0,
        platform = "7",
        operationalStatusVerified = true
    )

    @Test fun `the mark is the rounded on-time share, spoken with its counts`() {
        val text = WidgetReliabilityText.of(ReliabilityMark(25, 30, 15, 17, 30), italian)
        assertEquals(WidgetReliabilityText("83% in orario", "In orario 25 volte su 30 (statistiche non ufficiali)"), text)
        assertNull(WidgetReliabilityText.of(null, italian))
        val english = WidgetReliabilityStrings(Locale.ENGLISH, TestStringCatalogue.of("en").getValue("widget_reliability_mark")) { _, _ -> "" }
        assertEquals("67% on time", WidgetReliabilityText.of(ReliabilityMark(20, 30, 5, 20, 30), english)?.shown)
    }

    @Test fun `a route widget row is measured at the destination of the route, a replacement coach never`() {
        assertEquals(ReliabilityMark(22, 30, 5, 22, 30), ReliabilityMarks.widgetRow(stats, widgetService("9558"), "S08409", "S01700"))
        val coach = widgetService("9558").copy(typeGroup = TrainTypeGroup.REPLACEMENT_BUS)
        assertNull(ReliabilityMarks.widgetRow(stats, coach, "S08409", "S01700"))
        assertNull(ReliabilityMarks.widgetRow(stats, widgetService("9558"), "S08409", null))
    }

    @Test fun `a favourite row waits for the catalogue when it was saved with an origin`() {
        val codes = mapOf("Milano Centrale" to "S01700", "Palermo Centrale" to "S12001")
        assertNull(ReliabilityMarks.favouriteRow(stats, "2153", "Milano Centrale", codes::get, catalogueLoaded = false))
        assertEquals(26, ReliabilityMarks.favouriteRow(stats, "2153", "Milano Centrale", codes::get, catalogueLoaded = true)?.onTime)
        assertEquals(21, ReliabilityMarks.favouriteRow(stats, "2153", "Palermo Centrale", codes::get, catalogueLoaded = true)?.onTime)
        // Saved by an earlier version without an origin: the only train with the number.
        assertEquals(20, ReliabilityMarks.favouriteRow(stats, "9558", "", codes::get, catalogueLoaded = false)?.onTime)
        // An origin the catalogue does not know: the number alone, and none when it is ambiguous.
        assertEquals(20, ReliabilityMarks.favouriteRow(stats, "9558", "Roma Tiburtina", codes::get, catalogueLoaded = true)?.onTime)
        assertNull(ReliabilityMarks.favouriteRow(stats, "2153", "Pavia", codes::get, catalogueLoaded = true))
    }

    @Test fun `the reliability column is on by default, kept by the codec and read as on from older settings`() {
        assertTrue(RouteWidgetSettings().showReliability)
        assertTrue(RouteWidgetSettings.LEGACY.showReliability)
        assertTrue(TrainsWidgetSettings().showReliability)
        val route = RouteWidgetSettings(showReliability = false)
        assertEquals(route, WidgetSettingsCodec.decodeRoute(WidgetSettingsCodec.encodeRoute(route)))
        val trains = TrainsWidgetSettings(displayName = "Pendolare", showReliability = false)
        assertEquals(trains, WidgetSettingsCodec.decodeTrains(WidgetSettingsCodec.encodeTrains(trains), "Pendolare"))
        // Settings saved before the column existed have no "rl" key.
        assertTrue(WidgetSettingsCodec.decodeRoute("v=1;sp=0;sn=1").showReliability)
        assertTrue(WidgetSettingsCodec.decodeTrains("v=1;sp=1", "").showReliability)
    }

    @Test fun `the trains widget closes the detail line and the spoken label with the mark, unless the column is off`() {
        val strings = TrainsWidgetStrings(
            locale = Locale.ITALIAN, trainNumber = "Treno %1\$s", categoryNumber = "%1\$s %2\$s", nextStation = "Prossima: %1\$s %2\$s",
            lastDetection = "Rilevato a %1\$s %2\$s", noDetection = "Nessun rilevamento ancora", departureFrom = "Partenza %1\$s da %2\$s",
            waiting = "In attesa del primo aggiornamento", staleData = "Ultimo dato %1\$s", onTime = "OK", late = "+%1\$d", early = "−%1\$d",
            cancelled = "SOPP.", variation = "VAR.", scheduled = "PROGRAM.", platformShort = "BIN %1\$s", platform = "Binario %1\$s",
            platformUnavailable = "Binario non disponibile", delayAccessibility = { "In ritardo di $it minuti" }, earlyAccessibility = { "In anticipo di $it minuti" },
            onTimeAccessibility = "In orario", cancelledAccessibility = "Treno soppresso", variationAccessibility = "Variazione",
            scheduledAccessibility = "Stato operativo non verificato", categoryLabels = TrainCategory.entries.associateWith { if (it == TrainCategory.REGIONALE) "Regionale" else it.name }
        )
        val status = FavouriteTrainStatus(
            number = "2153", retrievedAt = 1_000L, category = TrainCategory.REGIONALE, origin = "Milano Centrale", destination = "Pavia",
            scheduledDeparture = "07:42", nextStation = "Rogoredo", nextStationTime = "07:50", operationalVerified = true, delayMinutes = 0
        )
        val mark = WidgetReliabilityText("93% in orario", "In orario 26 volte su 28 (statistiche non ufficiali)")
        val monitor = ServiceMonitor("2153", "", origin = "Milano Centrale")
        val shown = TrainsWidgetPresenter.row(monitor, status, TrainsWidgetSettings(), 2_000L, strings, mark)
        assertEquals("Prossima: Rogoredo 07:50 · 93% in orario", shown.detail)
        assertTrue(shown.accessibility.endsWith("In orario 26 volte su 28 (statistiche non ufficiali)"))
        val hidden = TrainsWidgetPresenter.row(monitor, status, TrainsWidgetSettings(showReliability = false), 2_000L, strings, mark)
        assertEquals("Prossima: Rogoredo 07:50", hidden.detail)
        assertFalse(hidden.accessibility.contains("statistiche"))
        val waiting = TrainsWidgetPresenter.row(monitor, null, TrainsWidgetSettings(), 2_000L, strings, mark)
        assertEquals("In attesa del primo aggiornamento · 93% in orario", waiting.detail)
    }
}
