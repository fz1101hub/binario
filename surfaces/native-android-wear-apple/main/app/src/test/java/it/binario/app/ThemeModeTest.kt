package it.binario.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `stored theme names resolve to their matching modes`() {
        assertEquals(AppThemeMode.LIGHT, appThemeModeFromStoredValue("LIGHT"))
        assertEquals(AppThemeMode.DARK, appThemeModeFromStoredValue("DARK"))
    }

    @Test
    fun `missing or invalid theme values safely default to system`() {
        assertEquals(AppThemeMode.SYSTEM, appThemeModeFromStoredValue(null))
        assertEquals(AppThemeMode.SYSTEM, appThemeModeFromStoredValue("invalid"))
    }
}
