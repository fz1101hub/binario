package it.binario.app.data.notices

import it.binario.app.data.Station
import it.binario.app.data.catalog.EmbeddedCuratedRailStationCatalog
import it.binario.app.data.live.MitStrikeRssAdapter
import it.binario.app.data.live.MutablePublicSourceClock
import it.binario.app.data.live.RecordingPublicSourceFetcher
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.Rss2Parser
import it.binario.app.data.live.RssItem
import it.binario.app.data.live.StrikeCalendar
import it.binario.app.data.live.TrenitaliaInfomobilityNewsAdapter
import it.binario.app.data.live.TrenordLine
import it.binario.app.data.live.TrenordLineNoticesAdapter
import it.binario.app.data.provider.AlertRequest
import it.binario.app.data.provider.ProviderErrorKind
import it.binario.app.data.provider.ProviderResult
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * contracts/shared-rules/v1/notices-v1.json: the notice rules Android and the iPhone must share (strike days, notice
 * text and links, Trenitalia and Trenord lists, route works), read from the same file by both test suites and run here
 * against the existing adapters and matchers.
 */
class NoticesContractTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val cases = JSONObject(File(repositoryRoot, "contracts/shared-rules/v1/notices-v1.json").readText(Charsets.UTF_8))
    private val clock = MutablePublicSourceClock(Instant.parse("2026-10-25T19:00:00Z"))

    private fun file(path: String): File = File(repositoryRoot, path)
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.date(key: String): LocalDate? = text(key)?.let(LocalDate::parse)
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
    private fun links(text: String?): List<String> =
        NoticeLinks.segments(text.orEmpty()).filterIsInstance<NoticeLinks.Segment.Link>().map { it.url }

    @Test fun `strike days of the MIT calendar`() {
        val strikes = cases.getJSONObject("strikes")
        val items = Rss2Parser.parse(file(strikes.getString("feed")).readBytes())
        assertEquals(strikes.getInt("items"), items.size)
        val first = strikes.getJSONObject("firstItem")
        assertEquals(first.getString("title"), items.first().title)
        assertEquals(first.getString("link"), items.first().link)
        assertEquals(first.getString("guid"), items.first().guid)
        assertEquals(Instant.parse(first.getString("pubDate")), items.first().pubDate)
        assertTrue(items.first().description!!.contains(first.getString("descriptionContains")))

        val today = LocalDate.parse(strikes.getString("fetchedOn"))
        val rail = StrikeCalendar.strikes(items, today)
        val expected = strikes.getJSONArray("rail").objects()
        assertEquals(expected.map { it.getString("id") }, rail.map { it.id })
        for (case in expected) {
            val strike = rail.single { it.id == case.getString("id") }
            assertEquals(strike.id, case.date("start"), strike.startDate)
            assertEquals(strike.id, case.date("end"), strike.endDate)
        }
        val description = strikes.getJSONObject("descriptionContains")
        assertTrue(rail.single { it.id == description.getString("id") }.description!!.contains(description.getString("text")))
        val now = Instant.parse(strikes.getString("now"))
        assertEquals(strikes.getJSONArray("upcoming").strings(), StrikeCalendar.upcoming(rail, today, now).map { it.id })
        assertEquals(strikes.getJSONArray("startingTomorrow").strings(), StrikeCalendar.startingTomorrow(rail, today).map { it.id })
        val gone = strikes.getJSONObject("undatedGoneAfter")
        assertTrue(StrikeCalendar.upcoming(rail, today, Instant.parse(gone.getString("now"))).none { it.id == gone.getString("id") })

        for (case in strikes.getJSONArray("isRail").objects()) {
            val item = RssItem(case.getString("title"), null, case.text("description"), null, null)
            assertEquals(case.getString("title"), case.getBoolean("rail"), StrikeCalendar.isRail(item))
        }
        for (case in strikes.getJSONArray("dates").objects()) {
            val reference = LocalDate.parse(case.getString("reference"))
            assertEquals(case.getString("text"), case.date("start") to case.date("end"), StrikeCalendar.dates(case.getString("text"), reference))
        }
        for (body in strikes.getJSONArray("unreadable").strings()) {
            val result = runBlocking { MitStrikeRssAdapter({ body.toByteArray() }, clock, 0).strikes() }
            if (result !is ProviderResult.Failure) fail("Expected a failure for $body: $result")
        }
    }

    @Test fun `notice text, strike words and links`() {
        val text = cases.getJSONObject("text")
        for (case in text.getJSONArray("mentionsStrike").objects()) {
            val texts = case.getJSONArray("texts").let { array -> (0 until array.length()).map { if (array.isNull(it)) null else array.getString(it) } }
            assertEquals(texts.toString(), case.getBoolean("strike"), NoticeText.mentionsStrike(*texts.toTypedArray()))
        }
        for (case in text.getJSONArray("normalize").objects()) {
            assertEquals(case.text("expected"), NoticeText.normalize(case.getString("text"), case.getInt("maximumLength")))
        }
        for (case in text.getJSONArray("fromHtml").objects()) {
            assertEquals(case.text("expected"), NoticeText.fromHtml(case.getString("html"), case.getInt("maximumLength")))
        }
        val keys = text.getJSONArray("sameDedupKey")
        for (index in 0 until keys.length()) {
            val pair = keys.getJSONArray(index).strings()
            assertEquals(NoticeText.dedupKey(pair[0]), NoticeText.dedupKey(pair[1]))
        }
        for (case in text.getJSONArray("links").objects()) {
            val segments = NoticeLinks.segments(case.getString("text"))
            assertEquals(case.getJSONArray("links").strings(), segments.filterIsInstance<NoticeLinks.Segment.Link>().map { it.url })
            assertEquals(case.getString("text"), segments.joinToString("") { it.text })
        }
        for (case in text.getJSONArray("cleartextAllowed").objects()) {
            assertEquals(case.getString("url"), case.getBoolean("allowed"), CleartextNoticeLinks.allowed(case.getString("url")))
        }
        for (case in text.getJSONArray("neutralize").objects()) {
            val neutral = CleartextNoticeLinks.neutralize(case.getString("text"))!!
            assertEquals(case.getString("text"), case.getJSONArray("links").strings(), links(neutral))
            case.text("contains")?.let { assertTrue(neutral, neutral.contains(it)) }
            case.text("expected")?.let { assertEquals(it, neutral) }
        }
    }

    private fun trenitalia(body: String) = TrenitaliaInfomobilityNewsAdapter(RecordingPublicSourceFetcher { body }, clock, 0)

    @Test fun `Trenitalia infomobility news`() = runBlocking {
        val news = cases.getJSONObject("trenitalia")
        val body = file(news.getString("feed")).readText(Charsets.UTF_8)
        val alerts = (trenitalia(body).getAlerts(AlertRequest()) as ProviderResult.Success).value
        val expected = news.getJSONArray("notices").objects()
        assertEquals(expected.size, alerts.size)
        for ((case, alert) in expected.zip(alerts)) {
            case.text("title")?.let { assertEquals(it, alert.title.value) }
            case.text("titleStartsWith")?.let { assertTrue(alert.title.value.startsWith(it)) }
            val description = alert.description!!.value
            case.text("descriptionStartsWith")?.let { assertTrue(description, description.startsWith(it)) }
            case.getJSONArray("descriptionContains").strings().forEach { assertTrue(it, it in description) }
            news.getJSONArray("neverInDescriptions").strings().forEach { assertFalse(it, it in description) }
            assertEquals(case.getJSONArray("trainNumbers").strings().toSet(), alert.affectedTrainNumbers)
            assertEquals(Instant.parse(case.getString("publishedAt")), alert.validFrom)
            if (case.has("link")) assertEquals(case.text("link"), alert.link)
        }

        val hostile = news.getJSONObject("hostile")
        val parsed = trenitalia("").parse(JSONArray().put(hostile.getJSONObject("item")).toString()).single()
        assertEquals(hostile.getString("description"), parsed.description)
        assertEquals(hostile.text("link"), parsed.link)

        val foreign = news.getJSONObject("foreignLinks")
        val both = trenitalia("").parse(foreign.getJSONArray("items").toString())
        assertEquals(foreign.text("firstLink"), both.first().link)
        assertEquals(foreign.getJSONArray("firstLinks").strings(), links(both.first().title + " " + both.first().description))
        assertTrue(both.first().title.contains(foreign.getString("firstTitleContains")))
        assertTrue(both.first().description!!.contains(foreign.getString("firstDescriptionContains")))
        assertEquals(foreign.getString("secondLink"), both.last().link)

        val twice = news.getJSONObject("encodedTwice")
        val service = RailNoticesService(emptyList(), listOf(trenitalia(JSONArray().put(twice.getJSONObject("item")).toString())))
        val notice = service.general().network.single()
        assertTrue(notice.cleartextHop)
        assertEquals(twice.getJSONArray("links").strings(), links(notice.title + " " + notice.body))
        assertTrue(notice.body!!.contains(twice.getString("bodyContains")))
        assertEquals(twice.getString("link"), notice.link)

        for (bad in news.getJSONArray("unreadable").strings() + body.take(news.getInt("truncatedUnreadable"))) {
            assertEquals(ProviderErrorKind.PARSE_ERROR, (trenitalia(bad).getAlerts(AlertRequest()) as ProviderResult.Failure).error.kind)
        }
    }

    private fun rfi(xml: String) = RfiInfomobilityRssAdapter(fetcher = { xml }, clock = clock)

    @Test fun `the notices area without duplicates and with the strike badge`() = runBlocking {
        val general = cases.getJSONObject("general")
        val news = file(cases.getJSONObject("trenitalia").getString("feed")).readText(Charsets.UTF_8)
        val area = RailNoticesService(emptyList(), listOf(rfi(general.getString("rfi")), trenitalia(news))).general()
        assertEquals(general.getInt("network"), area.network.size)
        val rfiOnly = area.network.filter { it.title.startsWith(general.getString("rfiOnlyTitleStartsWith")) }
        assertEquals(listOf(NoticePublisher.RFI), rfiOnly.map { it.publisher })
        assertEquals(general.getString("rfiOnlyBody"), rfiOnly.single().body)
        assertEquals(Instant.parse(general.getString("rfiOnlyPublishedAt")), rfiOnly.single().publishedAt)
        assertTrue(area.unavailable.isEmpty())
        val failing = TrenitaliaInfomobilityNewsAdapter(RecordingPublicSourceFetcher { throw IOException("offline") }, clock, 0)
        val broken = RailNoticesService(emptyList(), listOf(rfi(general.getString("rfi")), failing)).general()
        assertEquals(setOf(NoticePublisher.TRENITALIA), broken.unavailable)
        assertEquals(general.getInt("trenitaliaFailingNetwork"), broken.network.size)

        val trenord = TrenordLineNoticesAdapter(RecordingPublicSourceFetcher { file(cases.getJSONObject("trenord").getString("feed")).readText() }, clock, 0)
        val badges = RailNoticesService(emptyList(), listOf(rfi(general.getString("strikeRfi")), trenitalia(news)), trenord).general()
        assertEquals(general.getJSONArray("strikeBadgePublishers").strings(), badges.network.filter { it.mentionsStrike }.map { it.publisher.name })
        val trenordStrikes = badges.trenordGroups.flatMap { it.notices }.filter { it.mentionsStrike }
        assertTrue(trenordStrikes.single().body!!.contains(general.getString("trenordStrikeBodyContains")))
    }

    @Test fun `Trenord line notices, groups and station boards`() = runBlocking {
        val trenord = cases.getJSONObject("trenord")
        val body = file(trenord.getString("feed")).readText(Charsets.UTF_8)
        fun adapter(text: String) = TrenordLineNoticesAdapter(RecordingPublicSourceFetcher { text }, clock, 0)
        val notices = (adapter(body).getLineNotices() as ProviderResult.Success).value
        assertEquals(trenord.getJSONArray("lines").strings(), notices.lines.map { it.code })
        assertEquals(trenord.getJSONArray("firstLineTokens").strings(), notices.lines.first().stationTokens)
        assertEquals(trenord.getInt("notices"), notices.alerts.size)
        val strikeCase = trenord.getJSONObject("strike")
        val strike = notices.alerts.single { it.description!!.value.startsWith(strikeCase.getString("startsWith")) }
        assertEquals(strikeCase.getJSONArray("lines").strings().toSet(), strike.affectedLines)
        assertEquals(Instant.parse(strikeCase.getString("date")), strike.validFrom)
        assertTrue(strike.title.value.startsWith(strikeCase.getString("titleStartsWith")))
        assertTrue(strike.title.value.length <= strikeCase.getInt("titleMaximumLength") && strike.title.value.endsWith("…"))
        assertTrue(strike.description!!.value.endsWith(strikeCase.getString("descriptionEndsWith")))
        assertTrue(notices.alerts.none { trenord.getString("neverContains") in it.description!!.value })
        assertEquals(notices.alerts.sortedByDescending { it.validFrom }, notices.alerts)
        for (case in trenord.getJSONArray("operational").objects()) {
            assertEquals(case.getString("text"), case.getBoolean("operational"), TrenordLineNoticesAdapter.isOperational(case.getString("text")))
        }

        val service = RailNoticesService(emptyList(), emptyList(), adapter(body))
        val groups = service.general().trenordGroups
        val expectedGroups = trenord.getJSONArray("groups").objects()
        assertEquals(expectedGroups.map { it.getJSONArray("lines").strings() }, groups.map { group -> group.lines.map { it.code } })
        assertEquals(expectedGroups.map { it.getBoolean("allLines") }, groups.map { it.allLines })
        for ((case, group) in expectedGroups.zip(groups)) case.text("name")?.let { assertEquals(it, group.lines.single().name) }
        assertEquals(trenord.getInt("groupedNotices"), groups.sumOf { it.notices.size })
        for (case in trenord.getJSONArray("stations").objects()) {
            val station = service.forStation(case.getString("name")).notices
            assertEquals(case.getString("name"), case.getInt("notices"), station.size)
            case.text("everyNoticeOnLine")?.let { code -> assertTrue(station.all { notice -> notice.lines.any { it.code == code } }) }
        }
        for (case in trenord.getJSONArray("matchedTokens").objects()) {
            val line = TrenordLine(case.getString("code"), case.getString("description"))
            assertEquals(case.getJSONArray("tokens").strings().toSet(), TrenordLineMatcher.matchedTokens(line, case.getJSONArray("stations").strings()))
        }
        for (case in trenord.getJSONArray("area").objects()) {
            assertEquals(case.getBoolean("inArea"), TrenordLineMatcher.inTrenordArea(case.getDouble("latitude"), case.getDouble("longitude")))
        }
        for (bad in trenord.getJSONArray("unreadable").strings() + body.take(trenord.getInt("truncatedUnreadable"))) {
            assertEquals(ProviderErrorKind.PARSE_ERROR, (adapter(bad).getLineNotices() as ProviderResult.Failure).error.kind)
        }
    }

    @Test fun `route works on the stations and lines of the routes`() {
        val works = cases.getJSONObject("routeWorks")
        val catalogue: List<Station> = EmbeddedCuratedRailStationCatalog({ File(file("app/src/main/assets"), it).inputStream() }).load()
        assertEquals("app/src/main/assets/stations/curated_it_rail_stations.json", works.getString("catalogue"))
        val matcher = RouteWorksMatcher(catalogue)
        fun station(name: String): Station = catalogue.single { it.name == name }
        val routes = works.getJSONArray("routes").objects().associate { route ->
            route.getString("id") to WorksRoute(route.getString("id"), route.getString("id"), route.getJSONArray("stations").strings().map(::station))
        }
        val clock = MutablePublicSourceClock(Instant.parse(works.getString("now")))
        val notices = runBlocking {
            val rfi = RfiInfomobilityRssAdapter(fetcher = { file(works.getString("feed")).readText() }, clock = clock)
            RailNoticesService(emptyList(), listOf(rfi)).general(includeTrenord = false).network
        }
        assertEquals(works.getInt("network"), notices.size)
        val today = LocalDate.parse(works.getString("today"))
        val found = matcher.match(notices, routes.values.toList(), today)
        val expected = works.getJSONArray("works").objects()
        assertEquals(expected.map { it.getString("title") }, found.map { it.notice.title })
        for ((case, item) in expected.zip(found)) {
            val reasons = case.getJSONArray("reasons").objects()
            assertEquals(reasons.map { it.getString("routeId") }, item.reasons.map { it.routeId })
            assertEquals(reasons.map { it.getJSONArray("stations").strings() }, item.reasons.map { it.stations })
            assertEquals(reasons.map { it.text("line") }, item.reasons.map { it.line })
            val dates = case.getJSONObject("dates")
            assertEquals(NoticeDates(LocalDate.parse(dates.getString("first")), LocalDate.parse(dates.getString("last"))), item.dates)
        }
        val none = works.getJSONArray("noMatchRoutes")
        for (index in 0 until none.length()) {
            assertTrue(matcher.match(notices, none.getJSONArray(index).strings().map(routes::getValue), today).isEmpty())
        }
        for (case in works.getJSONArray("singleNotices").objects()) {
            val title = case.getString("title")
            val route = WorksRoute("r", "r", case.getJSONArray("route").strings().map(::station))
            val result = matcher.match(listOf(RailNotice(title, NoticePublisher.RFI, title, null, null, null)), listOf(route), today)
            if (case.isNull("stations")) assertTrue(title, result.isEmpty())
            else assertEquals(title, case.getJSONArray("stations").strings(), result.single().reasons.single().stations)
        }
        for (case in works.getJSONArray("dates").objects()) {
            val expectedDates = case.date("first")?.let { NoticeDates(it, case.date("last")!!) }
            assertEquals(case.getString("text"), expectedDates, NoticeDateParser.dates(case.getString("text"), LocalDate.parse(case.getString("reference"))))
        }
        for (case in works.getJSONArray("isWorks").objects()) {
            assertEquals(case.getString("text"), case.getBoolean("works"), RouteWorksMatcher.isWorks(case.getString("text")))
        }
        for (case in works.getJSONArray("fold").objects()) {
            assertEquals(case.getString("expected"), RouteWorksMatcher.fold(case.getString("text")))
        }
        assertNull(NoticeDateParser.dates("", today))
    }
}
