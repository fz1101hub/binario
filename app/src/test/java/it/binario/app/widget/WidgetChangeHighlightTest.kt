package it.binario.app.widget

import it.binario.app.data.DataConfidence
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChangeHighlightTest {
    @Test fun `licensed delayed service is highlighted`() =
        assertTrue(WidgetChangeHighlight.shouldHighlight(ServiceStatus.DELAYED, DataConfidence.LICENSED_AGGREGATE, Freshness.FRESH))

    @Test fun `demo delayed service is not presented as a real variation`() =
        assertFalse(WidgetChangeHighlight.shouldHighlight(ServiceStatus.DELAYED, DataConfidence.DEMONSTRATION, Freshness.FRESH))

    @Test fun `static GTFS delay is not highlighted when operational state is unverified`() =
        assertFalse(WidgetChangeHighlight.shouldHighlight(ServiceStatus.DELAYED, DataConfidence.STATIC_ONLY, Freshness.FRESH, false))

    @Test fun `reconciled static GTFS delay is highlighted when operational state is verified`() =
        assertTrue(WidgetChangeHighlight.shouldHighlight(ServiceStatus.DELAYED, DataConfidence.STATIC_ONLY, Freshness.FRESH, true))

    @Test fun `cached cancellation is not highlighted as current`() =
        assertFalse(WidgetChangeHighlight.shouldHighlight(ServiceStatus.CANCELLED, DataConfidence.CACHED, Freshness.STALE, true))

    @Test fun `cancelled unavailable snapshot is not highlighted`() =
        assertFalse(WidgetChangeHighlight.shouldHighlight(ServiceStatus.CANCELLED, DataConfidence.OPERATOR_PRIMARY, Freshness.UNAVAILABLE, true))

    @Test fun `demo status is labelled as demo`() {
        val service = WidgetService("123", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Roma", "12:00", ServiceStatus.DELAYED, 4, null, false)
        assertTrue(WidgetChangeHighlight.displayStatus(service, DataConfidence.DEMONSTRATION, italianWidgetStrings()) == "DEMO")
    }

    @Test fun `static unverified status is labelled as scheduled`() {
        val service = WidgetService("123", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Roma", "12:00", ServiceStatus.ON_TIME, 0, null, false)
        assertTrue(WidgetChangeHighlight.displayStatus(service, DataConfidence.STATIC_ONLY, italianWidgetStrings()) == "PROGRAM.")
        assertTrue(WidgetChangeHighlight.displayStatus(service, DataConfidence.STATIC_ONLY, englishWidgetStrings()) == "SCHED.")
    }

    @Test fun `static reconciled status shows live result`() {
        val service = WidgetService("123", RailOperator.TRENITALIA_REGIONALE, TrainCategory.REGIONALE, "Roma", "12:04", ServiceStatus.DELAYED, 4, "7", true)
        assertTrue(WidgetChangeHighlight.displayStatus(service, DataConfidence.STATIC_ONLY, englishWidgetStrings()) == "+4")
    }
}
