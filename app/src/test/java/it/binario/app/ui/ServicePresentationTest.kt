package it.binario.app.ui

import it.binario.app.TestStringCatalogue
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.ui.common.JourneyTimes
import it.binario.app.ui.common.ServiceGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ServicePresentationTest {
    private fun service(number: String, category: TrainCategory, departure: String, arrival: String?) = TrainService(
        "id-$number", number, RailOperator.OTHER, category, "Roma Termini", "Torino Porta Nuova",
        departure, departure, null, 0, ServiceStatus.ON_TIME,
        listOfNotNull(
            TrainStop("Roma Termini", departure, departure, null, 0, false),
            arrival?.let { TrainStop("Torino Porta Nuova", it, it, null, 0, false) }
        ),
        operationalStatusVerified = false
    )

    @Test
    fun `duration runs from the scheduled departure to the last stop and crosses midnight`() {
        assertEquals(265, JourneyTimes.durationMinutes(service("9558", TrainCategory.FRECCE, "17:40", "22:05")))
        assertEquals(270, JourneyTimes.durationMinutes(service("2018", TrainCategory.REGIONALE_VELOCE, "19:50", "00:20")))
        assertEquals("22:05", JourneyTimes.arrival(service("9558", TrainCategory.FRECCE, "17:40", "22:05")))
        assertNull(JourneyTimes.durationMinutes(service("1", TrainCategory.REGIONALE, "17:40", null)))
        assertNull(JourneyTimes.durationMinutes(service("2", TrainCategory.REGIONALE, "17:40", "17:40")))
        assertNull(JourneyTimes.durationMinutes(service("3", TrainCategory.REGIONALE, "--", "18:00")))
    }

    /** [JourneyTimes.durationLabel] with the duration_* templates of [locale], formatted as Android would. */
    private fun duration(minutes: Int, locale: String?, javaLocale: Locale): String {
        val strings = TestStringCatalogue.of(locale)
        return JourneyTimes.durationLabel(
            minutes,
            hoursMinutes = { hours, rest -> strings.getValue("duration_hours_minutes").format(javaLocale, hours, rest) },
            hoursOnly = { hours -> strings.getValue("duration_hours").format(javaLocale, hours) },
            minutesOnly = { rest -> strings.getValue("duration_minutes").format(javaLocale, rest) }
        )
    }

    @Test
    fun `duration labels keep the render in Italian and follow each language elsewhere`() {
        assertEquals("4h 25min", duration(265, null, Locale.ITALIAN))
        assertEquals("45min", duration(45, null, Locale.ITALIAN))
        assertEquals("2h", duration(120, null, Locale.ITALIAN))
        assertEquals("4h 25m", duration(265, "en", Locale.ENGLISH))
        assertEquals("4 ч 25 мин", duration(265, "ru", Locale.forLanguageTag("ru")))
        assertEquals("4時間25分", duration(265, "ja", Locale.JAPANESE))
        assertEquals("4시간 25분", duration(265, "ko", Locale.KOREAN))
    }

    @Test
    fun `category chips appear only for groups present in at least two groups and Italo is not a Freccia`() {
        val frecce = service("9558", TrainCategory.FRECCE, "17:40", "22:05")
        val italo = service("9920", TrainCategory.AV_ITALO, "17:50", "22:10")
        val regional = service("2018", TrainCategory.REGIONALE_VELOCE, "19:50", "00:20")
        assertTrue(ServiceGroup.chipsFor(listOf(frecce, frecce)).isEmpty())
        assertEquals(listOf(ServiceGroup.REGIONAL, ServiceGroup.FRECCE, ServiceGroup.ITALO), ServiceGroup.chipsFor(listOf(italo, frecce, regional)))
        assertEquals(listOf(italo), JourneyTimes.filtered(listOf(frecce, italo, regional), ServiceGroup.ITALO))
        assertEquals(listOf(frecce, italo), JourneyTimes.filtered(listOf(frecce, italo), null))
        assertEquals(ServiceGroup.REGIONAL, ServiceGroup.of(TrainCategory.TRENORD_REGIO))
    }
}
