package it.binario.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import it.binario.app.data.DataConfidence
import it.binario.app.data.DataResult
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.JourneyQuery
import it.binario.app.data.RailOperator
import it.binario.app.data.RouteSnapshot
import it.binario.app.data.SavedRoute
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.TrainDataSource
import it.binario.app.data.TrainService
import it.binario.app.data.TrainStop
import it.binario.app.data.repository.StationBoardAggregateService
import it.binario.app.data.repository.StationBoardRunDetailService
import it.binario.app.data.repository.TrainNumberSearchService
import java.io.File
import java.time.LocalTime
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Renders the real app UI with a fixed local demonstration catalogue and saves the screens compared with the
 * approved Binario 2.0 render. Not part of the normal device-test run: tools/run_android_ui_tests.sh runs it on
 * demand (BINARIO_CAPTURE_SCREENS=true) and prints the JPEG files into the CI log.
 *
 * Files: filesDir/design-screens/NN-name.jpg, the full window (status and navigation bars included) for
 * phones, the 900 x 600 dp frame for the wide layouts, scaled to 50 % and saved at JPEG quality 85.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DesignScreenshotTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()

    @Before fun prepare() {
        clearPreferences()
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
    }

    @After fun cleanup() {
        composeRule.runOnIdle { store.clear() }
        clearPreferences()
    }

    @Test fun a_phoneScreensInLightTheme() {
        val model = demoViewModel()
        showApp(model, AppThemeMode.LIGHT)
        capture("01-pianifica")
        composeRule.onNodeWithTag("planner-search").performClick()
        waitForTag("result-card")
        capture("02-risultati")
        composeRule.onAllNodesWithTag("result-card")[0].performClick()
        waitForTag("detail-timeline")
        capture("03-dettaglio-percorso")
        composeRule.onNodeWithTag("detail-tab-info").performClick()
        waitForTag("detail-info")
        capture("04-dettaglio-info")
        composeRule.onNodeWithTag("detail-tab-services").performClick()
        waitForTag("detail-services")
        capture("05-dettaglio-servizi")
        composeRule.onNodeWithTag("top-bar-back").performClick()
        waitForTag("route-results")
        composeRule.onNodeWithTag("nav-departures").performClick()
        waitForTag("departures-hero")
        capture("06-partenze")
        composeRule.onNodeWithTag("departures-section-stations").performClick()
        waitForTag("station-board-list")
        capture("07-partenze-stazioni")
        composeRule.onNodeWithTag("departures-section-trains").performClick()
        composeRule.onNodeWithTag("nav-for-you").performClick()
        waitForTag("for-you-screen")
        capture("08-per-te-tratte")
        composeRule.onNodeWithTag("for-you-tab-recent").performClick()
        composeRule.waitForIdle()
        capture("09-per-te-recenti")
        composeRule.onNodeWithTag("top-bar-settings").performClick()
        waitForTag("settings-screen")
        capture("10-impostazioni")
        composeRule.onNodeWithTag("settings-alerts").performClick()
        waitForTag("settings-section-alerts")
        capture("11-impostazioni-notifiche")
    }

    @Test fun b_departuresAndDetailInDarkTheme() {
        val model = demoViewModel()
        showApp(model, AppThemeMode.DARK)
        capture("12-pianifica-scuro")
        composeRule.onNodeWithTag("nav-departures").performClick()
        waitForTag("departures-hero")
        capture("13-partenze-scuro")
        composeRule.onNodeWithTag("departures-hero").performClick()
        waitForTag("detail-timeline")
        capture("14-dettaglio-scuro")
    }

    @Test fun c_tabletResultsWithMap() {
        val model = demoViewModel()
        showWideApp(model)
        composeRule.onNodeWithTag("planner-search").performClick()
        waitForTag("results-map-pane")
        // The map starts only on request (privacy policy), as a traveller would open it.
        composeRule.onNodeWithTag("results-map-show").performClick()
        // MapLibre draws the OpenFreeMap tiles outside Compose: give them time to arrive.
        SystemClock.sleep(MAP_SETTLE_MILLIS)
        captureNode("15-tablet-risultati", "wide-frame")
    }

    @Test fun d_tabletDeparturesWithDetail() {
        val model = demoViewModel()
        showWideApp(model)
        composeRule.onNodeWithTag("nav-departures").performClick()
        waitForTag("departures-hero")
        composeRule.onAllNodesWithTag("departures-hero")[0].performClick()
        waitForTag("detail-timeline")
        captureNode("16-tablet-partenze", "wide-frame")
    }

    private fun showApp(model: TrainViewModel, theme: AppThemeMode) {
        composeRule.setContent {
            BinarioTheme(theme) {
                BinarioApp(
                    viewModel = model,
                    themeMode = theme,
                    onThemeModeChange = {},
                    deepLinkRequest = null,
                    onDeepLinkConsumed = {},
                    onSystemBarsAppearance = ::applySystemBars
                )
            }
        }
        waitForTag("planner-screen")
    }

    /**
     * A tablet-sized window inside the phone emulator: a smaller density makes the 1080 px wide window 900 dp
     * wide (the two-pane layout from 840 dp) and the frame is 600 dp high, landscape like the render.
     */
    private fun showWideApp(model: TrainViewModel) {
        val widthPx = composeRule.activity.resources.displayMetrics.widthPixels
        val density = Density(density = widthPx / WIDE_WIDTH_DP, fontScale = 1f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                BinarioTheme(AppThemeMode.LIGHT) {
                    Box(
                        Modifier
                            .width(WIDE_WIDTH_DP.dp)
                            .height(WIDE_HEIGHT_DP.dp)
                            .consumeWindowInsets(WindowInsets.navigationBars)
                            .testTag("wide-frame")
                    ) {
                        BinarioApp(
                            viewModel = model,
                            themeMode = AppThemeMode.LIGHT,
                            onThemeModeChange = {},
                            deepLinkRequest = null,
                            onDeepLinkConsumed = {},
                            onSystemBarsAppearance = ::applySystemBars
                        )
                    }
                }
            }
        }
        waitForTag("navigation-rail")
    }

    private fun applySystemBars(lightStatusBar: Boolean, lightNavigationBar: Boolean) {
        val window = composeRule.activity.window
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightStatusBar
            isAppearanceLightNavigationBars = lightNavigationBar
        }
    }

    /** A view model with the stations of the curated catalogue, five saved routes and a local demo timetable. */
    private fun demoViewModel(): TrainViewModel {
        val model = TrainViewModel(
            application,
            false,
            StationBoardAggregateService(emptyList()),
            StationBoardRunDetailService(emptyList()),
            TrainNumberSearchService(emptyList()),
            DemoRouteSource()
        ).also { store.put("design-${System.nanoTime()}", it) }
        composeRule.waitUntil(CATALOGUE_TIMEOUT_MILLIS) { model.stations.size > 1_000 }
        composeRule.runOnIdle {
            fun station(name: String) = requireNotNull(model.stationFromName(name)) { "Missing station $name" }
            val roma = station("Roma Termini")
            val torino = station("Torino Porta Nuova")
            model.createRoute(station("Bologna Centrale"), station("Milano Centrale"), "Bologna – Milano")
            model.createRoute(station("Napoli Centrale"), roma, "Napoli – Roma")
            model.createRoute(station("Firenze S. M. Novella"), roma, "Firenze – Roma")
            model.createRoute(station("Milano Centrale"), station("Venezia Santa Lucia"), "Milano – Venezia")
            model.createRoute(roma, torino, "Roma – Torino")
            model.savedRoutes.first { it.name == "Roma – Torino" }.let(model::toggleRoutePin)
            model.searchRoute(station("Milano Centrale"), station("Venezia Santa Lucia"))
            model.searchRoute(roma, torino)
            model.activateRoute(model.savedRoutes.first { it.name == "Roma – Torino" })
        }
        composeRule.waitUntil(DATA_TIMEOUT_MILLIS) { model.homeServices().isNotEmpty() }
        return model
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(DATA_TIMEOUT_MILLIS) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        composeRule.waitForIdle()
    }

    /** The whole window, with the system bars, as the user sees it. */
    private fun capture(name: String) {
        composeRule.waitForIdle()
        SystemClock.sleep(SETTLE_MILLIS)
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: composeRule.onRoot().captureToImage().asAndroidBitmap()
        save(name, screen)
    }

    private fun captureNode(name: String, tag: String) {
        composeRule.waitForIdle()
        save(name, composeRule.onNodeWithTag(tag).captureToImage().asAndroidBitmap())
    }

    private fun save(name: String, bitmap: Bitmap) {
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val scaled = Bitmap.createScaledBitmap(software, software.width / 2, software.height / 2, true)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "design-screens").apply { mkdirs() }
        File(directory, "$name.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    private fun clearPreferences() {
        listOf(
            "binario_routes_v3", "binario_snapshot_cache", "binario_personal_travel_v1", "binario_station_preferences_v1",
            "binario_custom_stations_v1"
        ).forEach { application.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    /**
     * The departures of the render (Roma Termini → Torino Porta Nuova), as a demonstration catalogue: scheduled
     * times only, no platform, no delay, all direct. The UI labels it "Catalogo dimostrativo locale — non live".
     */
    private class DemoRouteSource : TrainDataSource {
        override suspend fun loadRoute(route: SavedRoute, direction: Direction): DataResult =
            loadRoute(route, direction, JourneyQuery.now())

        override suspend fun loadRoute(route: SavedRoute, direction: Direction, query: JourneyQuery): DataResult {
            val origin = route.origin(direction).name
            val destination = route.destination(direction).name
            fun service(number: String, category: TrainCategory, operator: RailOperator, vararg stops: Pair<String, String>): TrainService {
                val all = stops.toList()
                val departure = all.first().second
                return TrainService(
                    id = "demo:$number", number = number, operator = operator, trainCategory = category,
                    origin = origin, destination = destination,
                    scheduledDeparture = departure, expectedDeparture = departure, platform = null,
                    delayMinutes = 0, status = ServiceStatus.ON_TIME,
                    stops = all.map { (station, time) -> TrainStop(station, time, time, null, 0, false, operationalStatusVerified = false) },
                    operationalStatusVerified = false,
                    scheduledDepartureDateTime = query.serviceDate.atTime(LocalTime.parse(departure))
                )
            }
            val services = listOf(
                service("9558", TrainCategory.FRECCE, RailOperator.TRENITALIA_AV,
                    origin to "17:40", "Firenze S. M. Novella" to "18:55", "Bologna Centrale" to "20:00", destination to "22:05"),
                service("597", TrainCategory.INTERCITY, RailOperator.TRENITALIA_LONG_DISTANCE,
                    origin to "18:20", "Pisa Centrale" to "20:15", "Genova Piazza Principe" to "21:35", destination to "22:50"),
                service("8810", TrainCategory.FRECCE, RailOperator.TRENITALIA_AV,
                    origin to "19:05", "Firenze S. M. Novella" to "20:25", "Bologna Centrale" to "21:30", destination to "23:35"),
                service("2018", TrainCategory.REGIONALE_VELOCE, RailOperator.TRENITALIA_REGIONALE,
                    origin to "19:50", "Civitavecchia" to "20:55", "Grosseto" to "22:05", destination to "00:20")
            )
            val now = System.currentTimeMillis()
            return DataResult.Success(
                RouteSnapshot(
                    route = route,
                    direction = direction,
                    services = services,
                    providerName = "Catalogo dimostrativo locale",
                    retrievedAtEpochMillis = now,
                    expiresAtEpochMillis = now + 3_600_000,
                    freshness = Freshness.FRESH,
                    confidence = DataConfidence.DEMONSTRATION
                )
            )
        }
    }

    private companion object {
        const val WIDE_WIDTH_DP = 900f
        const val WIDE_HEIGHT_DP = 600f
        const val CATALOGUE_TIMEOUT_MILLIS = 30_000L
        const val DATA_TIMEOUT_MILLIS = 10_000L
        const val SETTLE_MILLIS = 400L
        const val MAP_SETTLE_MILLIS = 6_000L
    }
}
