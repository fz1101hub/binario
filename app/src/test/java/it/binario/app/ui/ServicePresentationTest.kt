package it.binario.app.ui

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

    @Test
    fun `duration labels use hour and minute symbols`() {
        assertEquals("4h 25min", JourneyTimes.durationLabel(265))
        assertEquals("45min", JourneyTimes.durationLabel(45))
        assertEquals("2h", JourneyTimes.durationLabel(120))
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
