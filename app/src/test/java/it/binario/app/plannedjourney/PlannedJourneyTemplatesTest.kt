package it.binario.app.plannedjourney

import it.binario.app.UiMessage
import it.binario.app.UiMessageKey
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Journey templates, ported from the companion (EXPO_PORT_PLAN F7, R19). */
class PlannedJourneyTemplatesTest {
    private val rome = PlannedJourneyStation("S08409", "Roma Termini", "RMTE")
    private val milan = PlannedJourneyStation("S01700", "Milano Centrale", "MICT")
    private val createdAt = Instant.parse("2026-09-01T08:00:00Z")

    @Test fun `a template keeps route, time, lead and checklist, and applying it leaves the date to choose`() {
        val store = MemoryTemplates()
        var ids = 0
        val model = PlannedJourneyViewModel(PlannedJourneyRepository(FakeStorage()), now = { createdAt }, zone = ZoneId.of("Europe/Rome"),
            newId = { "t-${++ids}" }, templateStore = store)
        model.beginCreate()
        model.changeEditor { it.copy(originQuery = rome.displayName, destinationQuery = milan.displayName, date = "2026-10-01", time = "07:40",
            reminderMinutes = PlannedJourneyReminderMinutes.FIFTEEN, checklistText = "Biglietto\nAcqua") }
        model.saveTemplate("  Casa-lavoro ", listOf(rome, milan))
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_TEMPLATE_SAVED), model.editor?.notice)
        val reloaded = PlannedJourneyTemplates.decode(store.value).single()
        assertEquals(PlannedJourneyTemplate("t-1", "Casa-lavoro", rome, milan, LocalTime.of(7, 40), PlannedJourneyReminderMinutes.FIFTEEN, listOf("Biglietto", "Acqua")), reloaded)

        model.dismissEditor()
        model.beginCreate()
        model.applyTemplate("t-1")
        val editor = requireNotNull(model.editor)
        assertEquals("" to "07:40", editor.date to editor.time)
        assertEquals(milan.canonicalId, editor.destinationStationId)
        assertEquals("Biglietto\nAcqua", editor.checklistText)
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_TEMPLATE_APPLIED, "Casa-lavoro"), editor.notice)

        model.deleteTemplate("t-1")
        assertTrue(model.templates.isEmpty())
        assertNull(store.value)
    }

    @Test fun `a template needs two catalogue stations, a time and a clean checklist`() {
        val model = PlannedJourneyViewModel(PlannedJourneyRepository(FakeStorage()), now = { createdAt }, templateStore = MemoryTemplates())
        model.beginCreate()
        model.changeEditor { it.copy(originQuery = rome.displayName, destinationQuery = "", time = "07:40") }
        model.saveTemplate("Casa", listOf(rome, milan))
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_TEMPLATE_INVALID), model.editor?.validationMessage)
        model.changeEditor { it.copy(destinationQuery = milan.displayName, checklistText = "PIN 1234") }
        model.saveTemplate("Casa", listOf(rome, milan))
        assertEquals(UiMessage(UiMessageKey.PLANNED_JOURNEY_TEMPLATE_INVALID), model.editor?.validationMessage)
        assertTrue(model.templates.isEmpty())
    }

    @Test fun `twelve templates at most, newest first, a same name replaces, unreadable storage reads as none`() {
        var list = emptyList<PlannedJourneyTemplate>()
        (1..13).forEach { index -> list = PlannedJourneyTemplates.add(list, template("id-$index", "Modello $index")) }
        assertEquals(PlannedJourneyTemplates.MAX_TEMPLATES, list.size)
        assertEquals("Modello 13", list.first().name)
        assertTrue(list.none { it.name == "Modello 1" })
        list = PlannedJourneyTemplates.add(list, template("other", "modello 13"))
        assertEquals(listOf("other"), list.filter { it.name.equals("modello 13", ignoreCase = true) }.map { it.id })
        assertEquals(list, PlannedJourneyTemplates.decode(PlannedJourneyTemplates.encode(list)))
        assertTrue(PlannedJourneyTemplates.decode("{broken").isEmpty())
        assertTrue(PlannedJourneyTemplates.decode("""{"kind":"other","schemaVersion":1,"templates":[]}""").isEmpty())
    }

    private fun template(id: String, name: String) = PlannedJourneyTemplate(id, name, rome, milan, LocalTime.of(8, 0))

    private class MemoryTemplates(var value: String? = null) : PlannedJourneyTemplateStore {
        override fun read(): String? = value
        override fun write(value: String?) { this.value = value }
    }

    private class FakeStorage(var value: String? = null) : PlannedJourneyStorage {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }
}
