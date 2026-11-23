package it.binario.app.data.notices

import it.binario.app.data.core.SourceId
import it.binario.app.data.live.MitStrikeRssAdapter
import it.binario.app.data.live.PublicSourceRequestPolicy
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.SourceCircuitBreaker
import it.binario.app.data.live.StrikeCalendar
import it.binario.app.data.live.TrenitaliaInfomobilityNewsAdapter
import it.binario.app.data.live.TrenordLineNoticesAdapter
import it.binario.app.data.live.binarioUserAgent
import it.binario.app.links.OfficialLinks
import it.binario.app.ui.departures.RouteWorksViewModel
import java.io.File
import java.time.Clock
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-data/v1/notices-sources-v1.json holds the notice feeds and the tables that read them for the other
 * platforms (the iPhone reads it at runtime). Until a generator writes the Kotlin side too, this test keeps the file
 * equal to Android's constants: private ones are read by reflection, the few literals written inline in a function
 * from the source file.
 */
class NoticesSourcesTableTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val table = JSONObject(File(repositoryRoot, "contracts/shared-data/v1/notices-sources-v1.json").readText(Charsets.UTF_8))
    private val sources = table.getJSONArray("sources").let { array -> (0 until array.length()).map(array::getJSONObject) }

    private fun source(id: String): JSONObject = sources.single { it.getString("id") == id }

    private fun kotlinSource(path: String): String = File(repositoryRoot, "app/src/main/java/it/binario/app/$path").readText(Charsets.UTF_8)

    /** A private field of [owner] (a static one without [instance]), as Kotlin compiles properties and constants. */
    private fun field(owner: Class<*>, name: String, instance: Any? = null): Any? =
        owner.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun pattern(owner: Class<*>, name: String): String = (field(owner, name) as Regex).pattern

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)

    private fun seconds(duration: Any?): Long = (duration as Duration).seconds

    /** [value] as Kotlin source writes it: «20_000», «6_000», «8». */
    private fun literal(value: Long): String = value.toString().reversed().chunked(3).joinToString("_").reversed()

    private fun assertInline(path: String, vararg fragments: String) {
        val text = kotlinSource(path)
        fragments.forEach { assertTrue("$path: «$it»", it in text) }
    }

    @Test fun `the manners are those of the refusal gate, the circuit breaker and the notices service`() {
        val manners = table.getJSONObject("manners")
        assertEquals("Binario/1.2 (Android; rail timetable app)",
            manners.getString("userAgent").replace("<version>", "1.2").replace("<platform>", "Android"))
        assertEquals(binarioUserAgent("1.2"), "Binario/1.2 (Android; rail timetable app)")
        assertEquals(listOf(403, 429), (0 until manners.getJSONArray("refusalStatuses").length()).map(manners.getJSONArray("refusalStatuses")::getInt))
        assertInline("data/live/PublicSourceHttp.kt", "error.status == 403 || error.status == 429 -> error.retryAfter ?: refusalPause",
            "private val refusalPause: java.time.Duration = java.time.Duration.ofMinutes(${manners.getLong("refusalPauseSeconds") / 60})")
        val breaker = SourceCircuitBreaker(Clock.systemUTC())
        val breakerClass = SourceCircuitBreaker::class.java
        assertEquals(manners.getInt("failureThreshold"), field(breakerClass, "failureThreshold", breaker))
        assertEquals(manners.getLong("baseOpenSeconds"), seconds(field(breakerClass, "baseOpenDuration", breaker)))
        assertEquals(manners.getLong("maximumOpenSeconds"), seconds(field(breakerClass, "maxOpenDuration", breaker)))
        assertEquals(manners.getLong("maximumRetryAfterSeconds"), seconds(field(breakerClass, "maxRetryAfter", breaker)))
        val service = RailNoticesService(emptyList())
        assertEquals(manners.getLong("sourceTimeoutSeconds") * 1_000, field(RailNoticesService::class.java, "sourceTimeoutMillis", service))
        assertEquals(manners.getLong("reloadAfterSeconds") * 1_000,
            field(Class.forName("it.binario.app.RailNoticesViewModel"), "RELOAD_AFTER_MILLIS"))
    }

    @Test fun `each feed has Android's address, gate, limits, rate and cache`() {
        val mit = source("mit-strikes-rss")
        assertEquals(MitStrikeRssAdapter.URL, mit.getString("url"))
        assertEquals("all-builds", mit.getString("gate"))
        val strikes = MitStrikeRssAdapter(Clock.systemUTC())
        assertEquals(mit.getLong("minimumIntervalSeconds"), strikes.descriptor.ratePolicy.minimumRequestInterval.seconds)
        assertEquals(mit.getLong("timeToLiveSeconds"), MitStrikeRssAdapter.TTL.seconds)
        assertEquals(mit.getLong("maximumStaleSeconds"), seconds(field(MitStrikeRssAdapter::class.java, "MAX_STALE")))
        assertCache(strikes, MitStrikeRssAdapter::class.java, mit)
        val mitFetcher = field(MitStrikeRssAdapter::class.java, "fetcher", strikes)!!
        assertEquals(mit.getInt("maximumBytes"), field(mitFetcher.javaClass, "maxBytes", mitFetcher))
        assertEquals(mit.getInt("maximumRequests"), field(mitFetcher.javaClass, "MAX_HOPS"))
        assertInline("data/live/MitStrikeRssAdapter.kt",
            "withTimeoutOrNull(${literal(mit.getLong("totalTimeoutSeconds") * 1_000)}) {",
            "connection.connectTimeout = ${literal(mit.getLong("connectTimeoutSeconds") * 1_000)}",
            "connection.readTimeout = ${literal(mit.getLong("readTimeoutSeconds") * 1_000)}",
            "connection.setRequestProperty(\"Accept\", \"${mit.getString("accept")}\")",
            "La fonte deve usare HTTPS sullo stesso host")
        assertEquals("same-https-host", mit.getString("redirects"))

        val rfi = source("rfi-infomobility-rss")
        assertEquals(RfiInfomobilityRssAdapter.NATIONAL_UPDATES_URL, rfi.getString("url"))
        assertEquals("all-builds", rfi.getString("gate"))
        val rfiAdapter = RfiInfomobilityRssAdapter()
        assertEquals(rfi.getLong("minimumIntervalSeconds"), rfiAdapter.descriptor.ratePolicy.minimumRequestInterval.seconds)
        assertEquals(rfi.getLong("timeToLiveSeconds"), seconds(field(RfiInfomobilityRssAdapter::class.java, "cacheTtl", rfiAdapter)))
        assertInline("data/live/RfiInfomobilityRssAdapter.kt",
            "!clock.instant().isAfter(it.expiresAt.plus(Duration.ofHours(${rfi.getLong("maximumStaleSeconds") / 3_600})))")
        assertPolicy(field(RfiInfomobilityRssAdapter::class.java, "fetcher", rfiAdapter)!!, "http", rfi)

        val trenitalia = source("trenitalia-infomobility-news")
        assertEquals(TrenitaliaInfomobilityNewsAdapter.URL, trenitalia.getString("url"))
        val news = TrenitaliaInfomobilityNewsAdapter(Clock.systemUTC())
        assertEquals(trenitalia.getLong("minimumIntervalSeconds"), news.descriptor.ratePolicy.minimumRequestInterval.seconds)
        assertEquals(trenitalia.getLong("timeToLiveSeconds"), seconds(field(TrenitaliaInfomobilityNewsAdapter::class.java, "TTL")))
        assertEquals(trenitalia.getInt("maximumBytes"), field(TrenitaliaInfomobilityNewsAdapter::class.java, "MAX_BYTES"))
        assertCache(news, TrenitaliaInfomobilityNewsAdapter::class.java, trenitalia)
        assertPolicy(field(TrenitaliaInfomobilityNewsAdapter::class.java, "fetcher", news)!!, null, trenitalia)
        val refusals = field(TrenitaliaInfomobilityNewsAdapter::class.java, "refusals", news)!!
        assertEquals(trenitalia.getString("pauseLabel"), field(refusals.javaClass, "label", refusals))

        val trenord = source("trenord-line-notices")
        assertEquals(TrenordLineNoticesAdapter.URL, trenord.getString("url"))
        val lines = TrenordLineNoticesAdapter(Clock.systemUTC())
        assertEquals(trenord.getLong("minimumIntervalSeconds"), lines.descriptor.ratePolicy.minimumRequestInterval.seconds)
        assertEquals(trenord.getLong("timeToLiveSeconds"), seconds(field(TrenordLineNoticesAdapter::class.java, "TTL")))
        assertEquals(trenord.getInt("maximumBytes"), field(TrenordLineNoticesAdapter::class.java, "MAX_BYTES"))
        assertCache(lines, TrenordLineNoticesAdapter::class.java, trenord)
        assertPolicy(field(TrenordLineNoticesAdapter::class.java, "fetcher", lines)!!, null, trenord)

        // RFI's feed and the MIT calendar in every build; Trenitalia and Trenord only with the personal sources.
        assertEquals("personal", trenitalia.getString("gate"))
        assertEquals("personal", trenord.getString("gate"))
        assertInline("data/LocalOnlyRailDataPolicy.kt",
            "fun networkNoticeProviders(): List<AlertProvider> = listOf(rfiAlerts) + personalNoticeProviders()",
            "if (BuildConfig.PERSONAL_REALTIME_SOURCES) listOf(trenitaliaNews) else emptyList()",
            "if (BuildConfig.PERSONAL_REALTIME_SOURCES) listOf(trenordNotices) else emptyList()",
            "fun strikeCalendarProvider(): it.binario.app.data.live.MitStrikeRssAdapter = mitStrikes")
        for (entry in sources) {
            val publisher = entry.optString("publisher").takeUnless { entry.isNull("publisher") }
            assertEquals(entry.getString("id"), publisher, RailNoticesService.publisherOf(SourceId(entry.getString("id")))?.name)
        }
    }

    private fun assertCache(adapter: Any, owner: Class<*>, source: JSONObject) {
        val cache = field(owner, "cache", adapter)!!
        assertEquals(source.getLong("timeToLiveSeconds"), seconds(field(cache.javaClass, "ttl", cache)))
        assertEquals(source.getLong("maximumStaleSeconds"), seconds(field(cache.javaClass, "maxStale", cache)))
        assertEquals(source.optLong("retryDelayMillis"), field(cache.javaClass, "retryDelayMillis", cache))
    }

    /** The [PublicSourceRequestPolicy] of a fetcher (inside its [wrapper] field when it has one). */
    private fun assertPolicy(fetcher: Any, wrapper: String?, source: JSONObject) {
        val http = wrapper?.let { field(fetcher.javaClass, it, fetcher)!! } ?: fetcher
        val policy = field(http.javaClass, "policy", http) as PublicSourceRequestPolicy
        assertEquals(source.getString("accept"), policy.accept)
        assertEquals(source.getLong("connectTimeoutSeconds") * 1_000, policy.connectTimeoutMillis.toLong())
        assertEquals(source.getLong("readTimeoutSeconds") * 1_000, policy.readTimeoutMillis.toLong())
        assertEquals(source.getLong("totalTimeoutSeconds") * 1_000, policy.totalTimeoutMillis)
        assertEquals(source.getInt("maximumBytes"), policy.maxBytes)
        assertEquals(source.getBoolean("viaggiaTrenoCleartextHop"), policy.allowViaggiaTrenoCleartextRedirect)
        assertEquals(source.getInt("maximumRequests"), field(http.javaClass, "MAX_HOPS"))
        assertEquals("same-resource", source.getString("redirects"))
        assertInline("data/live/PublicSourceHttp.kt", "if (target.path != requested.path || target.query != requested.query) return false")
    }

    @Test fun `the links are the pages the app opens on a tap`() {
        val links = table.getJSONObject("links")
        assertEquals(RailExternalLinks.MIT_STRIKE_CALENDAR, links.getString("mitStrikeCalendar"))
        assertEquals(OfficialLinks.FS_NEWS, links.getString("fsNews"))
        val guaranteed = links.getJSONObject("guaranteedTrains")
        assertEquals(setOf("TRENITALIA", "TRENORD"), guaranteed.keySet())
        for (publisher in NoticePublisher.entries) {
            assertEquals(publisher.name, guaranteed.optString(publisher.name).ifEmpty { null }, OfficialLinks.guaranteedTrainsOf(publisher.name))
        }
    }

    @Test fun `the text, link and strike tables are Android's`() {
        val text = table.getJSONObject("text")
        val noticeText = Class.forName("it.binario.app.data.notices.NoticeText")
        assertEquals(text.getInt("maximumInput"), field(noticeText, "MAX_INPUT"))
        assertEquals(text.getString("strikeWordPattern"), pattern(noticeText, "STRIKE"))
        assertEquals(text.getString("urlPattern"), pattern(NoticeLinks::class.java, "URL"))
        assertEquals(text.getString("urlTrailing"), field(NoticeLinks::class.java, "TRAILING"))
        assertEquals(text.getInt("minimumUrlLength"), field(NoticeLinks::class.java, "MIN_URL_LENGTH"))
        assertEquals(strings(text.getJSONArray("cleartextOfficialDomains")),
            field(Class.forName("it.binario.app.data.notices.CleartextNoticeLinks"), "OFFICIAL_DOMAINS"))
        assertInline("data/notices/RailNoticesService.kt",
            "NoticeText.normalize(title.value, ${text.getInt("noticeTitleLength")})",
            "NoticeText.fromHtml(it, ${literal(text.getLong("noticeBodyLength"))})")

        val rss = table.getJSONObject("rss")
        val parser = Class.forName("it.binario.app.data.live.Rss2Parser")
        assertEquals(rss.getInt("maximumItems"), field(parser, "MAX_ITEMS"))
        assertEquals(rss.getInt("maximumText"), field(parser, "MAX_TEXT"))

        val strikes = table.getJSONObject("strikes")
        val calendar = StrikeCalendar::class.java
        assertEquals(strikes.getString("railWordsPattern"), pattern(calendar, "railWords"))
        assertEquals(strikes.getString("startLabelPattern"), pattern(calendar, "startLabel"))
        assertEquals(strikes.getString("endLabelPattern"), pattern(calendar, "endLabel"))
        assertEquals(strikes.getInt("upcomingShown"), StrikeCalendar.UPCOMING_SHOWN)
        assertEquals(strikes.getLong("undatedRecentDays"), StrikeCalendar.UNDATED_RECENT.toDays())
        assertEquals(strings(table.getJSONArray("months")), field(calendar, "months"))
        assertInline("data/live/MitStrikeRssAdapter.kt",
            "value.substring(maxOf(0, item.position - ${strikes.getInt("labelWindow")}), item.position)",
            "if (month < reference.monthValue - ${strikes.getInt("inferNextYearMonthsBack")}) reference.year + 1 else reference.year",
            "NoticeText.fromHtml(item.title, ${strikes.getInt("titleLength")})",
            "NoticeText.fromHtml(item.description, ${literal(strikes.getLong("descriptionLength"))})")
        assertEquals(strings(table.getJSONArray("months")), field(Class.forName("it.binario.app.data.notices.NoticeDateParser"), "MONTHS"))
    }

    @Test fun `the Trenitalia, Trenord and route works tables are Android's`() {
        val trenitalia = table.getJSONObject("trenitalia")
        val news = TrenitaliaInfomobilityNewsAdapter::class.java
        assertEquals(trenitalia.getInt("maximumItems"), field(news, "MAX_ITEMS"))
        assertEquals(trenitalia.getInt("titleLength"), field(news, "MAX_TITLE"))
        assertEquals(trenitalia.getInt("descriptionLength"), field(news, "MAX_DESCRIPTION"))
        assertEquals(trenitalia.getString("trainTagPattern"), pattern(news, "TRAIN_TAG"))
        assertInline("data/live/TrenitaliaInfomobilityNewsAdapter.kt",
            "CleartextNoticeLinks.link(item.text(\"link\")?.take(${trenitalia.getInt("linkLength")}))",
            "(0 until minOf(tags.length(), ${trenitalia.getInt("maximumTrainTags")}))")

        val trenord = table.getJSONObject("trenord")
        val lines = TrenordLineNoticesAdapter::class.java
        assertEquals(trenord.getInt("maximumLines"), field(lines, "MAX_LINES"))
        assertEquals(trenord.getInt("maximumNewsPerLine"), field(lines, "MAX_NEWS_PER_LINE"))
        assertEquals(trenord.getInt("textLength"), field(lines, "MAX_TEXT"))
        assertEquals(trenord.getInt("titleLength"), field(lines, "MAX_TITLE"))
        assertEquals(trenord.getString("lineCodePattern"), pattern(lines, "LINE_CODE"))
        assertEquals(trenord.getString("operationalPattern"), pattern(lines, "OPERATIONAL"))
        assertEquals(trenord.getInt("hubLineCount"), field(TrenordLineMatcher::class.java, "HUB_LINE_COUNT"))
        val area = trenord.getJSONObject("area")
        assertInline("data/live/TrenordLineNoticesAdapter.kt",
            "item.text(\"nome\")?.take(${trenord.getInt("lineCodeLength")})",
            "item.text(\"descrizione\")?.take(${trenord.getInt("lineDescriptionLength")})",
            "description.split(" + strings(trenord.getJSONArray("stationTokenSeparators")).joinToString(", ") { "'$it'" } + ")",
            ".filter { it.length >= ${trenord.getInt("minimumTokenLength")} }")
        assertInline("data/notices/TrenordLineMatcher.kt",
            "alert.affectedLines.size >= maxOf(${trenord.getInt("networkWideMinimumLines")}, (lines.size + 2) / 3)",
            "latitude in ${area.getDouble("minimumLatitude")}..${area.getDouble("maximumLatitude")} && " +
                "longitude in ${area.getDouble("minimumLongitude")}..${"%.2f".format(java.util.Locale.ROOT, area.getDouble("maximumLongitude"))}")
        assertInline("StationLineNoticesSection.kt", "if (id.startsWith(\"${trenord.getString("gtfsStationPrefix")}\")) return true")

        val works = table.getJSONObject("routeWorks")
        val matcher = RouteWorksMatcher::class.java
        assertEquals(works.getInt("maximumRoutes"), RouteWorksViewModel.MAX_ROUTES)
        assertEquals(works.getInt("minimumNameLength"), field(matcher, "MIN_NAME_LENGTH"))
        assertEquals(works.getString("worksPattern"), pattern(matcher, "WORKS"))
        assertEquals(works.getString("linePattern"), pattern(matcher, "LINE"))
        assertEquals(strings(works.getJSONArray("commonWords")).toSet(), field(matcher, "COMMON_WORDS"))

        val display = table.getJSONObject("display")
        assertEquals(display.getInt("networkCollapsed"), field(Class.forName("it.binario.app.RailNoticesAreaKt"), "COLLAPSED_COUNT"))
        val components = Class.forName("it.binario.app.NoticeComponentsKt")
        assertEquals(display.getInt("bodyCollapsedLines"), field(components, "COLLAPSED_LINES"))
        assertEquals(display.getInt("bodyCollapsedCharacters"), field(components, "COLLAPSED_CHARACTERS"))
        assertEquals(display.getInt("maximumLineCodes"), field(components, "MAX_LINE_CODES"))
        assertEquals(display.getInt("worksCollapsed"), field(Class.forName("it.binario.app.ui.departures.RouteWorksSectionKt"), "COLLAPSED_NOTICES"))
    }
}
