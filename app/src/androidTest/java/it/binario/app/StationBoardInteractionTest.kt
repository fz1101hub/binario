package it.binario.app

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import it.binario.app.data.Station
import it.binario.app.data.TrainStop
import it.binario.app.data.core.*
import it.binario.app.data.provider.*
import it.binario.app.data.repository.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StationBoardInteractionTest {
    @get:Rule val composeRule = createComposeRule()
    private val store = ViewModelStore()
    private val now = Instant.parse("2026-10-18T07:00:00Z")
    private val zone = ZoneId.of("Europe/Rome")
    private val station = Station("board-test-appiano", "Stazione test A", "Roma")
    private val descriptor = ProviderDescriptor(SourceId("test-board"), "Test board", setOf(ProviderCapability.STATION_BOARD),
        CoverageDescriptor(description = "Synthetic fixture"), AccessMode.OPEN_DIRECT, RatePolicy(Duration.ofSeconds(30), defaultTimeToLive = Duration.ofMinutes(2)), 1)
    private val source = SourceRef(descriptor.id, fetchedAt = now, expiresAt = now.plusSeconds(120),
        confidence = Confidence.OPERATOR_PRIMARY, traceId = "test")
    private val entry = StationBoardEntry(TrainRunId("test-12345"), now.plusSeconds(1800).atZone(zone),
        expectedTime = SourcedValue(now.plusSeconds(2160).atZone(zone), source), destinationOrOrigin = StationId("test-destination"),
        destinationOrOriginLabel = "Roma Ostiense", publicNumber = "12345", status = SourcedValue(RunStatus.DELAYED, source))

    @After fun clearViewModels() { composeRule.runOnIdle { store.clear() } }

    private fun board(request: StationBoardRequest) = ProviderResult.Success(
        StationBoard(request.stationId, request.kind, request.from, request.to, listOf(entry),
            SnapshotMeta(1, now, now.plusSeconds(120), Freshness.FRESH, Completeness.COMPLETE, setOf(source))),
        ProviderResponseMeta(descriptor.id, now))

    private fun viewModel(providers: List<StationBoardProvider>, detailProviders: List<StationBoardRunDetailProvider>): TrainViewModel {
        return TrainViewModel(ApplicationProvider.getApplicationContext<Application>(), false,
            StationBoardAggregateService(providers, Clock.fixed(now, zone)), StationBoardRunDetailService(detailProviders))
            .also { store.put("station-test", it) }
    }

    @Test fun aTrainCanOpenItsFullDetailsWhileAnotherBoardSourceIsStillLoading() {
        val fast = object : StationBoardProvider {
            override val descriptor = this@StationBoardInteractionTest.descriptor
            override suspend fun getStationBoard(request: StationBoardRequest) = board(request)
        }
        val slow = object : StationBoardProvider by fast {
            override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> = awaitCancellation()
        }
        val detail = object : StationBoardRunDetailProvider {
            override val descriptor = this@StationBoardInteractionTest.descriptor
            override suspend fun getStationBoardRun(station: Station, kind: BoardKind, entry: StationBoardEntry) = ProviderResult.Success(
                StationBoardRunDetails(entry, "Viterbo", "Roma Ostiense",
                    listOf(TrainStop("Viterbo", "08:30", "08:36", null, 6, true, operationalStatusVerified = true),
                        TrainStop("Roma Ostiense", "10:00", "10:06", null, 6, false, operationalStatusVerified = true)), "Test source", now),
                ProviderResponseMeta(descriptor.id, now))
        }
        val model = viewModel(listOf(fast, slow), listOf(detail))
        composeRule.setContent { BinarioTheme { StationBoardScreen(Modifier, model) } }
        composeRule.runOnIdle { model.openStationBoard(station) }
        composeRule.waitUntil(5_000) { model.stationBoard?.entries?.isNotEmpty() == true }
        assertEquals(RefreshState.LOADING, model.stationBoardRefreshState)
        val train = hasContentDescription("Treno 12345.", substring = true) and hasClickAction()
        composeRule.onNodeWithTag("station-board-list").performScrollToNode(train)
        composeRule.onNode(train).performClick()
        composeRule.onNodeWithText("Dettagli corsa").fetchSemanticsNode()
        composeRule.waitUntil(5_000) { model.stationBoardRunRefreshState == RefreshState.SUCCESS }
        composeRule.onNodeWithTag("station-run-details").performScrollToNode(hasText("Fermate"))
        composeRule.onNodeWithText("Fermate").fetchSemanticsNode()
        assertEquals("12345", model.stationBoardRunDetails?.entry?.publicNumber)
        composeRule.onNodeWithTag("station-run-details").performScrollToNode(hasText("Chiudi"))
        composeRule.onNodeWithText("Chiudi").performClick()
        composeRule.runOnIdle { assertEquals(null, model.selectedBoardEntry) }
    }

    @Test fun changingStationCancelsTheOldBoardAndOnlyDisplaysTheNewOne() {
        val started = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val provider = object : StationBoardProvider {
            override val descriptor = this@StationBoardInteractionTest.descriptor
            override suspend fun getStationBoard(request: StationBoardRequest): ProviderResult<StationBoard> {
                if (request.stationName == station.name) {
                    started.set(true)
                    try { awaitCancellation() } finally { cancelled.set(true) }
                }
                return board(request)
            }
        }
        val model = viewModel(listOf(provider), emptyList())
        composeRule.setContent { BinarioTheme { StationBoardScreen(Modifier, model) } }
        composeRule.runOnIdle { model.openStationBoard(station) }
        composeRule.waitUntil(5_000) { started.get() }
        val second = station.copy(id = "board-test-b", name = "Stazione test B")
        composeRule.runOnIdle { model.selectStationBoardStation(second) }
        composeRule.waitUntil(5_000) { model.stationBoardRefreshState == RefreshState.SUCCESS }
        composeRule.runOnIdle {
            assertEquals(second.name, model.stationBoard?.station?.name)
            assertTrue(cancelled.get())
        }
    }
}
