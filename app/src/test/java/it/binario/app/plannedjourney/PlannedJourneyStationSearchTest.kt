package it.binario.app.plannedjourney

import org.junit.Assert.*
import org.junit.Test

class PlannedJourneyStationSearchTest {
    private val rome = PlannedJourneyStation("IT:ROMA-TERMINI", "Roma Termini", "RMTE")
    private val ostiense = PlannedJourneyStation("IT:ROMA-OSTIENSE", "Roma Ostiense", "RMOS")
    private val merano = PlannedJourneyStation("IT:MERANO", "Merano / Meràn", "MER")
    private val search = PlannedJourneyStationSearch(listOf(rome, ostiense, merano))

    @Test fun `search accepts names accents station codes and reversed words`() {
        assertEquals(listOf(merano), search.suggestions("meran"))
        assertEquals(listOf(rome), search.suggestions("rmte"))
        assertEquals(listOf(rome), search.suggestions("termini roma"))
        assertEquals(listOf(rome), search.suggestions("IT:ROMA-TERMINI"))
    }

    @Test fun `exact codes rank before name prefixes and results are stable`() {
        val first = PlannedJourneyStation("IT:ABC", "Roma", "ROMA")
        val index = PlannedJourneyStationSearch(listOf(rome, ostiense, first))
        assertEquals(first, index.suggestions("roma").first())
        assertEquals(listOf(ostiense, rome), search.suggestions("roma"))
    }

    @Test fun `same names remain distinguishable by canonical identity`() {
        val other = rome.copy(canonicalId = "IT:OTHER", railwayCode = "OTHER")
        assertEquals(2, PlannedJourneyStationSearch(listOf(rome, other)).suggestions("Roma Termini").size)
    }

    @Test fun `suggestion output is bounded and blank or oversized input does not search`() {
        val many = (1..50).map { PlannedJourneyStation("IT:$it", "Stazione $it") }
        assertEquals(6, PlannedJourneyStationSearch(many).suggestions("stazione").size)
        assertTrue(search.suggestions("").isEmpty())
        assertTrue(search.suggestions(" ").isEmpty())
        assertTrue(search.suggestions("a".repeat(121)).isEmpty())
    }
}
