package it.binario.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import it.binario.app.plannedjourney.PlannedJourney
import it.binario.app.plannedjourney.PlannedJourneyAgendaScreen
import it.binario.app.plannedjourney.PlannedJourneyChecklistItem
import it.binario.app.plannedjourney.PlannedJourneyDateTime
import it.binario.app.plannedjourney.PlannedJourneyItinerary
import it.binario.app.plannedjourney.PlannedJourneyRepository
import it.binario.app.plannedjourney.PlannedJourneySegment
import it.binario.app.plannedjourney.PlannedJourneyStation
import it.binario.app.plannedjourney.PlannedJourneyStorage
import it.binario.app.plannedjourney.PlannedJourneyViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannedJourneyAgendaScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun agendaExposesLocalOnlyActionsAndChecklistSemanticsAtLargeFontScale() {
        val rome = PlannedJourneyStation("IT:ROMA-TERMINI", "Roma Termini", "RMTE")
        val milan = PlannedJourneyStation("IT:MILANO-CENTRALE", "Milano Centrale", "MICT")
        val repository = PlannedJourneyRepository(MemoryStorage())
        repository.save(
            PlannedJourney(
                id = "agenda-ui",
                label = "Riunione Milano",
                itinerary = PlannedJourneyItinerary(
                    listOf(
                        PlannedJourneySegment(
                            "segment-1",
                            rome,
                            milan,
                            PlannedJourneyDateTime(
                                LocalDate.of(2026, 10, 1),
                                LocalTime.of(8, 15),
                                ZoneId.of("Europe/Rome")
                            )
                        )
                    )
                ),
                checklist = listOf(PlannedJourneyChecklistItem("ticket", "Prepara il biglietto")),
                createdAt = Instant.parse("2026-09-01T08:00:00Z")
            )
        )
        val model = PlannedJourneyViewModel(
            repository,
            now = { Instant.parse("2026-09-20T08:00:00Z") },
            zone = ZoneId.of("Europe/Rome")
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                BinarioTheme {
                    PlannedJourneyAgendaScreen(
                        viewModel = model,
                        stations = listOf(rome, milan),
                        onExportJson = {},
                        onExportIcs = {},
                        onImportRequested = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("plannedJourneyAgenda").performScrollToNode(hasText("Riunione Milano"))
        composeRule.onNodeWithText("Riunione Milano").fetchSemanticsNode()
        composeRule.onNodeWithTag("plannedJourneyAgenda").performScrollToNode(hasText("Viaggi salvati solo su questo dispositivo. Nessuna sincronizzazione."))
        composeRule.onNodeWithText("Viaggi salvati solo su questo dispositivo. Nessuna sincronizzazione.").fetchSemanticsNode()
        composeRule.onNodeWithTag("plannedJourneyAgenda").performScrollToNode(hasContentDescription("Checklist: Prepara il biglietto"))
        composeRule.onNodeWithContentDescription("Checklist: Prepara il biglietto").fetchSemanticsNode()
        composeRule.onNodeWithTag("plannedJourneyAgenda").performScrollToNode(hasText("Esporta JSON"))
        composeRule.onNodeWithText("Esporta JSON").fetchSemanticsNode()
        composeRule.onNodeWithText("Esporta ICS").fetchSemanticsNode()
    }

    private class MemoryStorage : PlannedJourneyStorage {
        private var content: String? = null
        override fun read(): String? = content
        override fun write(value: String) { content = value }
    }
}
