package it.binario.app.ui

import it.binario.app.data.core.LegMode
import it.binario.app.ui.journeys.JourneyLegKind
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyLegKindTest {
    @Test
    fun `every leg mode has a kind and unknown modes fall back to a generic leg`() {
        assertEquals(JourneyLegKind.TRAIN, JourneyLegKind.of(LegMode.TRAIN))
        assertEquals(JourneyLegKind.REPLACEMENT_BUS, JourneyLegKind.of(LegMode.REPLACEMENT_BUS))
        assertEquals(JourneyLegKind.WALK, JourneyLegKind.of(LegMode.WALK))
        assertEquals(JourneyLegKind.OTHER, JourneyLegKind.of(LegMode.UNKNOWN))
        LegMode.entries.forEach { JourneyLegKind.of(it) }
    }

    @Test
    fun `a scheduled bus mode of the timetable packs is drawn as a bus`() {
        assertEquals(JourneyLegKind.BUS, JourneyLegKind.ofModeName("BUS"))
        assertEquals(JourneyLegKind.BUS, JourneyLegKind.ofModeName("SCHEDULED_BUS"))
        assertEquals(JourneyLegKind.OTHER, JourneyLegKind.ofModeName("FERRY"))
        assertEquals(JourneyLegKind.OTHER, JourneyLegKind.ofModeName("BUSWAY_TRAM"))
    }
}
