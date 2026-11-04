package it.binario.app.maps

import it.binario.app.TestAssets
import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.core.StationId
import it.binario.app.data.journey.EmbeddedGtfsJourneyProvider
import it.binario.app.data.journey.ScheduledRunRequest
import it.binario.app.data.journey.ScheduledRunScope
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map of trains in motion on the bundled packs: how many trains a busy area has, and what reading them costs. */
class LiveTrainMapRealPacksTest {
    private val openAsset = { path: String -> TestAssets.open(path) }
    private val stations: List<Station> by lazy { EmbeddedCuratedRailStationCatalog(openAsset).load() }

    /** A Tuesday inside the validity of every bundled pack. */
    private val tuesday = LocalDate.of(2026, 11, 10)

    private fun station(name: String): Station = stations.single { it.name == name }

    private fun timed(block: suspend () -> Unit): Long = runBlocking {
        val started = System.nanoTime()
        block()
        (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun `trains in motion around Milano Centrale and on Milano Centrale - Bologna on a weekday morning`() = runBlocking {
        val provider = EmbeddedGtfsJourneyProvider.create(openAsset)
        val now = LocalDateTime.of(tuesday, LocalTime.of(8, 20))
        val milano = station("Milano Centrale").mapPoint()!!
        val area = LiveTrainAreas.around(milano)
        val read = LiveTrainAreas.readArea(area)
        val request = ScheduledRunRequest(LiveTrainRefresh.slotStart(now), ScheduledRunScope.Area(read.north, read.south, read.east, read.west))
        var runs = emptyList<it.binario.app.data.journey.ScheduledRailRun>()
        val cold = timed { runs = provider.scheduledRailRuns(request) }
        val warm = timed { runs = provider.scheduledRailRuns(request) }
        var selection = LiveTrainSelector.select(runs, emptyList(), now, area, milano)
        val select = timed { selection = LiveTrainSelector.select(runs, emptyList(), now, area, milano) }

        val route = ScheduledRunScope.Route(StationId(station("Milano Centrale").id), null, StationId(station("Bologna Centrale").id), null)
        val routeRuns = provider.scheduledRailRuns(ScheduledRunRequest(LiveTrainRefresh.slotStart(now), route))
        val bologna = station("Bologna Centrale").mapPoint()!!
        val routeSelection = LiveTrainSelector.select(routeRuns, emptyList(), now, LiveTrainAreas.routeArea(milano, bologna), milano)
        println(
            "LIVE_MAP_PERF areaRuns=${runs.size} areaPlaced=${selection.inBounds} areaShown=${selection.trains.size} " +
                "coldMs=$cold warmMs=$warm selectMs=$select routeRuns=${routeRuns.size} routePlaced=${routeSelection.inBounds} " +
                "sample=${selection.trains.take(5).map { LiveTrainLabels.marker(it) + " " + it.destination }}"
        )
        // A weekday morning around Milan has dozens of trains under way; the map never draws more than its cap.
        assertTrue(selection.inBounds >= 10)
        assertTrue(selection.trains.size <= LiveTrainSelector.MAX_TRAINS)
        assertTrue(selection.trains.all { area.contains(it.point) })
        assertTrue(routeSelection.inBounds >= 1)
        assertTrue(routeRuns.all { run -> run.stops.any { it.name == "Milano Centrale" } && run.stops.any { it.name == "Bologna Centrale" } })
    }
}
