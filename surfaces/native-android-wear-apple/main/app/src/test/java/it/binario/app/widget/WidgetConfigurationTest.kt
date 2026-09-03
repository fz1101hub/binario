package it.binario.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConfigurationTest {
    @Test
    fun `custom widget name overrides target label`() {
        val configuration = WidgetDisplayConfiguration(WidgetTargetType.ROUTE, "roma-torino", "Roma — Torino", "Pendolare")

        assertEquals("Pendolare", configuration.widgetTitle())
    }

    @Test
    fun `blank widget name keeps target label`() {
        val configuration = WidgetDisplayConfiguration(WidgetTargetType.ROUTE, "roma-torino", "Roma — Torino", "  ")

        assertEquals("Roma — Torino", configuration.widgetTitle())
    }
}
