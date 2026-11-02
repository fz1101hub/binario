package it.binario.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRowsTest {
    @Test
    fun `the station suggestion sits right before Informazioni as in the render`() {
        val last = SETTINGS_CARDS.last()
        assertEquals(listOf(SettingsRow.SuggestStation, SettingsRow.Group(SettingsSection.ABOUT)), last)
        assertEquals(
            listOf(SettingsSection.ALERTS, SettingsSection.WIDGETS, SettingsSection.DATA, SettingsSection.LANGUAGE).map { SettingsRow.Group(it) },
            SETTINGS_CARDS.first()
        )
    }

    @Test
    fun `every group of settings is offered exactly once and the suggestion once`() {
        val rows = SETTINGS_CARDS.flatten()
        assertEquals(SettingsSection.entries.toSet(), rows.filterIsInstance<SettingsRow.Group>().map { it.section }.toSet())
        assertEquals(SettingsSection.entries.size, rows.filterIsInstance<SettingsRow.Group>().size)
        assertEquals(1, rows.count { it == SettingsRow.SuggestStation })
    }
}
