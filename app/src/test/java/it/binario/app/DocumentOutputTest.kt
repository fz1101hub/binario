package it.binario.app

import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Exports replace the whole document they are written to (second audit, android-state-14). */
class DocumentOutputTest {
    @Test fun `the document is opened truncated`() {
        val modes = mutableListOf<String>()
        val stream = ByteArrayOutputStream()
        assertSame(stream, openReplacing { mode -> modes += mode; stream })
        assertEquals(listOf("wt"), modes)
    }

    @Test fun `a provider that refuses truncation is asked to write`() {
        for (refusal in listOf(FileNotFoundException("mode wt"), IllegalArgumentException("mode wt"), UnsupportedOperationException("mode wt"))) {
            val modes = mutableListOf<String>()
            val stream = ByteArrayOutputStream()
            assertSame(stream, openReplacing { mode -> modes += mode; if (mode == "wt") throw refusal else stream })
            assertEquals(listOf("wt", "w"), modes)
        }
    }

    @Test fun `any other failure is not hidden`() {
        val modes = mutableListOf<String>()
        assertThrows(IOException::class.java) { openReplacing { mode -> modes += mode; throw IOException("disk full") } }
        assertEquals(listOf("wt"), modes)
    }
}
