package it.binario.app.ui

import it.binario.app.ui.common.OperatorNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OperatorNamesTest {
    @Test
    fun `board operators read as names and codes are left out`() {
        assertEquals("Trenitalia", OperatorNames.displayName("trenitalia"))
        assertEquals("FART/SSIF", OperatorNames.displayName("fart-ssif"))
        assertEquals("European Sleeper", OperatorNames.displayName("european-sleeper"))
        assertEquals("ÖBB", OperatorNames.displayName("oebb"))
        assertEquals("ATM Milano", OperatorNames.displayName("ATM Milano"))
        assertNull(OperatorNames.displayName("800165"))
        assertNull(OperatorNames.displayName("some-slug"))
        assertNull(OperatorNames.displayName(" "))
    }
}
