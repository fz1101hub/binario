package it.binario.app

import it.binario.app.data.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The station indexes built off the main thread at a cold start (audit2 android-state-7). */
class StationIndexesTest {
    private val roma = Station("curated:S08409", "Roma Termini", "Roma", aliases = listOf("Roma Termini FS"))
    private val milano = Station("curated:S01700", "Milano Centrale", "Milano")
    private val catalogue = listOf(roma, milano)

    @Test fun `the catalogue comes first and remembered stations outside it follow`() {
        val typed = stationFromFreeText("Borgo San Dalmazzo")!!
        val indexes = StationIndexes.build(catalogue, listOf(typed, roma))
        assertEquals(listOf(roma.id, milano.id, typed.id), indexes.stations.map { it.id })
        assertSame(typed, indexes.autocomplete.findExact("Borgo San Dalmazzo"))
        assertSame(roma, indexes.autocomplete.findExact("Roma Termini"))
        assertSame(typed, indexes.canonicalizer.canonicalize(typed))
        assertEquals(listOf(typed, roma), indexes.remembered)
    }

    @Test fun `a remembered station the catalogue knows under another identifier becomes the catalogue's`() {
        val legacy = Station("roma-termini", "Roma Termini", "Roma")
        val indexes = StationIndexes.build(catalogue, listOf(legacy, legacy.copy(id = "manual:roma-termini")))
        assertEquals(listOf(roma.id, milano.id), indexes.stations.map { it.id })
        assertSame(roma, indexes.canonicalizer.canonicalize(legacy))
    }

    @Test fun `without remembered stations the list is the catalogue`() {
        val indexes = StationIndexes.build(catalogue, emptyList())
        assertEquals(catalogue, indexes.stations)
        assertSame(milano, indexes.canonicalizer.canonicalize(Station("x", "Milano Centrale", "Milano")))
    }
}
