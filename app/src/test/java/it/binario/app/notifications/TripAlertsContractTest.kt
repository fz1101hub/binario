package it.binario.app.notifications

import it.binario.app.CommuterAlertPlanner
import it.binario.app.CommuterAlertStatusRules
import it.binario.app.CommuterAlertStatusStrings
import it.binario.app.CommuterLiveStatus
import it.binario.app.StopAlarmPlan
import it.binario.app.StopAlarmPlanner
import it.binario.app.data.RailOperator
import it.binario.app.gpsalarm.GpsFix
import it.binario.app.gpsalarm.GpsRouteStop
import it.binario.app.gpsalarm.GpsTrainRoute
import it.binario.app.gpsalarm.GpsTrainRoutes
import it.binario.app.gpsalarm.GpsTrainTrack
import it.binario.app.gpsalarm.GpsTrainTracking
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * contracts/shared-rules/v1/trip-alerts-v1.json: the stop alarm, the stop countdown, the followed trip, the approach
 * alert, the notices of the trains with the bell, the status of a commuter alert and the position estimate, as the
 * iPhone's TripAlertsContractTests read them.
 */
class TripAlertsContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/trip-alerts-v1.json").readText(Charsets.UTF_8))
    private val rome = ZoneId.of("Europe/Rome")

    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(rome).toInstant()
    private fun JSONObject.instant(key: String): Instant? = if (isNull(key)) null else at(getString(key))
    private fun JSONObject.int(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.date(key: String): LocalDate? = if (isNull(key)) null else LocalDate.parse(getString(key))
    private fun JSONObject.days(): Map<String, Long> = keys().asSequence().associateWith(::getLong)
    private fun JSONObject.name(): String = optString("name", toString())

    @Test fun `the quiet hours`() {
        for (case in rules.getJSONArray("quietHours").objects()) {
            val time = LocalTime.parse(case.getString("time"))
            val instant = LocalDateTime.of(LocalDate.parse("2026-11-24"), time).atZone(rome).toInstant()
            assertEquals(case.toString(), case.getBoolean("quiet"), CommuterAlertPlanner.isQuiet(time))
            assertEquals(case.toString(), case.getBoolean("quiet"), TrainAlertWatch.isQuiet(instant, rome))
        }
    }

    @Test fun `the stop alarm`() {
        val alarm = rules.getJSONObject("stopAlarm")
        for (case in alarm.getJSONArray("plan").objects()) {
            val plan = StopAlarmPlanner.plan("2153", "Lecco", "Monza", case.getString("expected"), at(case.getString("now")).toEpochMilli(), rome)
            assertEquals(case.name(), case.instant("expectedAt")?.toEpochMilli(), plan?.expectedAtEpochMillis)
            assertEquals(case.name(), case.instant("triggerAt")?.toEpochMilli(), plan?.triggerAtEpochMillis)
        }
        for (case in alarm.getJSONArray("restoreTrigger").objects()) {
            val plan = StopAlarmPlan("2153", "Lecco", "Monza", "8:30", at(case.getString("trigger")).toEpochMilli(), at(case.getString("expectedAt")).toEpochMilli())
            assertEquals(case.toString(), case.instant("expected")?.toEpochMilli(), StopAlarmPlanner.restoreTrigger(plan, at(case.getString("now")).toEpochMilli()))
        }
        for (case in alarm.getJSONArray("isOver").objects()) {
            val expected = at(case.getString("expectedAt")).toEpochMilli()
            val plan = StopAlarmPlan("2153", "Lecco", "Monza", "8:30", expected - 300_000L, expected)
            assertEquals(case.toString(), case.getBoolean("over"), StopAlarmPlanner.isOver(plan, at(case.getString("now")).toEpochMilli()))
        }
    }

    private fun assertCountdown(name: String, expected: JSONObject?, actual: StopCountdown?) {
        if (expected == null) {
            assertNull(name, actual)
            return
        }
        assertEquals(name, expected.getInt("stopsLeft"), actual?.stopsLeft)
        assertEquals(name, expected.getInt("nextIndex"), actual?.nextIndex)
        assertEquals(name, expected.getString("nextStop"), actual?.nextStop)
        assertEquals(name, expected.getString("alightStop"), actual?.alightStop)
        assertEquals(name, expected.getString("source"), actual?.source?.name)
    }

    @Test fun `the stop countdown`() {
        val countdown = rules.getJSONObject("stopCountdown")
        val stops = countdown.getJSONArray("stops").objects().map {
            CountdownStop(it.getString("name"), null, it.instant("at"), it.getBoolean("skipped"))
        }
        for (case in countdown.getJSONArray("cases").objects()) {
            val actual = StopCountdownCalculator.countdown(
                stops, case.getInt("board"), case.getInt("alight"), at(case.getString("now")), case.int("livePassed"), case.int("positionPassed")
            )
            assertCountdown(case.name(), case.optJSONObject("expected"), actual)
        }
    }

    @Test fun `the followed trip`() {
        val followed = rules.getJSONObject("followedTrip")
        val leg = FollowedTripLeg("2153", "REG 2153", "Milano Centrale", "Lecco", at(followed.getString("departure")), at(followed.getString("arrival")))
        val trip = FollowedTrip("trip", "Milano Centrale → Lecco", listOf(leg))
        for (case in followed.getJSONArray("step").objects()) {
            val expected = case.getJSONObject("expected")
            val step = FollowedTripPlanner.step(trip, at(case.getString("now")), case.int("lastDelayMinutes"))
            when (expected.getString("step")) {
                "wait" -> assertEquals(case.name(), FollowedTripPlanner.Step.WaitUntil(at(expected.getString("at"))), step)
                "refresh" -> assertEquals(case.name(), FollowedTripPlanner.Step.Refresh(at(expected.getString("at"))), step)
                else -> assertEquals(case.name(), FollowedTripPlanner.Step.Finish, step)
            }
        }
        for (case in followed.getJSONArray("isUpcomingOrRunning").objects()) {
            assertEquals(case.toString(), case.getBoolean("expected"), FollowedTripPlanner.isUpcomingOrRunning(trip, at(case.getString("now"))))
        }
        for (case in followed.getJSONArray("nextRefreshAt").objects()) {
            val countdown = case.int("stopsLeft")?.let { StopCountdown(it, 0, "Monza", null, case.instant("nextStopAt"), "Lecco", StopProgressSource.SCHEDULED) }
            assertEquals(case.name(), at(case.getString("expected")),
                FollowedTripPlanner.nextRefreshAt(at(case.getString("regular")), at(case.getString("now")), countdown))
        }
    }

    private fun approachAlert(json: JSONObject) = ApproachAlert(
        number = "2153", label = "REG 2153", station = "Monza", stationTime = json.getString("stationTime"),
        departure = json.optString("departure", "08:00"), leadMinutes = json.getInt("lead"), createdAt = at("2026-11-20T08:00")
    )

    private fun observation(json: JSONObject?): ApproachObservation? = json?.let {
        ApproachObservation(
            at(it.getString("expectedAt")), it.int("delayMinutes"), if (it.isNull("platform")) null else it.getString("platform"),
            it.getBoolean("passed"), it.getBoolean("cancelled")
        )
    }

    @Test fun `the approach alert`() {
        val approach = rules.getJSONObject("approach")
        for (case in approach.getJSONArray("window").objects()) {
            val window = ApproachAlertPlanner.window(approachAlert(case), LocalDate.parse(case.getString("date")), rome)
            val expected = case.optJSONObject("expected")
            assertEquals(case.name(), expected?.let { at(it.getString("start")) }, window?.start)
            assertEquals(case.name(), expected?.let { at(it.getString("end")) }, window?.endInclusive)
        }
        for (case in approach.getJSONArray("serviceDate").objects()) {
            assertEquals(case.toString(), LocalDate.parse(case.getString("expected")),
                ApproachAlertPlanner.serviceDate(approachAlert(case), LocalDate.parse(case.getString("day")), rome))
        }
        for (case in approach.getJSONArray("quietOnTimetable").objects()) {
            assertEquals(case.toString(), case.getBoolean("expected"), ApproachAlertPlanner.quietOnTimetable(approachAlert(case)))
        }
        for (case in approach.getJSONArray("minutesUntil").objects()) {
            assertEquals(case.toString(), case.getInt("minutes"), ApproachAlertPlanner.minutesUntil(at(case.getString("now")), at(case.getString("expected"))))
        }
        val base = approach.getJSONObject("alert")
        val alert = approachAlert(base)
        val date = LocalDate.parse(base.getString("date"))
        for (case in approach.getJSONArray("decide").objects()) {
            val decision = ApproachAlertPlanner.decide(
                alert, date, observation(case.optJSONObject("observation")), at(case.getString("now")), case.getBoolean("notified"), rome
            )
            val expected = case.getJSONObject("expected")
            when (expected.getString("decision")) {
                "wait" -> assertEquals(case.name(), ApproachDecision.WaitUntil(at(expected.getString("at"))), decision)
                "notify" -> assertEquals(case.name(), ApproachDecision.Notify(expected.getInt("minutesLeft")), decision)
                else -> assertEquals(case.name(), ApproachDecision.Done, decision)
            }
        }
        for (case in approach.getJSONArray("nextRun").objects()) {
            val next = ApproachAlertPlanner.nextRun(
                alert, case.date("doneOn"), case.date("notifiedOn"), observation(case.optJSONObject("observation")), at(case.getString("now")), rome
            )
            assertEquals(case.name(), case.instant("expected"), next)
        }
        for (case in approach.getJSONArray("alarmAt").objects()) {
            val alarm = ApproachAlertPlanner.alarmAt(
                alert, case.date("doneOn"), case.date("notifiedOn"), observation(case.optJSONObject("observation")), at(case.getString("now")), rome
            )
            assertEquals(case.name(), case.instant("expected"), alarm)
        }
    }

    private fun subscription(number: String, departure: String) =
        TrainAlertSubscription(number, TrainAlertWatch.key(number), null, RailOperator.TRENITALIA_REGIONALE, departure, number, at("2026-11-20T08:00"))

    /** One subscription per departure, each its own train. */
    private fun subscriptions(json: JSONObject) =
        json.getJSONArray("departures").strings().mapIndexed { index, departure -> subscription("${100 + index}", departure) }

    @Test fun `the notices of the trains with the bell`() {
        val alerts = rules.getJSONObject("trainAlerts")
        for (case in alerts.getJSONArray("key").objects()) {
            assertEquals(case.toString(), case.getString("expected"), TrainAlertWatch.key(case.getString("number")))
        }
        val number = alerts.getString("number")
        for (case in alerts.getJSONArray("window").objects()) {
            val followed = case.getJSONArray("followed").objects().map {
                FollowedTrainTimes(it.getString("number"), at(it.getString("departure")), at(it.getString("arrival")))
            }
            val window = TrainAlertWatch.window(subscription(number, case.getString("departure")), LocalDate.parse(case.getString("date")), followed, rome)
            val expected = case.optJSONObject("expected")
            assertEquals(case.name(), expected?.let { at(it.getString("start")) }, window?.start)
            assertEquals(case.name(), expected?.let { at(it.getString("end")) }, window?.endInclusive)
        }
        for (case in alerts.getJSONArray("due").objects()) {
            val due = TrainAlertWatch.due(subscriptions(case), emptyList(), at(case.getString("now")), rome)
            assertEquals(case.name(), case.getJSONArray("expected").strings(), due.map { it.departure })
        }
        for (case in alerts.getJSONArray("nextRun").objects()) {
            val next = TrainAlertWatch.nextRun(subscriptions(case), emptyList(), at(case.getString("now")), rome, case.instant("lastCheckAt"))
            assertEquals(case.name(), case.instant("expected"), next)
        }
        for (case in alerts.getJSONArray("newNotices").objects()) {
            val notices = case.getJSONArray("notices").objects().map { WatchedNotice(it.getString("id"), "Avviso", it.instant("publishedAt")) }
            val fresh = TrainAlertWatch.newNotices(notices, case.getJSONObject("seen").days(), at(case.getString("subscribedAt")))
            assertEquals(case.name(), case.getJSONArray("expected").strings(), fresh.map { it.id })
        }
        for (case in alerts.getJSONArray("remember").objects()) {
            val notices = case.getJSONArray("notices").strings().map { WatchedNotice(it, "Avviso", null) }
            val remembered = TrainAlertWatch.remember(case.getJSONObject("seen").days(), notices, LocalDate.parse(case.getString("today")))
            assertEquals(case.name(), case.getJSONObject("expected").days(), remembered)
        }
    }

    @Test fun `the status of a commuter alert`() {
        val commuter = rules.getJSONObject("commuterStatus")
        val texts = commuter.getJSONObject("strings")
        val strings = CommuterAlertStatusStrings(
            unavailable = texts.getString("unavailable"),
            onTime = texts.getString("onTime"),
            cancelled = texts.getString("cancelled"),
            delay = { minutes -> texts.getString("delay").replace("%d", minutes.toString()) },
            withPlatform = { status, platform -> texts.getString("withPlatform").replaceFirst("%s", status).replaceFirst("%s", platform) }
        )
        for (case in commuter.getJSONArray("format").objects()) {
            val status = case.optJSONObject("status")?.let {
                CommuterLiveStatus(it.getInt("delayMinutes"), if (it.isNull("platform")) null else it.getString("platform"), it.getBoolean("cancelled"))
            }
            assertEquals(case.toString(), case.getString("expected"), CommuterAlertStatusRules.format(status, strings))
        }
        for (case in commuter.getJSONArray("routeStations").objects()) {
            val expected = case.optJSONArray("expected")?.strings()?.let { it[0] to it[1] }
            assertEquals(case.toString(), expected, CommuterAlertStatusRules.routeStations(case.getString("label")))
        }
    }

    private fun fix(json: JSONObject) =
        GpsFix(json.getDouble("latitude"), json.getDouble("longitude"), json.getDouble("accuracy"), json.getLong("ageSeconds") * 1_000L)

    private fun assertTrack(name: String, expected: JSONObject, track: GpsTrainTrack) {
        assertEquals(name, expected.getString("status"), track.status.name)
        assertEquals(name, expected.int("passedIndex"), track.passedIndex)
        assertEquals(name, expected.int("delayMinutes"), track.estimate?.delayMinutes)
        assertEquals(name, expected.instant("arrivalAt")?.toEpochMilli(), track.estimate?.arrivalEpochMillis)
    }

    @Test fun `the position estimate`() {
        val position = rules.getJSONObject("position")
        val route = GpsTrainRoute(
            trainNumber = "2153",
            stops = position.getJSONArray("route").objects().map {
                GpsRouteStop(it.getString("name"), it.getDouble("latitude"), it.getDouble("longitude"), at(it.getString("at")).toEpochMilli())
            },
            boardIndex = position.getInt("board"),
            targetIndex = position.getInt("target")
        )
        val tracker = GpsTrainTracking.Tracker(route)
        for (case in position.getJSONArray("fixes").objects()) {
            assertTrack(case.name(), case.getJSONObject("expected"), tracker.onFix(fix(case), at(case.getString("now")).toEpochMilli()))
        }
        val countdown = position.getJSONObject("countdown")
        assertCountdown("countdown", countdown.getJSONObject("expected"),
            GpsTrainRoutes.countdown(route, tracker.track, at(countdown.getString("now")).toEpochMilli(), rome))
        val arrival = position.getJSONObject("arrival")
        assertTrack("arrival", arrival.getJSONObject("expected"), tracker.onFix(fix(arrival), at(arrival.getString("now")).toEpochMilli()))
        for (case in position.getJSONArray("canStartEstimate").objects()) {
            assertEquals(case.toString(), case.getBoolean("expected"), GpsTrainTracking.canStartEstimate(
                at(case.getString("departure")).toEpochMilli(), at(case.getString("arrival")).toEpochMilli(),
                case.int("officialDelayMinutes"), at(case.getString("now")).toEpochMilli()
            ))
        }
    }
}
