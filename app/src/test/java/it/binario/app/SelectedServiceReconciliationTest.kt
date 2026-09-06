package it.binario.app

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SelectedServiceReconciliationTest {
    @Test
    fun `exact service id wins when snapshot is refreshed`() {
        val selected = service(id = "old", number = "9512")
        val refreshed = service(id = "old", number = "9512", expectedDeparture = "10:07")

        assertSame(refreshed, findRefreshedService(selected, listOf(refreshed)))
    }

    @Test
    fun `stable public identity can reattach when provider regenerates id`() {
        val selected = service(id = "old", number = "9512")
        val refreshed = service(id = "new", number = "9512", expectedDeparture = "10:07")

        assertSame(refreshed, findRefreshedService(selected, listOf(refreshed)))
    }

    @Test
    fun `ambiguous different run is not rebound to new snapshot provenance`() {
        val selected = service(id = "old", number = "9512")
        val differentRun = service(id = "new", number = "9512", scheduledDeparture = "11:00")

        assertNull(findRefreshedService(selected, listOf(differentRun)))
    }

    private fun service(
        id: String,
        number: String,
        scheduledDeparture: String = "10:00",
        expectedDeparture: String = scheduledDeparture
    ) = TrainService(
        id = id,
        number = number,
        operator = RailOperator.TRENITALIA,
        trainCategory = TrainCategory.OTHER,
        origin = "Roma Termini",
        destination = "Milano Centrale",
        scheduledDeparture = scheduledDeparture,
        expectedDeparture = expectedDeparture,
        platform = null,
        delayMinutes = 0,
        status = ServiceStatus.ON_TIME,
        stops = emptyList()
    )
}
