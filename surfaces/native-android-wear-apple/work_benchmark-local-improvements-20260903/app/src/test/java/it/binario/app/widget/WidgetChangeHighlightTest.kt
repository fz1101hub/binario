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
    @Test fun `static GTFS delay is not presented as an operational variation`() = assertFalse(WidgetChangeHighlight.shouldHighlight(ServiceStatus.DELAYED, DataConfidence.STATIC_ONLY, Freshness.FRESH))
    @Test fun `cached cancellation is not highlighted as current`() = assertFalse(WidgetChangeHighlight.shouldHighlight(ServiceStatus.CANCELLED, DataConfidence.CACHED, Freshness.STALE))
    @Test fun `cancelled unavailable snapshot is not highlighted`() = assertFalse(WidgetChangeHighlight.shouldHighlight(ServiceStatus.CANCELLED, DataConfidence.OPERATOR_PRIMARY, Freshness.UNAVAILABLE))

    @Test fun `demo status is labelled as demo`() {
        val service = WidgetService("123", RailOperator.TRENITALIA_REGIONALE, "Regionale", "Roma", "12:00", ServiceStatus.DELAYED, 4, null)
        assertTrue(WidgetChangeHighlight.displayStatus(service, DataConfidence.DEMONSTRATION) == "DEMO")
    }

    @Test fun `static status is labelled as scheduled`() {
        val service = WidgetService("123", RailOperator.TRENITALIA_REGIONALE, "Regionale", "Roma", "12:00", ServiceStatus.DELAYED, 4, null)
        assertTrue(WidgetChangeHighlight.displayStatus(service, DataConfidence.STATIC_ONLY) == "PROGRAM.")
    }
}
