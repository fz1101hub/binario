package it.binario.app

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.Station
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommuterAlertStatusTest {
    @Test fun `the route stations are found in the catalogue when the device does not remember them`() {
        val milano = Station("milano-centrale", "Milano Centrale", "Milano")
        val pavia = Station("pavia", "Pavia", "Pavia")
        val remembered = Station("custom:pavia", "Pavia", "Pavia")
        var catalogueReads = 0
        val catalogue = { catalogueReads++; listOf(milano, pavia) }

        // After a backup import only stations outside the catalogue are remembered: the catalogue resolves the rest.
        assertEquals(milano to pavia, CommuterAlertStatusRules.routeEndpoints("Milano Centrale → Pavia", emptyList(), catalogue))
        assertEquals(milano to remembered, CommuterAlertStatusRules.routeEndpoints("milano centrale → Pavia", listOf(remembered), catalogue))
        // Stations the device remembers win and the catalogue is not read.
        val reads = catalogueReads
        assertEquals(milano to remembered, CommuterAlertStatusRules.routeEndpoints("Milano Centrale → Pavia", listOf(remembered, milano), catalogue))
        assertEquals(reads, catalogueReads)
        assertNull(CommuterAlertStatusRules.routeEndpoints("Milano Centrale → Atlantide", emptyList(), catalogue))
        assertNull(CommuterAlertStatusRules.routeEndpoints("Milano Centrale", emptyList(), catalogue))
    }

    private val strings = CommuterAlertStatusStrings(
        unavailable = "stato non disponibile",
        onTime = "in orario",
        cancelled = "treno soppresso",
        delay = { minutes -> if (minutes == 1) "ritardo di 1 minuto" else "ritardo di $minutes minuti" },
        withPlatform = { status, platform -> "$status, binario $platform" }
    )

    @Test fun `the status sentence reads delay, platform, cancellation or unavailability`() {
        assertEquals("ritardo di 5 minuti, binario 3", CommuterAlertStatusRules.format(CommuterLiveStatus(5, "3", false), strings))
        assertEquals("ritardo di 1 minuto", CommuterAlertStatusRules.format(CommuterLiveStatus(1, null, false), strings))
        assertEquals("in orario, binario 12", CommuterAlertStatusRules.format(CommuterLiveStatus(0, "12", false), strings))
        assertEquals("treno soppresso", CommuterAlertStatusRules.format(CommuterLiveStatus(0, "3", true), strings))
        assertEquals("stato non disponibile", CommuterAlertStatusRules.format(null, strings))
    }

    @Test fun `the reminder's train is the one scheduled at its time at its station`() {
        val earlier = service("2151", listOf(stop("Milano Centrale", "07:40", 0, "8"), stop("Pavia", "08:05", 2, "2")))
        val wanted = service("2153", listOf(stop("MILANO CENTRALE", "08:10", 4, "7"), stop("PAVIA", "08:35", 6, "3")))

        assertEquals(wanted, CommuterAlertStatusRules.pick(listOf(earlier, wanted), "Pavia", "08:35"))
        assertEquals(wanted, CommuterAlertStatusRules.pick(listOf(earlier, wanted), "Milano Centrale", "8:10"))
        assertNull(CommuterAlertStatusRules.pick(listOf(earlier, wanted), "Pavia", "09:35"))
        assertNull(CommuterAlertStatusRules.pick(listOf(earlier, wanted), "Pavia", "non è un orario"))
    }

    @Test fun `the status is the one at the reminder's station, and only with verified data`() {
        val run = service("2153", listOf(stop("MILANO CENTRALE", "08:10", 4, "7"), stop("PAVIA", "08:35", 6, "3")))

        assertEquals(CommuterLiveStatus(6, "3", false), CommuterAlertStatusRules.statusOf(run, "Pavia"))
        assertEquals(CommuterLiveStatus(4, "7", false), CommuterAlertStatusRules.statusOf(run, "Milano Centrale"))
        assertEquals(CommuterLiveStatus(0, null, true), CommuterAlertStatusRules.statusOf(run.copy(status = ServiceStatus.CANCELLED), "Pavia"))
        assertNull(CommuterAlertStatusRules.statusOf(run.copy(operationalStatusVerified = false), "Pavia"))
    }

    @Test fun `the route label splits into its two stations`() {
        assertEquals("Milano Centrale" to "Pavia", CommuterAlertStatusRules.routeStations("Milano Centrale → Pavia"))
        assertNull(CommuterAlertStatusRules.routeStations("Milano Centrale"))
        assertNull(CommuterAlertStatusRules.routeStations(" → Pavia"))
    }

    private fun stop(station: String, scheduled: String, delay: Int, platform: String?) =
        TrainStop(station, scheduled, scheduled, platform, delay, completed = false, operationalStatusVerified = true)

    private fun service(number: String, stops: List<TrainStop>) = TrainService(
        id = "viaggiatreno:S01700:$number:1",
        number = number,
        operator = RailOperator.TRENITALIA_REGIONALE,
        trainCategory = TrainCategory.REGIONALE,
        origin = stops.first().station,
        destination = stops.last().station,
        scheduledDeparture = stops.first().scheduled,
        expectedDeparture = stops.first().scheduled,
        platform = stops.first().platform,
        delayMinutes = stops.last().delayMinutes,
        status = ServiceStatus.DELAYED,
        stops = stops
    )
}
