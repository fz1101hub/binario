package it.binario.app

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainShareFormatterTest {
    @Test
    fun `shared text includes service stops and non live status`() {
        val service = TrainService(
            id = "test", number = "4132", operator = RailOperator.TRENITALIA_REGIONALE,
            trainCategory = TrainCategory.REGIONALE, origin = "Roma Termini", destination = "Firenze S. M. Novella",
            scheduledDeparture = "09:00", expectedDeparture = "09:05", platform = "6", delayMinutes = 5,
            status = ServiceStatus.DELAYED,
            stops = listOf(TrainStop("Roma Tiburtina", "09:10", "09:15", "2", 5, false))
        )
        val content = TrainShareFormatter.create(service, "Catalogo locale", "Dato dimostrativo", null, 0L, true)

        assertTrue(content.title.contains("4132"))
        assertTrue(content.text.contains("Roma Tiburtina"))
        assertTrue(content.text.contains("non live"))
    }
}
