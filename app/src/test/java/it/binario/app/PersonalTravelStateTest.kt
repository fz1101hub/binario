package it.binario.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalTravelStateTest {
    @Test
    fun `active trip advances to the next stop without skipping`() {
        val trip = ActiveTrip("id", "4132", "Roma → Torino", listOf(ActiveTripStop("Firenze", "10:00", "4"), ActiveTripStop("Bologna", "11:00", null)))
        assertEquals("Firenze", trip.nextStop?.station)
        assertEquals("Bologna", trip.advance().nextStop?.station)
    }

    @Test
    fun `finished active trip is marked complete`() {
        val trip = ActiveTrip("id", "4132", "Roma → Torino", emptyList())
        assertTrue(trip.isComplete)
    }
}
