package it.binario.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
import it.binario.app.data.core.Completeness
import it.binario.app.data.core.JourneyId
import it.binario.app.data.core.JourneyLeg
import it.binario.app.data.core.JourneyOption
import it.binario.app.data.core.LegMode
import it.binario.app.data.core.ServiceKind
import it.binario.app.data.core.SnapshotMeta
import it.binario.app.data.core.SourceId
import it.binario.app.data.core.StationId
import it.binario.app.data.core.TrainRunId
import it.binario.app.data.provider.AccessMode
import it.binario.app.data.provider.CoverageDescriptor
import it.binario.app.data.provider.JourneyProvider
import it.binario.app.data.provider.JourneySearchRequest
import it.binario.app.data.provider.ProviderCapability
import it.binario.app.data.provider.ProviderDescriptor
import it.binario.app.data.provider.ProviderResponseMeta
import it.binario.app.data.provider.ProviderResult
import it.binario.app.data.provider.RatePolicy
import it.binario.app.data.repository.JourneySearchService
import it.binario.app.data.repository.StationBoardAggregateService
import it.binario.app.data.repository.StationBoardRunDetailService
import it.binario.app.data.repository.TrainNumberSearchService
import it.binario.app.journeys.JourneySearchViewModel
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
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
 * The journeys with changes come from a fixed fixture too, so the results mix "Diretto", "1 cambio" and "2 cambi".
 *
 * Files: filesDir/design-screens/NN-name.jpg, the full window (status and navigation bars included) for
 * phones, the 900 x 600 dp frame for the wide layouts, scaled to 50 % and saved at JPEG quality 85.
 *
 * The -ar and -ja screens render the same UI in Arabic (right to left) and Japanese through a locale override of
 * the composition ([WithLocale]), whatever the emulator's language.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DesignScreenshotTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    /** Journeys with changes from a fixed local fixture, never the network or the offline packs. */
    private val journeyModel: JourneySearchViewModel by lazy {
        JourneySearchViewModel(JourneySearchService(listOf(DemoJourneyProvider()))).also { store.put("design-journeys", it) }
    }

    @Before fun prepare() {
        clearPreferences()
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
    }

    @After fun cleanup() {
        composeRule.runOnIdle { store.clear() }
        clearPreferences()
        Locale.setDefault(deviceLocale)
    }

    private val deviceLocale: Locale = Locale.getDefault()

    @Test fun a_phoneScreensInLightTheme() {
        val model = demoViewModel()
        showApp(model, AppThemeMode.LIGHT)
        capture("01-pianifica")
        click("planner-search")
        waitForTag("result-card")
        waitForTag("journey-result-card")
        capture("02-risultati")
        clickFirst("result-card")
        waitForTag("detail-timeline")
        capture("03-dettaglio-percorso")
        click("detail-tab-info")
        waitForTag("detail-info")
        capture("04-dettaglio-info")
        click("detail-tab-services")
        waitForTag("detail-services")
        capture("05-dettaglio-servizi")
        click("top-bar-back")
        waitForTag("route-results")
        click("nav-departures")
        waitForTag("departures-hero")
        capture("06-partenze")
        click("departures-section-stations")
        waitForTag("station-board-list")
        capture("07-partenze-stazioni")
        click("departures-section-trains")
        click("nav-for-you")
        waitForTag("for-you-screen")
        capture("08-per-te-tratte")
        click("for-you-tab-recent")
        composeRule.waitForIdle()
        capture("09-per-te-recenti")
        click("top-bar-settings")
        waitForTag("settings-screen")
        waitForTag("settings-suggest-station")
        capture("10-impostazioni")
        click("settings-alerts")
        waitForTag("settings-section-alerts")
        capture("11-impostazioni-notifiche")
    }

    @Test fun b_departuresAndDetailInDarkTheme() {
        val model = demoViewModel()
        showApp(model, AppThemeMode.DARK)
        capture("12-pianifica-scuro")
        click("nav-departures")
        waitForTag("departures-hero")
        capture("13-partenze-scuro")
        click("departures-hero")
        waitForTag("detail-timeline")
        capture("14-dettaglio-scuro")
    }

    @Test fun c_tabletResultsWithMap() {
        val model = demoViewModel()
        showWideApp(model)
        click("planner-search")
        waitForTag("results-map-pane")
        // The map starts only on request (privacy policy), as a traveller would open it.
        click("results-map-show")
        // MapLibre draws the OpenFreeMap tiles outside Compose: give them time to arrive.
        SystemClock.sleep(MAP_SETTLE_MILLIS)
        captureNode("15-tablet-risultati", "wide-frame")
    }

    @Test fun d_tabletDeparturesWithDetail() {
        val model = demoViewModel()
        showWideApp(model)
        click("nav-departures")
        waitForTag("departures-hero")
        clickFirst("departures-hero")
        waitForTag("detail-timeline")
        captureNode("16-tablet-partenze", "wide-frame")
    }

    /**
     * The results of the render with the journeys of [DemoJourneyProvider] merged by departure ("Diretto",
     * "1 cambio", "Diretto", "2 cambi"; the direct 17:40 journey is the 9558 already listed and appears once), then
     * the detail of the journey with one change.
     */
    @Test fun e_resultsWithChangesAndJourneyDetail() {
        val model = demoViewModel()
        showApp(model, AppThemeMode.LIGHT)
        click("planner-search")
        waitForTag("result-card")
        waitForTag("journey-result-card")
        // The chips at the top, then the first four solutions.
        composeRule.onNodeWithTag("route-results").performScrollToIndex(1)
        capture("17-risultati-con-cambi")
        clickFirst("journey-result-card")
        waitForTag("journey-transfer")
        capture("18-viaggio-con-cambi")
    }

    /**
     * The planner and the results in Arabic: right-to-left layout, Arabic plurals, the routes and time ranges kept as
     * left-to-right units ("17:40 → 22:05") and the start-side timeline.
     */
    @Test fun f_arabicRightToLeft() {
        val model = demoViewModel()
        showApp(model, AppThemeMode.LIGHT, Locale.forLanguageTag("ar"))
        capture("19-pianifica-ar")
        click("planner-search")
        waitForTag("result-card")
        waitForTag("journey-result-card")
        capture("20-risultati-ar")
    }

    /** The planner in Japanese: CJK glyphs from the system fonts and the short labels of the chips. */
    @Test fun g_japanese() {
        val model = demoViewModel()
        showApp(model, AppThemeMode.LIGHT, Locale.JAPANESE)
        capture("21-pianifica-ja")
    }

    private fun showApp(model: TrainViewModel, theme: AppThemeMode, locale: Locale? = null) {
        locale?.let(Locale::setDefault)
        composeRule.setContent {
            WithLocale(locale) {
                BinarioTheme(theme) {
                    BinarioApp(
                        viewModel = model,
                        themeMode = theme,
                        onThemeModeChange = {},
                        deepLinkRequest = null,
                        onDeepLinkConsumed = {},
                        onSystemBarsAppearance = ::applySystemBars,
                        journeyViewModel = journeyModel
                    )
                }
            }
        }
        waitForTag("planner-screen")
    }

    /**
     * [content] in [locale] without changing the emulator's language: resources from a configuration context of that
     * locale (strings, plurals), its layout direction, and [Locale.getDefault] set by the caller for dates and
     * numbers. The context wrapper keeps the activity as its base, so code looking for the activity still finds it.
     * Texts the view model resolves itself through the application context stay in the emulator's language.
     */
    @Composable
    private fun WithLocale(locale: Locale?, content: @Composable () -> Unit) {
        if (locale == null) {
            content()
            return
        }
        val context = LocalContext.current
        val configuration = remember(locale) {
            Configuration(context.resources.configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
        }
        val localized = remember(locale) { LocalizedContext(context, context.createConfigurationContext(configuration).resources) }
        CompositionLocalProvider(
            LocalContext provides localized,
            LocalConfiguration provides configuration,
            LocalResources provides localized.resources,
            LocalLayoutDirection provides if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr,
            content = content
        )
    }

    private class LocalizedContext(base: Context, private val localizedResources: Resources) : ContextWrapper(base) {
        override fun getResources(): Resources = localizedResources
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
                            onSystemBarsAppearance = ::applySystemBars,
                            journeyViewModel = journeyModel
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

    /**
     * Runs the node's click action instead of injecting a touch: a control can sit below the visible part of
     * a list or under the bottom navigation, and the screenshot must not depend on the emulator's height.
     */
    private fun click(tag: String) {
        composeRule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private fun clickFirst(tag: String) {
        composeRule.onAllNodesWithTag(tag)[0].performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
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

    /**
     * Journeys for the same route as [DemoRouteSource], labelled with the demonstration catalogue as source: the
     * direct 17:40 train (which the results show once, as the direct train), one option with one change at Milano
     * Centrale and one with two changes arriving after midnight. Scheduled times only.
     */
    private class DemoJourneyProvider : JourneyProvider {
        override val descriptor = ProviderDescriptor(
            SourceId("demo-journeys"), "Catalogo dimostrativo locale", setOf(ProviderCapability.JOURNEY_SEARCH),
            CoverageDescriptor(description = "Fixture of the design screenshots"), AccessMode.OPEN_DIRECT,
            RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofMinutes(30)), 1
        )

        override suspend fun searchJourneys(request: JourneySearchRequest): ProviderResult<List<JourneyOption>> {
            val day = request.departureAt.toLocalDate()
            val zone = ZoneId.of("Europe/Rome")
            val origin = request.originEndpoint?.name ?: "Roma Termini"
            val destination = request.destinationEndpoint?.name ?: "Torino Porta Nuova"
            fun at(time: String, plusDays: Long = 0) = day.plusDays(plusDays).atTime(LocalTime.parse(time)).atZone(zone)
            fun leg(line: String, number: String, kind: ServiceKind, from: Pair<String, String>, to: Pair<String, String>,
                    departure: java.time.ZonedDateTime, arrival: java.time.ZonedDateTime) = JourneyLeg(
                mode = LegMode.TRAIN, runId = TrainRunId("demo:$number"), origin = StationId(from.first), destination = StationId(to.first),
                scheduledDeparture = departure, scheduledArrival = arrival, publicNumber = number, lineLabel = line,
                originLabel = from.second, destinationLabel = to.second, serviceKind = kind, operatorName = "Trenitalia"
            )
            val start = request.origin.value to origin
            val end = request.destination.value to destination
            val milano = "demo:milano" to "Milano Centrale"
            val firenze = "demo:firenze" to "Firenze S. M. Novella"
            val bologna = "demo:bologna" to "Bologna Centrale"
            val meta = SnapshotMeta(1, Instant.now(), null, it.binario.app.data.core.Freshness.FRESH, Completeness.STATIC_ONLY, emptySet())
            fun option(id: String, vararg legs: JourneyLeg) = JourneyOption(
                JourneyId(id), request.origin, request.destination, legs.first().scheduledDeparture, legs.last().scheduledArrival, legs.toList(), meta
            )
            val options = listOf(
                option("demo-direct", leg("FR", "9558", ServiceKind.HIGH_SPEED, start, end, at("17:40"), at("22:05"))),
                option(
                    "demo-one-change",
                    leg("FR", "9620", ServiceKind.HIGH_SPEED, start, milano, at("18:00"), at("21:10")),
                    leg("RV", "2041", ServiceKind.REGIONAL, milano, end, at("21:25"), at("22:50"))
                ),
                option(
                    "demo-two-changes",
                    leg("RV", "2310", ServiceKind.REGIONAL, start, firenze, at("18:35"), at("20:20")),
                    leg("R", "6420", ServiceKind.REGIONAL, firenze, bologna, at("20:35"), at("21:55")),
                    leg("IC", "35", ServiceKind.INTERCITY, bologna, end, at("22:10"), at("00:40", plusDays = 1))
                )
            )
            return ProviderResult.Success(options, ProviderResponseMeta(descriptor.id, Instant.now()))
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
