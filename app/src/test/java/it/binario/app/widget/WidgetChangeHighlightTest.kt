package it.binario.app.widget

import it.binario.app.data.DataConfidence
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChangeHighlightTest {
    @Test fun `licensed delayed service is highlighted`() = assertTrue(WidgetChangeHighlight.shouldHighlight(ServiceStatus.DELAYED, DataConfidence.LICENSED_AGGREGATE, Freshness.FRESH))
    @Test fun `demo delayed service is not presented as a real variation`() = assertFalse(WidgetChangeHighlight.shouldHighlight(ServiceStatus.DELAYED, DataConfidence.DEMONSTRATION, Freshness.FRESH))
    @Test fun `cancelled unavailable snapshot is not highlighted`() = assertFalse(WidgetChangeHighlight.shouldHighlight(ServiceStatus.CANCELLED, DataConfidence.OPERATOR_PRIMARY, Freshness.UNAVAILABLE))

    @Test fun `demo status is labelled as demo`() {
        val service = WidgetService("123", RailOperator.TRENITALIA_REGIONALE, "Regionale", "Roma", "12:00", ServiceStatus.DELAYED, 4, null)
        assertTrue(WidgetChangeHighlight.displayStatus(service, DataConfidence.DEMONSTRATION) == "DEMO")
    }
}
