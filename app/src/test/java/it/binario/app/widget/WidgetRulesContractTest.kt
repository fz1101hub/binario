package it.binario.app.widget

import it.binario.app.ServiceMonitor
import it.binario.app.data.Direction
import it.binario.app.data.Freshness
import it.binario.app.data.RailOperator
import it.binario.app.data.ServiceStatus
import it.binario.app.data.TrainCategory
import it.binario.app.data.core.RunStatus
import it.binario.app.data.core.ServiceKind
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * contracts/shared-rules/v1/widgets-v1.json: the rules of the three home-screen widgets, as the iPhone's
 * WidgetRulesContractTests read them (BinarioKit's WidgetRules.swift, used by its WidgetKit extension).
 */
class WidgetRulesContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/widgets-v1.json").readText(Charsets.UTF_8))
    private val rome = ZoneId.of("Europe/Rome")

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    private fun at(text: String): ZonedDateTime = LocalDateTime.parse(text).atZone(rome)
    private fun JSONObject.atOrNull(key: String): ZonedDateTime? = if (isNull(key)) null else at(getString(key))
    private fun JSONObject.millis(key: String): Long? = atOrNull(key)?.toInstant()?.toEpochMilli()
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.flag(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)
    private fun JSONObject.direction(key: String): Direction? = text(key)?.let { Direction.valueOf(it) }
    private fun groups(array: JSONArray): Set<TrainTypeGroup> = array.strings().map { TrainTypeGroup.valueOf(it) }.toSet()

    private fun settings(name: String): AutoRefreshSettings {
        val json = rules.getJSONObject("settings").getJSONObject(name)
        val days = json.getJSONArray("days")
        val windows = json.getJSONArray("windows")
        return AutoRefreshSettings(
            mode = AutoRefreshMode.valueOf(json.getString("mode")),
            interval = requireNotNull(RefreshInterval.of(json.getInt("intervalMinutes"))),
            days = (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
            windows = (0 until windows.length()).map { index ->
                val pair = windows.getJSONArray(index)
                TimeWindow(LocalTime.parse(pair.getString(0)), LocalTime.parse(pair.getString(1)))
            }
        )
    }

    @Test fun `automatic refresh windows`() {
        for (case in rules.getJSONArray("isActiveAt").objects()) {
            assertEquals(case.toString(), case.getBoolean("expected"), settings(case.getString("settings")).isActiveAt(at(case.getString("at"))))
        }
        for (case in rules.getJSONArray("nextDueAt").objects()) {
            val now = at(case.getString("now"))
            val due = settings(case.getString("settings")).nextDueAt(case.millis("lastRefresh") ?: 0L, now)
            assertEquals(case.toString(), case.atOrNull("expected")?.toInstant(), due?.toInstant())
        }
    }

    @Test fun `the automatic return direction`() {
        val direction = rules.getJSONObject("direction")
        for (case in direction.getJSONArray("effective").objects()) {
            val effective = WidgetDirectionSchedule.effectiveDirection(
                returnFrom = case.text("returnFrom")?.let { LocalTime.parse(it) },
                storedDirection = requireNotNull(case.direction("stored")),
                manualDirection = case.direction("manual"),
                manualUntilEpochMillis = case.millis("manualUntil") ?: 0L,
                now = at(case.getString("now"))
            )
            assertEquals(case.toString(), case.direction("expected"), effective)
        }
        for (case in direction.getJSONArray("nextBoundary").objects()) {
            val boundary = WidgetDirectionSchedule.nextBoundary(LocalTime.parse(case.getString("returnFrom")), at(case.getString("now")))
            assertEquals(case.toString(), at(case.getString("expected")).toInstant(), boundary.toInstant())
        }
    }

    @Test fun `the train type group of a board row`() {
        for (case in rules.getJSONArray("typeGroups").objects()) {
            val group = WidgetTrainTypes.groupOf(
                ServiceKind.UNKNOWN,
                case.text("operator"),
                case.text("line"),
                if (case.getBoolean("replacementBus")) RunStatus.REPLACED_BY_BUS else null
            )
            assertEquals(case.toString(), case.text("expected")?.let { TrainTypeGroup.valueOf(it) }, group)
        }
    }

    @Test fun `the runs the route widget lists`() {
        val routeRows = rules.getJSONObject("routeRows")
        val services = routeRows.getJSONArray("rows").objects().map { row ->
            WidgetService(
                number = row.getString("id"),
                operator = RailOperator.OTHER,
                category = TrainCategory.OTHER,
                destination = "Destinazione",
                expectedDeparture = "00:00",
                status = if (row.getBoolean("cancelled")) ServiceStatus.CANCELLED else ServiceStatus.ON_TIME,
                delayMinutes = 0,
                platform = null,
                operationalStatusVerified = true,
                departureEpochMillis = row.millis("departure"),
                arrivalEpochMillis = row.millis("arrival"),
                typeGroup = row.text("group")?.let { TrainTypeGroup.valueOf(it) }
            )
        }
        for (case in routeRows.getJSONArray("cases").objects()) {
            val settings = RouteWidgetSettings(
                excludedTypes = groups(case.getJSONArray("excluded")),
                untilArrival = case.getBoolean("untilArrival"),
                timeRange = RouteTimeRange.valueOf(case.getString("timeRange"))
            )
            val visible = RouteWidgetPresenter.visible(services, requireNotNull(case.millis("now")), settings)
            val pairs = case.getJSONArray("expected")
            val expected = (0 until pairs.length()).map { index ->
                val pair = pairs.getJSONArray(index)
                pair.getString(0) to pair.getBoolean(1)
            }
            assertEquals(case.getString("name"), expected, visible.map { row -> row.service.number to row.inTransit })
        }
    }

    @Test fun `the departures the station widget lists`() {
        val stationRows = rules.getJSONObject("stationRows")
        val departures = stationRows.getJSONArray("departures").objects().map { row ->
            StationWidgetDeparture(
                scheduledEpochMillis = requireNotNull(row.millis("scheduled")),
                expectedEpochMillis = row.millis("expected"),
                number = row.getString("id"),
                operator = null,
                destination = "Destinazione",
                platform = null,
                status = row.text("status")?.let { RunStatus.valueOf(it) },
                typeGroup = row.text("group")?.let { TrainTypeGroup.valueOf(it) }
            )
        }
        for (case in stationRows.getJSONArray("cases").objects()) {
            val upcoming = StationWidgetPresenter.upcoming(
                departures,
                Freshness.valueOf(case.getString("freshness")),
                requireNotNull(case.millis("now")),
                case.getInt("limit"),
                groups(case.getJSONArray("excluded"))
            )
            assertEquals(case.getString("name"), case.getJSONArray("expected").strings(), upcoming.map { row -> row.number })
        }
    }

    @Test fun `when a favourite train is running and which day a refresh asks for`() {
        val trains = rules.getJSONObject("trains")
        for (case in trains.getJSONArray("isRunning").objects()) {
            val json = if (case.isNull("status")) null else case.getJSONObject("status")
            val status = json?.let {
                FavouriteTrainStatus(
                    number = "2153",
                    retrievedAt = 1L,
                    scheduledDepartureEpochMillis = it.millis("departure"),
                    arrivalEpochMillis = it.millis("arrival"),
                    cancelled = it.getBoolean("cancelled"),
                    departed = it.flag("departed"),
                    arrived = it.flag("arrived"),
                    operationalVerified = true
                )
            }
            assertEquals(case.toString(), case.getBoolean("expected"), TrainsWidgetPresenter.isRunning(status, requireNotNull(case.millis("now"))))
        }
        for (case in trains.getJSONArray("lookupDate").objects()) {
            val monitor = ServiceMonitor(number = "2153", label = "Treno 2153", origin = "Origine", scheduledDeparture = case.getString("savedDeparture"))
            val json = if (case.isNull("status")) null else case.getJSONObject("status")
            val status = json?.let {
                FavouriteTrainStatus(
                    number = "2153",
                    retrievedAt = 1L,
                    scheduledDeparture = it.atOrNull("departure")?.toLocalTime()?.toString().orEmpty(),
                    scheduledDepartureEpochMillis = it.millis("departure"),
                    arrivalEpochMillis = it.millis("arrival")
                )
            }
            val date = TrainsWidgetLookupPlanner.lookupDate(monitor, status, at(case.getString("now")).toInstant(), case.getBoolean("automatic"), rome)
            assertEquals(case.toString(), case.text("expected")?.let { LocalDate.parse(it) }, date)
        }
    }

    @Test fun `one run of the scheduler for one widget`() {
        for (case in rules.getJSONArray("planner").objects()) {
            val now = at(case.getString("now"))
            val candidate = AutoRefreshCandidate(
                kind = WidgetKind.ROUTE,
                widgetId = 1,
                settings = settings(case.getString("settings")),
                lastRefreshEpochMillis = case.millis("lastRefresh") ?: 0L,
                nextDirectionBoundary = case.atOrNull("nextDirectionBoundary"),
                pendingDirection = case.direction("pendingDirection")
            )
            val action = WidgetAutoRefreshPlanner.plan(listOf(candidate), now).singleOrNull()
            assertEquals(case.toString(), case.getBoolean("fetch"), action?.fetch == true)
            assertEquals(case.toString(), case.direction("applyDirection"), action?.applyDirection)
            assertEquals(case.toString(), case.atOrNull("nextRun")?.toInstant(), WidgetAutoRefreshPlanner.nextRun(listOf(candidate), now)?.toInstant())
        }
    }

    @Test fun `the retries of a refresh the user asked for`() {
        for (case in rules.getJSONArray("retry").objects()) {
            val retry = WidgetRefreshRetryPolicy.retry(mapOf(1 to settings(case.getString("settings"))), case.getInt("attempt"), at(case.getString("now")))
            val expected = if (case.isNull("expectedDelayMinutes")) null else Duration.ofMinutes(case.getLong("expectedDelayMinutes"))
            assertEquals(case.toString(), expected, retry?.delay)
        }
    }

    @Test fun `how fresh the data of a widget are`() {
        for (case in rules.getJSONArray("freshness").objects()) {
            val freshness = WidgetFreshness.fromAge(case.millis("retrievedAt") ?: 0L, requireNotNull(case.millis("now")))
            assertEquals(case.toString(), Freshness.valueOf(case.getString("expected")), freshness)
        }
    }
}
