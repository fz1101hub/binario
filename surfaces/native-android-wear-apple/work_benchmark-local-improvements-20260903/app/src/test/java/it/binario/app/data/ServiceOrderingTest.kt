package it.binario.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceOrderingTest {
    @Test
    fun `services are ordered by expected departure independently of operator`() {
        val italo = service("9927", RailOperator.ITALO, TrainCategory.AV_ITALO, "08:00")
        val regionale = service("4512", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "08:30")

        val result = orderServicesForDisplay(listOf(regionale, italo))

        assertEquals(listOf("9927", "4512"), result.map { it.number })
    }

    @Test
    fun `same departure time is ordered deterministically by train number`() {
        val higher = service("9927", RailOperator.ITALO, TrainCategory.AV_ITALO, "08:00")
        val lower = service("4512", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "08:00")

        val result = orderServicesForDisplay(listOf(higher, lower))

        assertEquals(listOf("4512", "9927"), result.map { it.number })
    }

    @Test
    fun `invalid expected times are shown after valid departures`() {
        val valid = service("4512", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "08:00")
        val invalid = service("9927", RailOperator.ITALO, TrainCategory.AV_ITALO, "non valido")

        val result = orderServicesForDisplay(listOf(invalid, valid))

        assertEquals(listOf("4512", "9927"), result.map { it.number })
    }

    private fun service(
        number: String,
        operator: RailOperator,
        category: TrainCategory,
        expectedDeparture: String
    ): TrainService = TrainService(
        id = number,
        number = number,
        operator = operator,
        trainCategory = category,
        origin = "Origine",
        destination = "Destinazione",
        scheduledDeparture = expectedDeparture,
        expectedDeparture = expectedDeparture,
        platform = "1",
        delayMinutes = 0,
        status = ServiceStatus.ON_TIME,
        stops = emptyList()
    )
}
