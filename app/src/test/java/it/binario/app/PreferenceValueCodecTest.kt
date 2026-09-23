package it.binario.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferenceValueCodecTest {
    @Test
    fun `codec preserves unicode values`() {
        val value = "Reggio nell’Emilia · binario 1"
        assertEquals(value, PreferenceValueCodec.decode(PreferenceValueCodec.encode(value)))
    }

    @Test
    fun `corrupted base64 is ignored`() {
        assertNull(PreferenceValueCodec.decode("%%%not-base64%%%"))
    }
}
