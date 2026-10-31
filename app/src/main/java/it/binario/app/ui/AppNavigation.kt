package it.binario.app.ui

import it.binario.app.BinarioDeepLink
import it.binario.app.ui.departures.OperationsSection
import it.binario.app.ui.planner.SearchMode

/** The three destinations of Binario 2.0: plan a search, read the departures, personal items. */
internal enum class TopDestination { PLAN, DEPARTURES, FOR_YOU }

/**
 * Every screen the host shows. A screen with a [destination] belongs to that tab; the train detail has
 * none and belongs to the screen it was opened from.
 */
internal enum class AppScreen(val destination: TopDestination?) {
    PLANNER(TopDestination.PLAN),
    RESULTS(TopDestination.PLAN),
    NUMBER_RESULTS(TopDestination.PLAN),
    DEPARTURES(TopDestination.DEPARTURES),
    FOR_YOU(TopDestination.FOR_YOU),
    /** The agenda of planned journeys: a section of "Per te", formerly its own tab. */
    AGENDA(TopDestination.FOR_YOU),
    DETAIL(null)
}

/** Width classes of the Material guidance, computed from the window width in dp. */
internal enum class WidthClass {
    COMPACT, MEDIUM, EXPANDED;

    companion object {
        fun fromWidthDp(widthDp: Float): WidthClass = when {
            widthDp >= 840f -> EXPANDED
            widthDp >= 600f -> MEDIUM
            else -> COMPACT
        }
    }
}

/** Where an external request (shortcut, URI, widget tap) lands. */
internal data class NavigationTarget(
    val screen: AppScreen,
    val departuresSection: OperationsSection? = null,
    val plannerMode: SearchMode? = null
)

internal object AppNavigation {
    fun rootOf(destination: TopDestination): AppScreen = when (destination) {
        TopDestination.PLAN -> AppScreen.PLANNER
        TopDestination.DEPARTURES -> AppScreen.DEPARTURES
        TopDestination.FOR_YOU -> AppScreen.FOR_YOU
    }

    /** The tab highlighted for [screen]; the detail highlights the tab of the screen it returns to. */
    fun destinationOf(screen: AppScreen, detailParent: AppScreen): TopDestination =
        screen.destination ?: detailParent.destination ?: TopDestination.PLAN

    /** Screen shown by Back, or null when Back leaves the app (top-level screens, as before). */
    fun back(screen: AppScreen, detailParent: AppScreen): AppScreen? = when (screen) {
        AppScreen.DETAIL -> detailParent.takeIf { it != AppScreen.DETAIL } ?: AppScreen.PLANNER
        AppScreen.RESULTS, AppScreen.NUMBER_RESULTS -> AppScreen.PLANNER
        AppScreen.AGENDA -> AppScreen.FOR_YOU
        AppScreen.PLANNER, AppScreen.DEPARTURES, AppScreen.FOR_YOU -> null
    }

    /**
     * Phones keep the bottom bar out of the search flow (results and detail are full screen with Back);
     * from 600 dp the navigation rail is always visible.
     */
    fun showsNavigation(screen: AppScreen, widthClass: WidthClass): Boolean =
        widthClass != WidthClass.COMPACT || screen in setOf(AppScreen.PLANNER, AppScreen.DEPARTURES, AppScreen.FOR_YOU, AppScreen.AGENDA)

    /** Screens whose rail data is refreshed every 30 seconds while visible. */
    fun refreshesPeriodically(screen: AppScreen): Boolean =
        screen in setOf(AppScreen.RESULTS, AppScreen.NUMBER_RESULTS, AppScreen.DETAIL, AppScreen.DEPARTURES)

    /**
     * From 840 dp lists and detail sit side by side: the list screen kept on the left, or null for a
     * single pane. The detail opened from a list keeps that list visible.
     */
    fun listPaneOf(screen: AppScreen, detailParent: AppScreen, widthClass: WidthClass): AppScreen? {
        if (widthClass != WidthClass.EXPANDED) return null
        val list = if (screen == AppScreen.DETAIL) detailParent else screen
        return list.takeIf { it in TWO_PANE_LISTS }
    }

    fun targetOf(deepLink: BinarioDeepLink): NavigationTarget = when (deepLink) {
        BinarioDeepLink.Agenda -> NavigationTarget(AppScreen.AGENDA)
        BinarioDeepLink.ActiveRoute, BinarioDeepLink.NextDepartures, is BinarioDeepLink.WidgetRoute ->
            NavigationTarget(AppScreen.DEPARTURES, departuresSection = OperationsSection.TRAINS)
        is BinarioDeepLink.TrainSearch -> NavigationTarget(AppScreen.PLANNER, plannerMode = SearchMode.TRAIN)
    }

    private val TWO_PANE_LISTS = setOf(AppScreen.DEPARTURES, AppScreen.RESULTS, AppScreen.NUMBER_RESULTS)
}
