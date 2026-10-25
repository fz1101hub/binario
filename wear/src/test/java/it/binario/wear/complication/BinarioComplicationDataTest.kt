package it.binario.wear.complication

import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import it.binario.wear.ResourceNameStrings
import it.binario.wear.model.WearConfidence
import it.binario.wear.model.WearDataNature
import it.binario.wear.model.WearDirection
import it.binario.wear.model.WearFreshness
import it.binario.wear.model.WearOperationalState
import it.binario.wear.model.WearProvenance
import it.binario.wear.model.WearProvenanceProducer
import it.binario.wear.model.WearService
import it.binario.wear.model.WearSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BinarioComplicationDataTest {
    @Test
    fun `short text splits departure and train number instead of one combined string`() {
        val texts = shortTextComplicationTexts(snapshot(services = listOf(service)), ResourceNameStrings)

        assertEquals("08:15", texts.text)
        assertEquals("RV 2345", texts.title)
        assertEquals(
            "accessibility_scheduled_departure(08:05). accessibility_expected_departure(08:15). accessibility_train(RV 2345). " +
                "Firenze S. M. Novella. Trenitalia. Regionale Veloce. accessibility_platform(4). In orario",
            texts.contentDescription,
        )
    }

    @Test
    fun `short text without a service shows the app name and reads the data status`() {
        val texts = shortTextComplicationTexts(snapshot(services = emptyList()), ResourceNameStrings)

        assertEquals("app_name", texts.text)
        assertNull(texts.title)
        assertEquals("status_received_from_phone", texts.contentDescription)
    }

    @Test
    fun `long text keeps the combined label with the route as title`() {
        val withService = longTextComplicationTexts(snapshot(services = listOf(service)), ResourceNameStrings)
        val withoutService = longTextComplicationTexts(snapshot(services = emptyList()), ResourceNameStrings)

        assertEquals("service_time_number(08:15, RV 2345)", withService.text)
        assertEquals("Roma Termini → Firenze S. M. Novella", withService.title)
        assertEquals("status_received_from_phone", withoutService.text)
        assertEquals("Roma Termini → Firenze S. M. Novella", withoutService.title)
    }

    @Test
    fun `complication data ends at the snapshot deadline and is empty without a snapshot`() {
        val expiresAt = 1_893_485_700_000L
        val snapshot = snapshot(expiresAt = expiresAt, services = listOf(service))

        val short = binarioComplicationData(ComplicationType.SHORT_TEXT, snapshot, ResourceNameStrings)
        val long = binarioComplicationData(ComplicationType.LONG_TEXT, snapshot, ResourceNameStrings)

        assertEquals(ComplicationType.SHORT_TEXT, short.type)
        assertEquals(expiresAt, short.validTimeRange.endDateTimeMillis)
        assertNotNull((short as ShortTextComplicationData).title)
        assertEquals(ComplicationType.LONG_TEXT, long.type)
        assertEquals(expiresAt, long.validTimeRange.endDateTimeMillis)
        assertNotNull((long as LongTextComplicationData).title)
        assertEquals(ComplicationType.NO_DATA, binarioComplicationData(ComplicationType.SHORT_TEXT, null, ResourceNameStrings).type)
        assertEquals(ComplicationType.NO_DATA, binarioComplicationData(ComplicationType.RANGED_VALUE, snapshot, ResourceNameStrings).type)
    }

    private val service = WearService(
        number = "RV 2345",
        operator = "Trenitalia",
        category = "Regionale Veloce",
        destination = "Firenze S. M. Novella",
        scheduledDeparture = "08:05",
        expectedDeparture = "08:15",
        status = "In orario",
        platform = "4",
        delayMinutes = 10,
    )

    private fun snapshot(expiresAt: Long = 1_893_485_700_000L, services: List<WearService>): WearSnapshot = WearSnapshot(
        routeId = "route-id",
        routeLabel = "Roma Termini → Firenze S. M. Novella",
        direction = WearDirection.OUTBOUND,
        provenance = WearProvenance(
            producer = WearProvenanceProducer.ANDROID_APP,
            dataNature = WearDataNature.OPERATIONAL,
            sourceLabel = "Fonte",
            observedAtEpochMillis = expiresAt - 60_000L,
        ),
        operationalState = WearOperationalState.OPERATIONAL,
        freshness = WearFreshness.FRESH,
        confidence = WearConfidence.OPERATOR_PRIMARY,
        generatedAtEpochMillis = expiresAt - 60_000L,
        expiresAtEpochMillis = expiresAt,
        ttlMillis = 60_000L,
        notice = "",
        services = services,
    )
}
