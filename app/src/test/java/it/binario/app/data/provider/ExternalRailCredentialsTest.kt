package it.binario.app.data.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRailCredentialsTest {
    @Test
    fun `toString non espone nessuna credenziale`() {
        val credentials = ExternalRailCredentials(
            swissApiKey = "swiss-secret-value",
            dbClientId = "db-client-secret-value",
            dbApiKey = "db-api-secret-value"
        )

        val rendered = credentials.toString()
        assertTrue(rendered.contains("redacted"))
        assertFalse(rendered.contains("swiss-secret-value"))
        assertFalse(rendered.contains("db-client-secret-value"))
        assertFalse(rendered.contains("db-api-secret-value"))
    }
}
