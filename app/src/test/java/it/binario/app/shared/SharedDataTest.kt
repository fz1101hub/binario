package it.binario.app.shared

import it.binario.app.data.AirportRoutes
import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.gtfs.ItaloGtfsStationCodes
import it.binario.app.data.live.MitStrikeRssAdapter
import it.binario.app.data.live.RfiInfomobilityRssAdapter
import it.binario.app.data.live.SourceCircuitBreaker
import it.binario.app.data.live.StrikeCalendar
import it.binario.app.data.live.TrenitaliaInfomobilityNewsAdapter
import it.binario.app.data.live.TrenordLineNoticesAdapter
import it.binario.app.data.live.binarioUserAgent
import it.binario.app.data.live.noticeFeed
import it.binario.app.data.live.requestPolicy
import it.binario.app.data.notices.NoticePublisher
import it.binario.app.data.notices.RailExternalLinks
import it.binario.app.data.notices.RailNoticesService
import it.binario.app.data.notices.TrenordLineMatcher
import it.binario.app.data.sources.PlannedPackClass
import it.binario.app.data.sources.PlannedPackOrder
import it.binario.app.links.OfficialLinks
import it.binario.app.ui.departures.RouteWorksViewModel
import it.binario.app.data.live.ItaloPublicStationFallbacks
import it.binario.app.data.live.ItaloStationSlugs
import it.binario.app.data.live.RfiStationIds
import it.binario.app.data.planned.ItalianRailServiceCalendar
import it.binario.app.data.reliability.NetworkReliability
import it.binario.app.data.reliability.NetworkReliabilityAggregates
import it.binario.app.data.repository.TrainNumberQuery
import it.binario.app.data.sources.RailInformationCategory
import it.binario.app.data.sources.RailSourceSelectionPolicy
import it.binario.app.plannedjourney.PlannedJourneyPersistenceSchema
import it.binario.app.plannedjourney.PlannedJourneyReminderMinutes
import it.binario.app.plannedjourney.PlannedJourneyTemplates
import it.binario.app.plannedjourney.PlannedJourneyTransferContract
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * contracts/shared-data/v1: each table read from its JSON file, as the iPhone and Alexa tests read it, against the
 * generated [SharedData] and against the code that uses it.
 */
class SharedDataTest {
    private val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun table(name: String) = JSONObject(File(repositoryRoot, "contracts/shared-data/v1/$name").readText(Charsets.UTF_8))
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun JSONArray.ints() = (0 until length()).map(::getInt)
    // org.json keeps no key order: the maps are compared as maps.
    private fun JSONObject.entries(): Map<String, Any> = keys().asSequence().associateWith { get(it) }

    @Test fun `italo fallback stations and slugs are the shared table`() {
        val json = table("italo-stations-v1.json")
        val stations = json.getJSONArray("stations").objects()
        assertEquals(stations.map { it.getString("code") to it.getString("name") }, ItaloPublicStationFallbacks.all.map { it.code to it.name })
        assertEquals(
            stations.map { it.optJSONArray("aliases")?.strings()?.toSet().orEmpty() },
            ItaloPublicStationFallbacks.all.map { it.aliases }
        )
        assertEquals(stations.map { it.getString("code") }, SharedData.ItaloStations.STATIONS.map { it.code })
        assertEquals("milano-rho-fiera", ItaloPublicStationFallbacks.all.single { it.code == "RRO" }.slug)
        val slugs = json.getJSONObject("slugsByName").entries()
        assertEquals(slugs, SharedData.ItaloStations.SLUGS_BY_NAME)
        for ((name, slug) in slugs) assertEquals(name, slug, ItaloStationSlugs.slug("not-a-legacy-id", name))
    }

    @Test fun `rfi place ids by name are the shared table`() {
        val places = table("rfi-place-ids-v1.json").getJSONObject("placeIdsByName").entries()
        assertEquals(places, SharedData.RfiPlaceIds.PLACE_IDS_BY_NAME)
        for ((name, id) in places) assertEquals(name, id, RfiStationIds.placeId("not-a-legacy-id", name))
    }

    @Test fun `source scores operator bonus and coverage fallbacks are the shared table`() {
        val json = table("source-scores-v1.json")
        assertEquals(json.getJSONArray("categories").strings(), RailInformationCategory.entries.map { it.name })
        val preferences = json.getJSONArray("preferences").objects()
        assertEquals(preferences.size, SharedData.SourceScores.PREFERENCES.size)
        for (preference in preferences) {
            val category = RailInformationCategory.valueOf(preference.getString("category"))
            val source = SourceId(preference.getString("source"))
            assertEquals("$source $category", preference.getInt("score"), RailSourceSelectionPolicy.score(category, source))
            assertTrue(RailSourceSelectionPolicy.ranked(category).any { it.sourceId == source && it.reason == preference.getString("reason") })
        }
        val defaultScore = json.getInt("defaultScore")
        assertEquals(defaultScore, RailSourceSelectionPolicy.score(RailInformationCategory.STATION_BOARD, SourceId("not-a-source")))
        for (rule in json.getJSONArray("operatorAffinity").objects()) {
            val points = rule.getJSONObject("pointsByCategory")
            for (source in rule.getJSONArray("sources").strings()) for (category in RailInformationCategory.entries) {
                val base = preferences.firstOrNull { it.getString("source") == source && it.getString("category") == category.name }
                    ?.getInt("score") ?: defaultScore
                for (keyword in rule.getJSONArray("operatorKeywords").strings()) {
                    assertEquals("$source $category $keyword", base + points.getInt(category.name),
                        RailSourceSelectionPolicy.score(category, SourceId(source), keyword))
                }
                assertEquals("$source $category without operator", base, RailSourceSelectionPolicy.score(category, SourceId(source), "Zzz"))
            }
        }
        val fallbacks = json.getJSONArray("coverageFallbacks").strings()
        assertEquals(fallbacks.toSet(), SharedData.SourceScores.COVERAGE_FALLBACKS)
        for (source in fallbacks) assertTrue(source, RailSourceSelectionPolicy.isCoverageFallback(SourceId(source)))
        assertFalse(RailSourceSelectionPolicy.isCoverageFallback(SourceId("trenitalia-gtfs")))
    }

    @Test fun `punctuality thresholds are the shared table`() {
        val json = table("punctuality-v1.json")
        val categories = json.getJSONArray("highSpeedCategories").strings()
        assertEquals(categories.toSet(), SharedData.Punctuality.HIGH_SPEED_CATEGORIES)
        for (category in categories) assertEquals(category, json.getInt("highSpeedOnTimeMinutes"), NetworkReliability.onTimeLimitMinutes(category))
        assertEquals(json.getInt("otherOnTimeMinutes"), NetworkReliability.onTimeLimitMinutes("REG"))
        assertEquals(json.getInt("highSpeedOnTimeMinutes"), NetworkReliability.HIGH_SPEED_ON_TIME_MINUTES)
        assertEquals(json.getInt("otherOnTimeMinutes"), NetworkReliability.OTHER_ON_TIME_MINUTES)
        assertEquals(json.getInt("minimumSamples"), NetworkReliability.MIN_SAMPLES)
        assertEquals(json.getInt("minimumPlatformDays"), NetworkReliability.MIN_PLATFORM_DAYS)
        assertEquals(json.getInt("minimumPlatformSharePercent") / 100.0, NetworkReliability.MIN_PLATFORM_SHARE, 0.0)
        assertEquals(0.6, NetworkReliability.MIN_PLATFORM_SHARE, 0.0)
        assertEquals(json.getInt("minimumWeekdaySamples"), NetworkReliability.MIN_WEEKDAY_SAMPLES)
        assertEquals(json.getInt("minimumWeekdayGapPoints"), NetworkReliability.MIN_WEEKDAY_GAP_POINTS)
        assertEquals(json.getInt("minimumAggregateSamples"), NetworkReliabilityAggregates.MIN_AGGREGATE_SAMPLES)
        assertEquals(json.getInt("minimumBandSamples"), NetworkReliabilityAggregates.MIN_BAND_SAMPLES)
        assertEquals(json.getInt("minimumBandGapPoints"), NetworkReliabilityAggregates.MIN_BAND_GAP_POINTS)
    }

    @Test fun `train number prefixes are the shared table`() {
        val categories = table("train-number-prefixes-v1.json").getJSONArray("categories").strings()
        assertEquals(categories, SharedData.TrainNumberPrefixes.CATEGORIES)
        val date = LocalDate.of(2026, 11, 23)
        for (category in categories) {
            assertEquals(category, "123", TrainNumberQuery("$category 0123", date).numeric)
            assertEquals(category, null, TrainNumberQuery("${category}Q123", date).numeric)
        }
    }

    @Test fun `airport routes and their city lines are the shared table`() {
        val json = table("airport-routes-v1.json")
        val routes = json.getJSONArray("routes").objects()
        assertEquals(
            routes.map { Triple("curated:${it.getString("city")}", "curated:${it.getString("airport")}", it.getJSONArray("packs").strings().toSet()) },
            AirportRoutes.all.map { Triple(it.cityStationId, it.airportStationId, it.packs) }
        )
        val cityLines = json.getJSONArray("packs").objects().filter { it.getBoolean("cityLine") }.map { it.getString("id") }.toSet()
        assertEquals(setOf("marconi-express-gtfs"), cityLines)
        for (route in AirportRoutes.all) assertEquals(route.cityStationId, route.packs.all(cityLines::contains), AirportRoutes.byCityLine(route))
    }

    @Test fun `holidays are the shared calendar`() {
        val json = table("italian-holidays-v1.json")
        val holidays = json.getJSONArray("holidays").objects()
        assertEquals(holidays.map { it.getInt("month") to it.getInt("day") }, SharedData.ItalianHolidays.HOLIDAYS.map { it.month to it.day })
        for (holiday in holidays) for (year in 2024..2028) {
            val date = LocalDate.of(year, holiday.getInt("month"), holiday.getInt("day"))
            val fromYear = holiday.optInt("fromYear", Int.MIN_VALUE)
            assertEquals("$date", year >= fromYear, ItalianRailServiceCalendar.isHoliday(date))
        }
        val easter2026 = LocalDate.of(2026, 4, 5)
        val offsets = json.getJSONArray("easterOffsetsDays").ints()
        assertEquals(offsets, SharedData.ItalianHolidays.EASTER_OFFSETS_DAYS)
        for (offset in -2..3) {
            val date = easter2026.plusDays(offset.toLong())
            assertEquals("$date", offset in offsets, ItalianRailServiceCalendar.isHoliday(date))
        }
    }

    @Test fun `agenda and template limits are the shared table`() {
        val json = table("planned-journeys-v1.json")
        assertEquals(json.getInt("schemaVersion"), PlannedJourneyPersistenceSchema.VERSION)
        assertEquals(json.getInt("schemaVersion"), PlannedJourneyTransferContract.VERSION)
        assertEquals(json.getString("transferKind"), PlannedJourneyTransferContract.KIND)
        assertEquals(json.getInt("maximumJourneys"), PlannedJourneyPersistenceSchema.MAX_JOURNEYS)
        assertEquals(json.getInt("maximumSegments"), PlannedJourneyPersistenceSchema.MAX_ITINERARY_SEGMENTS)
        assertEquals(json.getInt("maximumChecklistItems"), PlannedJourneyPersistenceSchema.MAX_CHECKLIST_ITEMS)
        assertEquals(json.getInt("maximumOccurrences"), PlannedJourneyPersistenceSchema.MAX_RECURRENCE_OCCURRENCES)
        assertEquals(json.getInt("maximumLabelLength"), PlannedJourneyPersistenceSchema.MAX_LABEL_LENGTH)
        assertEquals(json.getInt("maximumChecklistLabelLength"), PlannedJourneyPersistenceSchema.MAX_CHECKLIST_LABEL_LENGTH)
        assertEquals(json.getInt("maximumIdentifierLength"), PlannedJourneyPersistenceSchema.MAX_IDENTIFIER_LENGTH)
        assertEquals(json.getInt("maximumTransferBytes"), PlannedJourneyTransferContract.MAX_BYTES)
        assertEquals(json.getInt("maximumNestingDepth"), PlannedJourneyTransferContract.MAX_NESTING_DEPTH)
        assertEquals(json.getString("templatesKind"), SharedData.PlannedJourneys.TEMPLATES_KIND)
        assertEquals(json.getInt("maximumTemplates"), PlannedJourneyTemplates.MAX_TEMPLATES)
        assertEquals(json.getInt("maximumTemplateNameLength"), PlannedJourneyTemplates.MAX_NAME_LENGTH)
        assertEquals(json.getInt("maximumTemplatesBytes"), SharedData.PlannedJourneys.MAXIMUM_TEMPLATES_BYTES)
        // The reminder leads stay an enum (the editor's labels): it must list the table's values, in its order.
        assertEquals(json.getJSONArray("reminderMinutes").ints(), PlannedJourneyReminderMinutes.entries.map { it.minutes })
    }

    @Test fun `italo gtfs station codes are the shared table`() {
        val json = table("italo-gtfs-station-codes.json")
        val codes = json.getJSONArray("codes").objects()
        assertEquals(codes.map { it.getString("code") to it.getString("catalogueId") }, SharedData.ItaloGtfsStationCodes.CODES.map { it.code to it.catalogueId })
        assertEquals(codes.size, ItaloGtfsStationCodes.size)
        for (code in codes) assertEquals(code.getString("code"), "curated:${code.getString("catalogueId")}", ItaloGtfsStationCodes.catalogueId(code.getString("code")))
        val unmapped = json.getJSONArray("unmapped").objects().map { it.getString("code") }.toSet()
        assertEquals(unmapped, ItaloGtfsStationCodes.unmapped)
        for (code in unmapped) assertEquals(code, null, ItaloGtfsStationCodes.catalogueId(code))
    }

    @Test fun `pack roles are the shared table`() {
        val json = table("pack-roles-v1.json")
        fun set(key: String) = json.getJSONArray(key).strings().toSet()
        assertEquals(set("nationalPacks"), SharedData.PackRoles.NATIONAL_PACKS)
        assertEquals(set("nationalPacks"), PlannedPackOrder.nationalPacks.map { it.value }.toSet())
        assertEquals(set("crossPublishingPacks"), SharedData.PackRoles.CROSS_PUBLISHING_PACKS)
        assertEquals(set("scheduledCoachPacks"), SharedData.PackRoles.SCHEDULED_COACH_PACKS)
        val packs = EmbeddedGtfsPacks.forBuild(personalSources = true).filterNot { it.isUrban }
        assertEquals(set("scheduledCoachPacks"), packs.filter { it.busRoutesAreScheduledCoaches }.map { it.sourceId.value }.toSet())
        // Android derives a foreign pack from its first catalogue country: for every railway pack it is the table's set.
        assertEquals(set("foreignPacks"), SharedData.PackRoles.FOREIGN_PACKS)
        for (pack in packs) {
            assertEquals(pack.sourceId.value, pack.sourceId.value in set("foreignPacks"), PlannedPackOrder.classOf(pack.sourceId) == PlannedPackClass.FOREIGN)
        }
    }

    @Test fun `every source is asked with the shared manners`() {
        val json = table("source-manners-v1.json")
        assertEquals("Binario/1.2 (Android; rail timetable app)", binarioUserAgent("1.2"))
        assertEquals(json.getString("userAgent").replace("<version>", "0").replace("<platform>", "Android"), binarioUserAgent("é"))
        assertEquals(json.getJSONArray("refusalStatuses").ints().toSet(), SharedData.SourceManners.REFUSAL_STATUSES)
        assertEquals(json.getInt("refusalPauseSeconds"), SharedData.SourceManners.REFUSAL_PAUSE_SECONDS)
        val now = Instant.parse("2026-11-23T10:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val breaker = SourceCircuitBreaker(clock)
        repeat(json.getInt("failureThreshold") - 1) { breaker.recordFailure() }
        assertTrue(breaker.allowRequest())
        breaker.recordFailure()
        assertEquals(now.plusSeconds(json.getLong("baseOpenSeconds")), breaker.pausedUntil())
        val asked = SourceCircuitBreaker(clock)
        asked.recordFailure(Duration.ofDays(1))
        assertEquals(now.plusSeconds(json.getLong("maximumRetryAfterSeconds")), asked.pausedUntil())
        assertEquals(json.getInt("maximumOpenSeconds"), SharedData.SourceManners.MAXIMUM_OPEN_SECONDS)
    }

    @Test fun `the notice feeds are the shared table`() {
        val json = table("notices-sources-v1.json")
        val sources = json.getJSONArray("sources").objects()
        assertEquals(sources.map { it.getString("id") }, SharedData.NoticesSources.SOURCES.map { it.id })
        for (source in sources) {
            val id = source.getString("id")
            val feed = noticeFeed(id)
            assertEquals(id, source.getString("url"), feed.url)
            assertEquals(id, source.getString("gate"), feed.gate)
            assertEquals(id, source.getLong("timeToLiveSeconds"), feed.timeToLiveSeconds.toLong())
            assertEquals(id, source.getLong("maximumStaleSeconds"), feed.maximumStaleSeconds.toLong())
            assertEquals(id, source.optLong("retryDelayMillis").takeUnless { source.isNull("retryDelayMillis") }, feed.retryDelayMillis?.toLong())
            assertEquals(id, source.optString("pauseLabel").takeUnless { source.isNull("pauseLabel") }, feed.pauseLabel)
            val policy = feed.requestPolicy()
            assertEquals(id, source.getString("accept"), policy.accept)
            assertEquals(id, source.getLong("connectTimeoutSeconds") * 1_000, policy.connectTimeoutMillis.toLong())
            assertEquals(id, source.getLong("readTimeoutSeconds") * 1_000, policy.readTimeoutMillis.toLong())
            assertEquals(id, source.getLong("totalTimeoutSeconds") * 1_000, policy.totalTimeoutMillis)
            assertEquals(id, source.getInt("maximumBytes"), policy.maxBytes)
            assertEquals(id, source.getInt("maximumRequests"), policy.maxRequests)
            assertEquals(id, source.getBoolean("viaggiaTrenoCleartextHop"), policy.allowViaggiaTrenoCleartextRedirect)
            val publisher = source.optString("publisher").takeUnless { source.isNull("publisher") }
            assertEquals(id, publisher, RailNoticesService.publisherOf(SourceId(id))?.name)
        }
        fun interval(id: String) = sources.single { it.getString("id") == id }.getLong("minimumIntervalSeconds")
        fun ttl(id: String) = sources.single { it.getString("id") == id }.getLong("timeToLiveSeconds")
        val mit = MitStrikeRssAdapter(Clock.systemUTC())
        assertEquals(noticeFeed("mit-strikes-rss").url, MitStrikeRssAdapter.URL)
        assertEquals(interval("mit-strikes-rss"), mit.descriptor.ratePolicy.minimumRequestInterval.seconds)
        assertEquals(ttl("mit-strikes-rss"), MitStrikeRssAdapter.TTL.seconds)
        val rfi = RfiInfomobilityRssAdapter()
        assertEquals(noticeFeed("rfi-infomobility-rss").url, RfiInfomobilityRssAdapter.NATIONAL_UPDATES_URL)
        assertEquals(interval("rfi-infomobility-rss"), rfi.descriptor.ratePolicy.minimumRequestInterval.seconds)
        assertEquals(ttl("rfi-infomobility-rss"), rfi.descriptor.ratePolicy.defaultTimeToLive.seconds)
        val news = TrenitaliaInfomobilityNewsAdapter(Clock.systemUTC())
        assertEquals(noticeFeed("trenitalia-infomobility-news").url, TrenitaliaInfomobilityNewsAdapter.URL)
        assertEquals(interval("trenitalia-infomobility-news"), news.descriptor.ratePolicy.minimumRequestInterval.seconds)
        assertEquals(ttl("trenitalia-infomobility-news"), news.descriptor.ratePolicy.defaultTimeToLive.seconds)
        val lines = TrenordLineNoticesAdapter(Clock.systemUTC())
        assertEquals(noticeFeed("trenord-line-notices").url, TrenordLineNoticesAdapter.URL)
        assertEquals(interval("trenord-line-notices"), lines.descriptor.ratePolicy.minimumRequestInterval.seconds)
        assertEquals(ttl("trenord-line-notices"), lines.descriptor.ratePolicy.defaultTimeToLive.seconds)
    }

    @Test fun `the notice links, readers and display limits are the shared table`() {
        val json = table("notices-sources-v1.json")
        val links = json.getJSONObject("links")
        assertEquals(links.getString("mitStrikeCalendar"), RailExternalLinks.MIT_STRIKE_CALENDAR)
        assertEquals(links.getString("fsNews"), OfficialLinks.FS_NEWS)
        val guaranteed = links.getJSONObject("guaranteedTrains").entries()
        assertEquals(guaranteed, SharedData.NoticesSources.LINKS.guaranteedTrains)
        for (publisher in NoticePublisher.entries) assertEquals(publisher.name, guaranteed[publisher.name], OfficialLinks.guaranteedTrainsOf(publisher.name))
        val strikes = json.getJSONObject("strikes")
        assertEquals(strikes.getInt("upcomingShown"), StrikeCalendar.UPCOMING_SHOWN)
        assertEquals(strikes.getLong("undatedRecentDays"), StrikeCalendar.UNDATED_RECENT.toDays())
        assertEquals(strikes.getString("railWordsPattern"), SharedData.NoticesSources.STRIKES.railWordsPattern)
        assertEquals(json.getJSONArray("months").strings(), SharedData.NoticesSources.MONTHS)
        val text = json.getJSONObject("text")
        assertEquals(text.getString("strikeWordPattern"), SharedData.NoticesSources.TEXT.strikeWordPattern)
        assertEquals(text.getString("urlPattern"), SharedData.NoticesSources.TEXT.urlPattern)
        assertEquals(text.getJSONArray("cleartextOfficialDomains").strings(), SharedData.NoticesSources.TEXT.cleartextOfficialDomains)
        val trenord = json.getJSONObject("trenord")
        assertEquals(trenord.getString("operationalPattern"), SharedData.NoticesSources.TRENORD.operationalPattern)
        assertEquals(trenord.getJSONArray("stationTokenSeparators").strings(), SharedData.NoticesSources.TRENORD.stationTokenSeparators)
        val area = trenord.getJSONObject("area")
        assertTrue(TrenordLineMatcher.inTrenordArea(area.getDouble("minimumLatitude"), area.getDouble("maximumLongitude")))
        assertFalse(TrenordLineMatcher.inTrenordArea(area.getDouble("minimumLatitude") - 0.01, area.getDouble("minimumLongitude")))
        assertFalse(TrenordLineMatcher.inTrenordArea(area.getDouble("maximumLatitude"), area.getDouble("maximumLongitude") + 0.01))
        val works = json.getJSONObject("routeWorks")
        assertEquals(works.getInt("maximumRoutes"), RouteWorksViewModel.MAX_ROUTES)
        assertEquals(works.getString("linePattern"), SharedData.NoticesSources.ROUTE_WORKS.linePattern)
        assertEquals(works.getJSONArray("commonWords").strings(), SharedData.NoticesSources.ROUTE_WORKS.commonWords)
        val service = json.getJSONObject("service")
        assertEquals(service.getInt("sourceTimeoutSeconds"), SharedData.NoticesSources.SERVICE.sourceTimeoutSeconds)
        assertEquals(service.getInt("reloadAfterSeconds"), SharedData.NoticesSources.SERVICE.reloadAfterSeconds)
        val display = json.getJSONObject("display")
        assertEquals(display.getInt("networkCollapsed"), SharedData.NoticesSources.DISPLAY.networkCollapsed)
        assertEquals(display.getInt("bodyCollapsedLines"), SharedData.NoticesSources.DISPLAY.bodyCollapsedLines)
        assertEquals(display.getInt("bodyCollapsedCharacters"), SharedData.NoticesSources.DISPLAY.bodyCollapsedCharacters)
        assertEquals(display.getInt("maximumLineCodes"), SharedData.NoticesSources.DISPLAY.maximumLineCodes)
        assertEquals(display.getInt("worksCollapsed"), SharedData.NoticesSources.DISPLAY.worksCollapsed)
    }
}
