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
    /** "Impostazioni", reached from "Per te": theme and the grouped settings rows. */
    SETTINGS(TopDestination.FOR_YOU),
    /** One group of settings opened from "Impostazioni" (alerts, widgets, data, language, information). */
    SETTINGS_SECTION(TopDestination.FOR_YOU),
    DETAIL(null),
    /** An option of the journeys-with-changes search, opened from the route results. */
    JOURNEY_DETAIL(TopDestination.PLAN),
    /** A result list saved from the route results ("Orari salvati" of Per te), read-only and offline. */
    SAVED_TIMETABLE(TopDestination.FOR_YOU)
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

    /**
     * The tab highlighted for [screen]; the detail highlights the tab of the screen it returns to, and the results
     * (route, train number, journey) the tab they were opened from ([resultsParent]: Pianifica or Per te).
     */
    fun destinationOf(screen: AppScreen, detailParent: AppScreen, resultsParent: AppScreen = AppScreen.PLANNER): TopDestination {
        val shown = if (screen == AppScreen.DETAIL) detailParent else screen
        return when (shown) {
            in RESULT_SCREENS -> resultsParentOf(resultsParent).destination
            else -> shown.destination
        } ?: TopDestination.PLAN
    }

    /** Screen shown by Back, or null when Back leaves the app (top-level screens, as before). */
    fun back(screen: AppScreen, detailParent: AppScreen, resultsParent: AppScreen = AppScreen.PLANNER): AppScreen? = when (screen) {
        AppScreen.DETAIL -> detailParent.takeIf { it != AppScreen.DETAIL } ?: AppScreen.PLANNER
        AppScreen.RESULTS, AppScreen.NUMBER_RESULTS -> resultsParentOf(resultsParent)
        AppScreen.JOURNEY_DETAIL -> AppScreen.RESULTS
        AppScreen.AGENDA, AppScreen.SETTINGS, AppScreen.SAVED_TIMETABLE -> AppScreen.FOR_YOU
        AppScreen.SETTINGS_SECTION -> AppScreen.SETTINGS
        AppScreen.PLANNER, AppScreen.DEPARTURES, AppScreen.FOR_YOU -> null
    }

    /**
     * Phones show the bottom bar everywhere but in the train detail, which keeps its "Segui questo viaggio"
     * action at the bottom (as in the approved render); from 600 dp the side navigation is always visible.
     */
    fun showsNavigation(screen: AppScreen, widthClass: WidthClass): Boolean =
        widthClass != WidthClass.COMPACT || screen !in DETAIL_SCREENS

    /** Screens whose rail data is refreshed every 30 seconds while visible. */
    fun refreshesPeriodically(screen: AppScreen): Boolean =
        screen in setOf(AppScreen.RESULTS, AppScreen.NUMBER_RESULTS, AppScreen.DETAIL, AppScreen.DEPARTURES)

    /**
     * From 840 dp lists and detail sit side by side: the list screen kept on the left, or null for a
     * single pane. The detail opened from a list keeps that list visible.
     */
    fun listPaneOf(screen: AppScreen, detailParent: AppScreen, widthClass: WidthClass): AppScreen? {
        if (widthClass != WidthClass.EXPANDED) return null
        val list = when (screen) {
            AppScreen.DETAIL -> detailParent
            AppScreen.JOURNEY_DETAIL -> AppScreen.RESULTS
            else -> screen
        }
        return list.takeIf { it in TWO_PANE_LISTS }
    }

    fun targetOf(deepLink: BinarioDeepLink): NavigationTarget = when (deepLink) {
        BinarioDeepLink.Agenda -> NavigationTarget(AppScreen.AGENDA)
        BinarioDeepLink.ActiveRoute, BinarioDeepLink.NextDepartures, is BinarioDeepLink.WidgetRoute ->
            NavigationTarget(AppScreen.DEPARTURES, departuresSection = OperationsSection.TRAINS)
        is BinarioDeepLink.TrainSearch -> NavigationTarget(AppScreen.PLANNER, plannerMode = SearchMode.TRAIN)
        is BinarioDeepLink.FavouriteTrain -> NavigationTarget(AppScreen.NUMBER_RESULTS)
    }

    /** Where the results go back to: the planner, or "Per te" for a favourite train or a recent route opened there. */
    fun resultsParentOf(parent: AppScreen): AppScreen = parent.takeIf { it == AppScreen.FOR_YOU } ?: AppScreen.PLANNER

    /**
     * The screen a tab reopens on: the last one shown in it (the planner keeps its results, Per te its settings),
     * except a train detail, whose pinned run is gone once it is left; its parent list is shown instead.
     */
    fun resumeScreenOf(screen: AppScreen, detailParent: AppScreen): AppScreen =
        if (screen == AppScreen.DETAIL) detailParent.takeIf { it != AppScreen.DETAIL } ?: AppScreen.PLANNER else screen

    private val RESULT_SCREENS = setOf(AppScreen.RESULTS, AppScreen.NUMBER_RESULTS, AppScreen.JOURNEY_DETAIL)

    private val DETAIL_SCREENS = setOf(AppScreen.DETAIL, AppScreen.JOURNEY_DETAIL)

    private val TWO_PANE_LISTS = setOf(AppScreen.DEPARTURES, AppScreen.RESULTS, AppScreen.NUMBER_RESULTS)
}

/** The screen each tab reopens on, kept as "PLAN=RESULTS;FOR_YOU=SETTINGS" in the saved state. */
internal object TabScreens {
    fun of(encoded: String, tab: TopDestination): AppScreen? = encoded.split(';')
        .firstNotNullOfOrNull { entry ->
            entry.split('=').takeIf { it.size == 2 && it[0] == tab.name }?.let { parts -> AppScreen.entries.firstOrNull { it.name == parts[1] } }
        }

    fun with(encoded: String, tab: TopDestination, screen: AppScreen): String =
        (TopDestination.entries.mapNotNull { other -> (if (other == tab) screen else of(encoded, other))?.let { "${other.name}=${it.name}" } })
            .joinToString(";")
}
