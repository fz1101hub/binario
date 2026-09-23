package it.binario.app.data.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalStatusTermsTest {
    @Test
    fun `Cancello Arnone non viene interpretata come corsa cancellata`() {
        assertFalse("Regionale 21237 per Cancello Arnone".hasExplicitCancellationStatus())
    }

    @Test
    fun `riconosce soltanto stati espliciti di soppressione o cancellazione`() {
        assertTrue("Treno soppresso".hasExplicitCancellationStatus())
        assertTrue("Corsa cancellata".hasExplicitCancellationStatus())
        assertFalse("Il treno cancella il ritardo accumulato".hasExplicitCancellationStatus())
    }
}
