package it.binario.app.ui

import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.ui.common.ServiceRunLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceRunLabelTest {
    private fun service(status: ServiceStatus, delay: Int, verified: Boolean = true, unpublished: Boolean = false) = TrainService(
        "id", "2045", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Milano Centrale", "Brescia",
        "17:40", "17:40", "4", delay, status, emptyList(), operationalStatusVerified = verified, delayMinutesUnpublished = unpublished
    )

    @Test
    fun `a late status whose expected time equals the timetable reads late without a number, never on time or +0 min`() {
        val late = service(ServiceStatus.DELAYED, 0)
        assertEquals(ServiceRunLabel.Late, ServiceRunLabel.of(late))
        assertEquals(ServiceStatus.DELAYED, ServiceRunLabel.spokenStatus(late))
        // A negative delay (an expected time before the timetable) is no number to show either.
        assertEquals(ServiceRunLabel.Late, ServiceRunLabel.of(service(ServiceStatus.DELAYED, -2)))
    }

    @Test
    fun `minutes of delay are shown and spoken as late even when the status field says on time`() {
        val late = service(ServiceStatus.ON_TIME, 6)
        assertEquals(ServiceRunLabel.LateBy(6), ServiceRunLabel.of(late))
        assertEquals(ServiceStatus.DELAYED, ServiceRunLabel.spokenStatus(late))
        assertEquals(ServiceRunLabel.LateBy(6), ServiceRunLabel.of(service(ServiceStatus.DELAYED, 6)))
    }

    @Test
    fun `on time only when nothing says late`() {
        assertEquals(ServiceRunLabel.OnTime, ServiceRunLabel.of(service(ServiceStatus.ON_TIME, 0)))
        assertEquals(ServiceRunLabel.OnTime, ServiceRunLabel.of(service(ServiceStatus.ON_TIME, -1)))
        assertEquals(ServiceStatus.ON_TIME, ServiceRunLabel.spokenStatus(service(ServiceStatus.ON_TIME, 0)))
    }

    @Test
    fun `cancelled wins over minutes, and an unverified run is the timetable or late without minutes`() {
        assertEquals(ServiceRunLabel.Cancelled, ServiceRunLabel.of(service(ServiceStatus.CANCELLED, 12)))
        assertEquals(ServiceStatus.CANCELLED, ServiceRunLabel.spokenStatus(service(ServiceStatus.CANCELLED, 12)))
        assertEquals(ServiceRunLabel.Scheduled, ServiceRunLabel.of(service(ServiceStatus.DELAYED, 5, verified = false)))
        assertEquals(
            ServiceRunLabel.LateMinutesUnpublished,
            ServiceRunLabel.of(service(ServiceStatus.ON_TIME, 0, verified = false, unpublished = true))
        )
    }
}
