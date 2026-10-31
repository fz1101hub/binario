package it.binario.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.ui.TopDestination
import it.binario.app.ui.common.BinarioNavigationBar
import it.binario.app.ui.common.BinarioNavigationRail
import it.binario.app.ui.departures.HeroDepartureCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DestinationNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theThreeDestinationsAreLabelledSelectableAndReadableAtLargeFontScale() {
        var selected by mutableStateOf(TopDestination.PLAN)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                BinarioTheme {
                    BinarioNavigationBar(selected = selected, onSelect = { selected = it }, containerColor = Color.White)
                }
            }
        }

        composeRule.onNodeWithText("Pianifica").fetchSemanticsNode()
        composeRule.onNodeWithText("Partenze").fetchSemanticsNode()
        composeRule.onNodeWithText("Per te").fetchSemanticsNode()
        composeRule.onNodeWithTag("nav-plan").assertIsSelected()
        composeRule.onNodeWithTag("nav-for-you").assertIsNotSelected().performClick()
        composeRule.runOnIdle { assertEquals(TopDestination.FOR_YOU, selected) }
        composeRule.onNodeWithTag("nav-for-you").assertIsSelected()
        composeRule.onNodeWithTag("nav-departures").performClick()
        composeRule.runOnIdle { assertEquals(TopDestination.DEPARTURES, selected) }
    }

    @Test
    fun wideWindowsOfferTheSameDestinationsInTheNavigationRail() {
        var selected by mutableStateOf(TopDestination.DEPARTURES)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                BinarioTheme {
                    BinarioNavigationRail(selected = selected, onSelect = { selected = it })
                }
            }
        }

        composeRule.onNodeWithTag("navigation-rail").fetchSemanticsNode()
        composeRule.onNodeWithTag("nav-departures").assertIsSelected()
        composeRule.onNodeWithText("Per te").performClick()
        composeRule.runOnIdle { assertEquals(TopDestination.FOR_YOU, selected) }
        composeRule.onNodeWithTag("nav-plan").performClick()
        composeRule.runOnIdle { assertEquals(TopDestination.PLAN, selected) }
    }

    @Test
    fun theNextDepartureOfDemonstrationDataShowsNoDelayOrPlatformAsReal() {
        val service = TrainService(
            "demo-1", "2345", RailOperator.OTHER, TrainCategory.REGIONALE, "Roma Termini", "Napoli Centrale",
            "08:00", "08:06", "5", 6, ServiceStatus.DELAYED,
            listOf(TrainStop("Roma Termini", "08:00", "08:06", "5", 6, false)), operationalStatusVerified = true
        )
        var opened = false
        composeRule.setContent {
            BinarioTheme {
                HeroDepartureCard(service, isDemonstration = true, onOpen = { opened = true })
            }
        }

        val hero = composeRule.onNodeWithTag("departures-hero")
        val description = hero.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString(" ")
        assertTrue(description, description.startsWith("Prossima partenza. Catalogo dimostrativo locale — non live."))
        assertTrue(description, description.contains("Binario non disponibile"))
        assertFalse(description, description.contains("Binario 5"))
        assertFalse(description, description.contains("08:06"))
        composeRule.onNode(hasContentDescription("Napoli Centrale", substring = true)).assertExists()
        hero.performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }
}
