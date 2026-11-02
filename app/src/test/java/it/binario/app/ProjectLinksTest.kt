package it.binario.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectLinksTest {
    @Test
    fun `the station suggestion opens the issue form with an encoded title`() {
        assertEquals(
            "https://github.com/fz1101hub/binario/issues/new?title=Suggerimento+stazione%3A+",
            ProjectLinks.newIssue("Suggerimento stazione: ")
        )
    }
}
