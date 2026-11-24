package it.binario.app.notifications

import it.binario.app.data.live.StrikeNotice
import it.binario.app.data.notices.NoticeDates
import it.binario.app.data.notices.NoticePublisher
import it.binario.app.data.notices.RailNotice
import it.binario.app.data.notices.RouteWorksNotice
import it.binario.app.data.notices.RouteWorksReason
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * contracts/shared-rules/v1/evening-alerts-v1.json: the 18:00 strike reminder and the 19:00 alert about tomorrow's works
 * on the saved routes, as the iPhone's ExtrasContractTests read them.
 */
class EveningAlertsContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val rules = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/evening-alerts-v1.json").readText(Charsets.UTF_8))
    private val rome = ZoneId.of("Europe/Rome")
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun JSONObject.date(key: String): LocalDate? = if (isNull(key)) null else LocalDate.parse(getString(key))
    private fun at(text: String): ZonedDateTime = LocalDateTime.parse(text).atZone(rome)
    private fun days(json: JSONObject): Map<String, Long> = json.keys().asSequence().associateWith(json::getLong)

    @Test fun `the strike reminder`() {
        val strike = rules.getJSONObject("strikeReminder")
        for (case in strike.getJSONArray("nextRunAt").objects()) {
            assertEquals(case.getString("now"), at(case.getString("expected")), StrikeReminderPlanner.nextRunAt(at(case.getString("now"))))
        }
        for (case in strike.getJSONArray("mayNotify").objects()) {
            assertEquals(case.getString("now"), case.getBoolean("expected"), StrikeReminderPlanner.mayNotify(at(case.getString("now"))))
        }
        for (case in strike.getJSONArray("toNotify").objects()) {
            val strikes = case.getJSONArray("strikes").objects().map {
                StrikeNotice(it.getString("id"), "Sciopero", null, null, null, it.date("start"), it.date("end"))
            }
            val notices = StrikeReminderPlanner.toNotify(strikes, LocalDate.parse(case.getString("today")), days(case.getJSONObject("notified")))
            assertEquals(case.getString("name"), case.getJSONArray("expected").strings(), notices.map { it.id })
        }
        for (case in strike.getJSONArray("remember").objects()) {
            val strikes = case.getJSONArray("strikes").strings().map { StrikeNotice(it, "Sciopero", null, null, null, null, null) }
            assertEquals(days(case.getJSONObject("expected")),
                StrikeReminderPlanner.remember(days(case.getJSONObject("notified")), strikes, LocalDate.parse(case.getString("today"))))
        }
    }

    private fun works(json: JSONObject): RouteWorksNotice {
        val title = if (json.getBoolean("mentionsStrike")) "Sciopero ${json.getString("id")}" else "Lavori ${json.getString("id")}"
        val dates = json.optJSONObject("dates")?.let { NoticeDates(LocalDate.parse(it.getString("first")), LocalDate.parse(it.getString("last"))) }
        val reasons = json.getJSONArray("reasons").objects().map {
            RouteWorksReason(it.getString("routeId"), it.getString("routeName"), it.getJSONArray("stations").strings(), if (it.isNull("line")) null else it.getString("line"))
        }
        return RouteWorksNotice(RailNotice(json.getString("id"), NoticePublisher.RFI, title, null, null, null), reasons, dates)
    }

    @Test fun `the works alert`() {
        val alert = rules.getJSONObject("routeWorks")
        for (case in alert.getJSONArray("nextRunAt").objects()) {
            assertEquals(case.getString("now"), at(case.getString("expected")),
                RouteWorksAlertPlanner.nextRunAt(at(case.getString("now")), case.getBoolean("immediate")))
        }
        for (case in alert.getJSONArray("mayNotify").objects()) {
            assertEquals(case.getString("now"), case.getBoolean("expected"), RouteWorksAlertPlanner.mayNotify(at(case.getString("now"))))
        }
        for (case in alert.getJSONArray("quiet").objects()) {
            assertEquals(case.getString("now"), case.getBoolean("expected"), RouteWorksAlertPlanner.quiet(at(case.getString("now"))))
        }
        for (case in alert.getJSONArray("select").objects()) {
            val name = case.getString("name")
            val selected = RouteWorksAlertPlanner.select(
                case.getJSONArray("works").objects().map(::works),
                case.getJSONArray("routeIds").strings(),
                LocalDate.parse(case.getString("today")),
                days(case.getJSONObject("notified"))
            )
            val expected = case.optJSONObject("expected")
            if (expected == null) {
                assertNull(name, selected)
                continue
            }
            assertEquals(name, LocalDate.parse(expected.getString("day")), selected!!.day)
            assertEquals(name, expected.getJSONArray("due").objects().map { it.getString("id") to it.getString("routeId") },
                selected.due.map { it.works.notice.id to it.reason.routeId })
            assertEquals(name, expected.getJSONArray("routeNames").strings(), selected.routeNames)
            if (expected.has("shown")) {
                assertEquals(name, expected.getInt("shown"), selected.items.size)
                assertEquals(name, expected.getInt("hidden"), selected.hidden)
            }
        }
        for (case in alert.getJSONArray("keepUntil").objects()) {
            assertEquals(LocalDate.parse(case.getString("expected")), RouteWorksAlertPlanner.keepUntil(
                NoticeDates(LocalDate.parse(case.getString("first")), LocalDate.parse(case.getString("last"))), LocalDate.parse(case.getString("today"))))
        }
        for (case in alert.getJSONArray("remember").objects()) {
            val today = LocalDate.parse(case.getString("today"))
            val due = case.getJSONArray("due").objects().map {
                val dates = NoticeDates(LocalDate.parse(it.getString("first")), LocalDate.parse(it.getString("last")))
                val reason = RouteWorksReason("casa", "Casa", emptyList(), null)
                RouteWorksAlertItem(RouteWorksNotice(RailNotice(it.getString("id"), NoticePublisher.RFI, "Lavori", null, null, null), listOf(reason), dates), reason, dates)
            }
            val selected = RouteWorksAlert(today.plusDays(1), due, listOf("Casa"))
            assertEquals(case.getString("name"), days(case.getJSONObject("expected")),
                RouteWorksAlertPlanner.remember(days(case.getJSONObject("notified")), selected, today))
        }
    }
}
