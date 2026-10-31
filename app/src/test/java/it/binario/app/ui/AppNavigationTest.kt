package it.binario.app.ui

import it.binario.app.BinarioDeepLink
import it.binario.app.data.Direction
import it.binario.app.data.SavedRoute
import it.binario.app.data.Station
import it.binario.app.ui.departures.OperationsSection
import it.binario.app.ui.planner.SearchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationTest {
    @Test
    fun `width classes follow the 600 and 840 dp breakpoints`() {
        assertEquals(WidthClass.COMPACT, WidthClass.fromWidthDp(411f))
        assertEquals(WidthClass.COMPACT, WidthClass.fromWidthDp(599.9f))
        assertEquals(WidthClass.MEDIUM, WidthClass.fromWidthDp(600f))
        assertEquals(WidthClass.MEDIUM, WidthClass.fromWidthDp(839f))
        assertEquals(WidthClass.EXPANDED, WidthClass.fromWidthDp(840f))
        assertEquals(WidthClass.EXPANDED, WidthClass.fromWidthDp(1280f))
    }

    @Test
    fun `every screen belongs to one of the three destinations`() {
        assertEquals(TopDestination.PLAN, AppNavigation.destinationOf(AppScreen.RESULTS, AppScreen.PLANNER))
        assertEquals(TopDestination.PLAN, AppNavigation.destinationOf(AppScreen.NUMBER_RESULTS, AppScreen.PLANNER))
        assertEquals(TopDestination.FOR_YOU, AppNavigation.destinationOf(AppScreen.AGENDA, AppScreen.PLANNER))
        assertEquals(TopDestination.DEPARTURES, AppNavigation.destinationOf(AppScreen.DETAIL, AppScreen.DEPARTURES))
        assertEquals(TopDestination.PLAN, AppNavigation.destinationOf(AppScreen.DETAIL, AppScreen.NUMBER_RESULTS))
        TopDestination.entries.forEach { destination ->
            assertEquals(destination, AppNavigation.rootOf(destination).destination)
        }
    }

    @Test
    fun `back returns to the parent and leaves the app only from a top-level screen`() {
        assertEquals(AppScreen.DEPARTURES, AppNavigation.back(AppScreen.DETAIL, AppScreen.DEPARTURES))
        assertEquals(AppScreen.RESULTS, AppNavigation.back(AppScreen.DETAIL, AppScreen.RESULTS))
        assertEquals(AppScreen.PLANNER, AppNavigation.back(AppScreen.RESULTS, AppScreen.PLANNER))
        assertEquals(AppScreen.PLANNER, AppNavigation.back(AppScreen.NUMBER_RESULTS, AppScreen.PLANNER))
        assertEquals(AppScreen.FOR_YOU, AppNavigation.back(AppScreen.AGENDA, AppScreen.PLANNER))
        assertNull(AppNavigation.back(AppScreen.PLANNER, AppScreen.PLANNER))
        assertNull(AppNavigation.back(AppScreen.DEPARTURES, AppScreen.PLANNER))
        assertNull(AppNavigation.back(AppScreen.FOR_YOU, AppScreen.PLANNER))
    }

    @Test
    fun `phones hide the bottom bar in the search flow while the rail stays from 600 dp`() {
        listOf(AppScreen.PLANNER, AppScreen.DEPARTURES, AppScreen.FOR_YOU, AppScreen.AGENDA).forEach {
            assertTrue(AppNavigation.showsNavigation(it, WidthClass.COMPACT))
        }
        listOf(AppScreen.RESULTS, AppScreen.NUMBER_RESULTS, AppScreen.DETAIL).forEach {
            assertFalse(AppNavigation.showsNavigation(it, WidthClass.COMPACT))
            assertTrue(AppNavigation.showsNavigation(it, WidthClass.MEDIUM))
            assertTrue(AppNavigation.showsNavigation(it, WidthClass.EXPANDED))
        }
    }

    @Test
    fun `lists and detail share the window only from 840 dp`() {
        assertNull(AppNavigation.listPaneOf(AppScreen.DETAIL, AppScreen.DEPARTURES, WidthClass.COMPACT))
        assertNull(AppNavigation.listPaneOf(AppScreen.DETAIL, AppScreen.DEPARTURES, WidthClass.MEDIUM))
        assertEquals(AppScreen.DEPARTURES, AppNavigation.listPaneOf(AppScreen.DEPARTURES, AppScreen.PLANNER, WidthClass.EXPANDED))
        assertEquals(AppScreen.DEPARTURES, AppNavigation.listPaneOf(AppScreen.DETAIL, AppScreen.DEPARTURES, WidthClass.EXPANDED))
        assertEquals(AppScreen.RESULTS, AppNavigation.listPaneOf(AppScreen.DETAIL, AppScreen.RESULTS, WidthClass.EXPANDED))
        assertEquals(AppScreen.NUMBER_RESULTS, AppNavigation.listPaneOf(AppScreen.NUMBER_RESULTS, AppScreen.PLANNER, WidthClass.EXPANDED))
        assertNull(AppNavigation.listPaneOf(AppScreen.PLANNER, AppScreen.PLANNER, WidthClass.EXPANDED))
        assertNull(AppNavigation.listPaneOf(AppScreen.FOR_YOU, AppScreen.PLANNER, WidthClass.EXPANDED))
        assertNull(AppNavigation.listPaneOf(AppScreen.AGENDA, AppScreen.PLANNER, WidthClass.EXPANDED))
    }

    @Test
    fun `existing deep links land on the new destinations`() {
        val departures = NavigationTarget(AppScreen.DEPARTURES, departuresSection = OperationsSection.TRAINS)
        assertEquals(departures, AppNavigation.targetOf(BinarioDeepLink.ActiveRoute))
        assertEquals(departures, AppNavigation.targetOf(BinarioDeepLink.NextDepartures))
        val route = SavedRoute("r", "Roma – Napoli", Station("roma", "Roma Termini", "Roma"), Station("napoli", "Napoli Centrale", "Napoli"))
        assertEquals(departures, AppNavigation.targetOf(BinarioDeepLink.WidgetRoute(route, Direction.RETURN)))
        assertEquals(NavigationTarget(AppScreen.AGENDA), AppNavigation.targetOf(BinarioDeepLink.Agenda))
        assertEquals(
            NavigationTarget(AppScreen.PLANNER, plannerMode = SearchMode.TRAIN),
            AppNavigation.targetOf(BinarioDeepLink.TrainSearch("9920"))
        )
        assertEquals(AppScreen.PLANNER, AppNavigation.targetOf(BinarioDeepLink.TrainSearch(null)).screen)
    }

    @Test
    fun `only visible boards and the detail poll for updates`() {
        assertTrue(AppNavigation.refreshesPeriodically(AppScreen.DEPARTURES))
        assertTrue(AppNavigation.refreshesPeriodically(AppScreen.DETAIL))
        assertTrue(AppNavigation.refreshesPeriodically(AppScreen.RESULTS))
        assertTrue(AppNavigation.refreshesPeriodically(AppScreen.NUMBER_RESULTS))
        assertFalse(AppNavigation.refreshesPeriodically(AppScreen.PLANNER))
        assertFalse(AppNavigation.refreshesPeriodically(AppScreen.FOR_YOU))
        assertFalse(AppNavigation.refreshesPeriodically(AppScreen.AGENDA))
    }
}
