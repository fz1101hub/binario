package it.binario.app

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import it.binario.app.data.*
import it.binario.app.data.core.SourceId
import it.binario.app.data.provider.*
import it.binario.app.data.repository.*
import java.time.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrainNumberSearchInteractionTest {
    @get:Rule val composeRule = createComposeRule()
    private val store = ViewModelStore()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val calls = AtomicInteger()
    private val descriptor = ProviderDescriptor(SourceId("global-test"), "Global test", setOf(ProviderCapability.TRAIN_RUN_REALTIME),
        CoverageDescriptor(description = "Synthetic fixture"), AccessMode.OPEN_DIRECT, RatePolicy(Duration.ZERO, defaultTimeToLive = Duration.ofSeconds(30)), 1)
    @Before fun resetRoutes() { application.getSharedPreferences("binario_routes_v3", Context.MODE_PRIVATE).edit().clear().commit() }
    @After fun cleanup() { composeRule.runOnIdle { store.clear() }; resetRoutes() }
    private fun match(query: TrainNumberQuery): TrainNumberMatch {
        val service = TrainService("global:${query.number}", query.number, RailOperator.OTHER, TrainCategory.REGIONALE,
            "Torino Test", "Venezia Test", "08:00", "08:06", "2", 6, ServiceStatus.DELAYED,
            listOf(TrainStop("Torino Test", "08:00", "08:06", "2", 6, true)), operationalStatusVerified = true,
            scheduledDepartureDateTime = query.date.atTime(8, 0), officialDelayMinutes = 6)
        return TrainNumberMatch.create(service, "Global test", DataConfidence.OPERATOR_PRIMARY, System.currentTimeMillis(), System.currentTimeMillis() + 30_000)
    }
    private fun viewModel(block: suspend (TrainNumberQuery) -> ProviderResult<List<TrainNumberMatch>> = { query ->
        calls.incrementAndGet()
        ProviderResult.Success(listOf(match(query)), ProviderResponseMeta(descriptor.id, Instant.now()))
    }): TrainViewModel {
        val provider = object : TrainNumberSearchProvider {
            override val descriptor = this@TrainNumberSearchInteractionTest.descriptor
            override suspend fun searchByNumber(query: TrainNumberQuery) = block(query)
        }
        return TrainViewModel(application, false, StationBoardAggregateService(emptyList()), StationBoardRunDetailService(emptyList()),
            TrainNumberSearchService(listOf(provider))).also { store.put("number", it) }
    }
    @Test fun searchIsEnabledWithoutARouteAndTappingResultSelectsFullRun() {
        val model = viewModel()
        composeRule.setContent { BinarioTheme { Column {
            TrainNumberSearchForm(model.trainNumberQuery, model::updateTrainNumberQuery) { model.searchTrainNumber() }
            TrainNumberResultsScreen(Modifier, model, model::openTrainNumberMatch)
        } } }
        composeRule.runOnIdle { assertFalse(model.hasActiveRoute) }
        composeRule.onNode(hasSetTextAction()).performTextInput("9920")
        composeRule.onNodeWithText("Cerca treno").assertIsEnabled().performClick()
        composeRule.waitUntil(5_000) { model.trainNumberSearchState == RefreshState.SUCCESS }
        val row = hasContentDescription("9920", substring = true) and hasClickAction()
        composeRule.onNodeWithTag("train-number-results").performScrollToNode(row)
        composeRule.onNode(row).performClick()
        composeRule.runOnIdle {
            assertEquals("9920", model.selectedService?.number)
            assertEquals("Torino Test", model.selectedService?.origin)
            assertEquals("08:00", model.selectedService?.scheduledDeparture)
            assertEquals(6, model.selectedService?.officialDelayMinutes)
            assertEquals("Global test", model.currentProviderLabel())
            assertFalse(model.hasActiveRoute)
            model.refresh()
        }
        composeRule.waitUntil(5_000) { calls.get() == 2 && model.trainNumberSearchState == RefreshState.SUCCESS }
        composeRule.runOnIdle { assertEquals("9920", model.selectedService?.number) }
    }
    @Test fun changingNumberCancelsPreviousLookupAndCannotSelectAnUnrelatedRun() {
        val started = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val model = viewModel { query ->
            if (query.number == "111") {
                started.set(true)
                try { awaitCancellation() } finally { cancelled.set(true) }
            }
            ProviderResult.Success(listOf(match(query)), ProviderResponseMeta(descriptor.id, Instant.now()))
        }
        composeRule.setContent { BinarioTheme { TrainNumberResultsScreen(Modifier, model, model::openTrainNumberMatch) } }
        composeRule.runOnIdle { model.updateTrainNumberQuery("111"); model.searchTrainNumber() }
        composeRule.waitUntil(5_000) { started.get() }
        composeRule.runOnIdle { model.updateTrainNumberQuery("222"); model.searchTrainNumber() }
        composeRule.waitUntil(5_000) { model.trainNumberSearchState == RefreshState.SUCCESS }
        composeRule.runOnIdle { assertTrue(cancelled.get()); assertEquals("222", model.trainNumberMatches.single().service.number) }
    }
}
