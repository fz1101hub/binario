package it.binario.app

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import org.junit.Assert.assertEquals
import org.junit.Test

class PublishedOperatorNamesTest {
    @Test
    fun `published operator names preserve agency identity and deduplicate case insensitively`() {
        val names = publishedOperatorNames(
            listOf(
                service("1", RailOperator.OTHER, "SBB CFF FFS"),
                service("2", RailOperator.OTHER, "sbb cff ffs"),
                service("3", RailOperator.TILO, null),
                service("4", RailOperator.OTHER, "RegioJet"),
                service("5", RailOperator.SNCF, " ")
            )
        )

        assertEquals(listOf("RegioJet", "SBB CFF FFS", "SNCF", "TILO"), names)
    }

    private fun service(
        id: String,
        operator: RailOperator,
        displayName: String?
    ) = TrainService(
        id = id,
        number = id,
        operator = operator,
        trainCategory = TrainCategory.OTHER,
        origin = "A",
        destination = "B",
        scheduledDeparture = "10:00",
        expectedDeparture = "10:00",
        platform = null,
        delayMinutes = 0,
        status = ServiceStatus.ON_TIME,
        stops = emptyList(),
        operatorDisplayName = displayName
    )
}
