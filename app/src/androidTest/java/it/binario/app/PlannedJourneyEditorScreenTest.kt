package it.binario.app

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import it.binario.app.plannedjourney.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
@android.annotation.TargetApi(29)
class PlannedJourneyEditorScreenTest {
    @org.junit.Before fun supportedSdk() { org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29) }

    @get:Rule val compose = createComposeRule()

    @Test fun catalogSuggestionsAndNativePickersCreateTheChosenJourney() {
        val rome = PlannedJourneyStation("IT:ROMA-TERMINI", "Roma Termini", "RMTE")
        val milan = PlannedJourneyStation("IT:MILANO-CENTRALE", "Milano Centrale", "MICT")
        val storage = object : PlannedJourneyStorage {
            var encoded: String? = null
            override fun read() = encoded
            override fun write(value: String) { encoded = value }
        }
        val model = PlannedJourneyViewModel(PlannedJourneyRepository(storage))
        model.beginCreate()
        compose.setContent {
            BinarioTheme {
                PlannedJourneyAgendaScreen(viewModel = model, stations = listOf(rome, milan),
                    onExportJson = {}, onExportIcs = {}, onImportRequested = {})
            }
        }
        compose.onNodeWithTag("plannedJourneyOrigin").performClick().performTextInput("rmte")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("plannedJourneyOrigin:${rome.canonicalId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("plannedJourneyEditor").performScrollToNode(hasTestTag("plannedJourneyOrigin:${rome.canonicalId}"))
        compose.onNodeWithTag("plannedJourneyOrigin:${rome.canonicalId}").performClick()
        compose.onNodeWithTag("plannedJourneyEditor").performScrollToNode(hasTestTag("plannedJourneyDestination"))
        compose.onNodeWithTag("plannedJourneyDestination").performClick().performTextInput("milano")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("plannedJourneyDestination:${milan.canonicalId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("plannedJourneyEditor").performScrollToNode(hasTestTag("plannedJourneyDestination:${milan.canonicalId}"))
        compose.onNodeWithTag("plannedJourneyDestination:${milan.canonicalId}").performClick()
        compose.onNodeWithTag("plannedJourneyEditor").performScrollToNode(hasTestTag("plannedJourneyDate"))
        compose.onNodeWithTag("plannedJourneyDate").performClick()
        nativeWidget(DatePicker::class.java) {
            (it as DatePicker).updateDate(2099, 8, 20)
        }
        confirmNativePicker()
        compose.onNodeWithTag("plannedJourneyEditor").performScrollToNode(hasTestTag("plannedJourneyTime"))
        compose.onNodeWithTag("plannedJourneyTime").performClick()
        nativeWidget(TimePicker::class.java) {
            val picker = it as TimePicker
            picker.hour = 10
            picker.minute = 15
        }
        confirmNativePicker()
        compose.onNodeWithTag("plannedJourneySave").performClick()
        compose.runOnIdle {
            val leg = model.snapshot.journeys.single().itinerary.segments.single()
            assertEquals(rome, leg.origin)
            assertEquals(milan, leg.destination)
            assertEquals("2099-09-20", leg.departure.date.toString())
            assertEquals("10:15", leg.departure.time.toString())
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun nativeWidget(type: Class<out View>, action: (View) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val widget = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::views)
                .first { type.isInstance(it) && it.isShown }
            action(widget)
        }
        compose.waitForIdle()
    }

    private fun confirmNativePicker() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val button = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::views)
                .first { it.id == android.R.id.button1 && it.isShown }
            check(button.performClick())
        }
        compose.waitForIdle()
    }
}
