package it.binario.app.shared

import it.binario.app.data.AirportRoutes
import it.binario.app.data.CompensationShare
import it.binario.app.data.DelayCompensationRule
import it.binario.app.data.bikesharing.BikeSharingService
import it.binario.app.data.bikesharing.BikeSharingSystems
import it.binario.app.data.journey.ScheduledRunRequest
import it.binario.app.data.weather.OpenMeteoForecastAdapter
import it.binario.app.maps.LiveTrainAreas
import it.binario.app.maps.LiveTrainRefresh
import it.binario.app.maps.LiveTrainSelector
import it.binario.app.notifications.ApproachAlertPlanner
import it.binario.app.notifications.FollowedTrip
import it.binario.app.notifications.FollowedTripPlanner
import it.binario.app.notifications.RouteWorksAlertPlanner
import it.binario.app.notifications.StrikeReminderPlanner
import it.binario.app.notifications.TrainAlertWatch
import it.binario.app.data.core.SourceId
import it.binario.app.data.gtfs.EmbeddedGtfsPacks
import it.binario.app.data.gtfs.ItaloGtfsStationCodes
import it.binario.app.data.journey.GtfsJourneyPlannerSettings
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
import it.binario.app.data.urban.UrbanStopPlatforms
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
        assertEquals(json.getInt("maximumWeekdayRemarks"), NetworkReliability.MAX_WEEKDAY_REMARKS)
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
        // The city networks are the URBAN packs of every build, as the two manifests file them ("kind": "urban").
        assertEquals(set("urbanPacks"), SharedData.PackRoles.URBAN_PACKS)
        assertEquals(set("urbanPacks"), EmbeddedGtfsPacks.forBuild(personalSources = true).filter { it.isUrban }.map { it.sourceId.value }.toSet())
        val manifests = listOf("src/main/assets/gtfs-transit/manifest.json", "src/personal/assets/gtfs-personal/manifest.json")
        val urban = manifests.flatMap { path ->
            val packList = JSONObject(File(repositoryRoot, "app/$path").readText(Charsets.UTF_8)).getJSONArray("packs")
            (0 until packList.length()).map(packList::getJSONObject).filter { it.getString("kind") == "urban" }.map { it.getString("sourceId") }
        }.toSet()
        assertEquals(set("urbanPacks"), urban)
    }

    @Test fun `the walks to the city stops are the shared table`() {
        val json = table("urban-stops-v1.json")
        val settings = GtfsJourneyPlannerSettings()
        assertEquals(json.getInt("maximumWalkMeters"), settings.maxWalkMeters)
        assertEquals(json.getDouble("walkingSpeedKmh"), settings.walkingSpeedKmh, 0.0)
        assertEquals(json.getInt("walkMarginMinutes"), settings.walkMarginMinutes)
        assertEquals(json.getInt("samePlaceMeters"), SharedData.UrbanStops.SAME_PLACE_METERS)
        assertEquals(json.getInt("platformMeters").toDouble(), UrbanStopPlatforms.SIBLING_METERS, 0.0)
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

    @Test fun `the delay compensation thresholds are the shared table`() {
        val json = table("delay-compensation-v1.json")
        assertEquals(json.getInt("quarterFromMinutes"), DelayCompensationRule.QUARTER_FROM_MINUTES)
        assertEquals(json.getInt("halfFromMinutes"), DelayCompensationRule.HALF_FROM_MINUTES)
        assertEquals(json.getInt("quarterPercent"), CompensationShare.QUARTER.percent)
        assertEquals(json.getInt("halfPercent"), CompensationShare.HALF.percent)
    }

    @Test fun `the destination weather source is the shared table`() {
        val json = table("destination-weather-v1.json")
        assertEquals(json.getString("forecastUrl"), SharedData.DestinationWeather.FORECAST_URL)
        assertEquals(json.getString("attributionUrl"), OpenMeteoForecastAdapter.ATTRIBUTION_URL)
        assertEquals(json.getInt("forecastDays"), OpenMeteoForecastAdapter.FORECAST_DAYS)
        assertEquals(json.getJSONArray("dailyVariables").strings(), SharedData.DestinationWeather.DAILY_VARIABLES)
        assertEquals("45.46", OpenMeteoForecastAdapter.rounded(45.4641))
        assertEquals(json.getInt("timeToLiveSeconds"), SharedData.DestinationWeather.TIME_TO_LIVE_SECONDS)
        assertEquals(json.getInt("maximumBytes"), SharedData.DestinationWeather.MAXIMUM_BYTES)
    }

    @Test fun `the bike sharing systems are the shared table`() {
        val json = table("bike-sharing-v1.json")
        val systems = json.getJSONArray("systems").objects()
        assertEquals(systems.map { it.getString("id") }, BikeSharingSystems.all.map { it.id.value })
        for ((entry, system) in systems.zip(BikeSharingSystems.all)) {
            assertEquals(entry.getString("name"), system.name)
            assertEquals(entry.getString("operatorName"), system.operator)
            assertEquals(entry.getString("discoveryUrl"), system.discoveryUrl)
            assertEquals(entry.getString("websiteUrl"), system.websiteUrl)
            assertEquals(entry.getString("attribution"), system.attribution)
            assertEquals(entry.getBoolean("personalOnly"), system.personalOnly)
            val areas = entry.getJSONArray("areas").objects()
            assertEquals(areas.map { it.getString("name") }, system.areas.map { it.name })
            assertEquals(areas.map { it.getDouble("radiusKm") }, system.areas.map { it.radiusKm })
        }
        assertEquals(listOf("gbfs-bird-roma"), BikeSharingSystems.all.filter { it.personalOnly }.map { it.id.value })
        assertEquals(json.getInt("radiusMeters"), BikeSharingService.DEFAULT_RADIUS_METERS)
        assertEquals(json.getJSONArray("feedLanguages").strings(), SharedData.BikeSharing.FEED_LANGUAGES)
        assertEquals(json.getInt("maximumBytes"), SharedData.BikeSharing.MAXIMUM_BYTES)
    }

    @Test fun `the live train map is the shared table`() {
        val json = table("live-train-map-v1.json")
        assertEquals(json.getInt("lookBackMinutes"), ScheduledRunRequest.DEFAULT_LOOK_BACK_MINUTES)
        assertEquals(json.getInt("lookAheadMinutes"), ScheduledRunRequest.DEFAULT_LOOK_AHEAD_MINUTES)
        assertEquals(json.getInt("lookBackMinutes"), LiveTrainSelector.MAX_APPLIED_DELAY)
        assertEquals(json.getInt("minimumAppliedDelayMinutes"), LiveTrainSelector.MIN_APPLIED_DELAY)
        assertEquals(json.getInt("maximumTrains"), LiveTrainSelector.MAX_TRAINS)
        assertEquals(json.getDouble("sameTrainKilometres"), LiveTrainSelector.SAME_TRAIN_KM, 0.0)
        assertEquals(json.getInt("positionsRefreshSeconds") * 1_000L, LiveTrainRefresh.POSITIONS_MILLIS)
        assertEquals(json.getDouble("stationRadiusKilometres"), LiveTrainAreas.STATION_RADIUS_KM, 0.0)
        assertEquals(json.getDouble("maximumLatitudeSpan"), LiveTrainAreas.MAX_LATITUDE_SPAN, 0.0)
        assertEquals(json.getDouble("maximumLongitudeSpan"), LiveTrainAreas.MAX_LONGITUDE_SPAN, 0.0)
    }

    @Test fun `the evening alerts are the shared table`() {
        val json = table("evening-alerts-v1.json")
        assertEquals(json.getInt("strikeReminderHour"), StrikeReminderPlanner.RUN_AT.hour)
        assertEquals(json.getInt("quietFromHour"), StrikeReminderPlanner.QUIET_FROM.hour)
        assertEquals(json.getLong("strikeNotifiedRetentionDays"), StrikeReminderPlanner.NOTIFIED_RETENTION_DAYS)
        assertEquals(json.getInt("routeWorksAlertHour"), RouteWorksAlertPlanner.RUN_AT.hour)
        assertEquals(json.getInt("quietFromHour"), RouteWorksAlertPlanner.QUIET_FROM.hour)
        assertEquals(json.getInt("quietUntilHour"), RouteWorksAlertPlanner.QUIET_UNTIL.hour)
        assertEquals(json.getInt("routeWorksMaximumItems"), RouteWorksAlertPlanner.MAX_ITEMS)
        assertEquals(json.getLong("routeWorksMaximumKeepDays"), RouteWorksAlertPlanner.MAX_KEEP_DAYS)
        assertEquals(json.getInt("routeWorksMaximumNotified"), RouteWorksAlertPlanner.MAX_NOTIFIED)
    }

    @Test fun `the trip alerts are the shared table`() {
        val json = table("trip-alerts-v1.json")
        fun minutes(key: String): Duration = Duration.ofMinutes(json.getLong(key))
        fun seconds(key: String): Duration = Duration.ofSeconds(json.getLong(key))
        assertEquals(json.getInt("stopAlarmMaximumAlarms"), it.binario.app.StopAlarmPlanner.MAX_ALARMS)
        assertEquals(minutes("stopAlarmNotificationTimeoutMinutes").toMillis(), it.binario.app.StopAlarmPlanner.NOTIFICATION_TIMEOUT_MILLIS)
        assertEquals(minutes("stopAlarmKeepAfterArrivalMinutes").toMillis(), it.binario.app.StopAlarmPlanner.KEEP_AFTER_ARRIVAL_MILLIS)
        assertEquals(seconds("stopAlarmReplanThresholdSeconds").toMillis(), it.binario.app.StopAlarmPlanner.REPLAN_THRESHOLD_MILLIS)
        assertEquals(minutes("followedTripLeadMinutes"), FollowedTripPlanner.LEAD)
        assertEquals(minutes("followedTripTrailMinutes"), FollowedTripPlanner.TRAIL)
        assertEquals(minutes("followedTripRefreshMinutes"), FollowedTripPlanner.INTERVAL)
        assertEquals(minutes("followedTripMaximumDelayExtensionMinutes"), FollowedTripPlanner.MAX_DELAY_EXTENSION)
        assertEquals(json.getInt("followedTripNearAlightStops"), FollowedTripPlanner.NEAR_ALIGHT_STOPS)
        assertEquals(seconds("followedTripNearAlightDelaySeconds"), FollowedTripPlanner.NEAR_ALIGHT_DELAY)
        assertEquals(minutes("followedTripMinimumRefreshMinutes"), FollowedTripPlanner.MIN_REFRESH)
        assertEquals(json.getInt("followedTripMaximumLegs"), FollowedTrip.MAX_LEGS)
        assertEquals(json.getJSONArray("approachLeadOptionsMinutes").ints(), ApproachAlertPlanner.LEAD_OPTIONS)
        assertEquals(json.getInt("approachDefaultLeadMinutes"), ApproachAlertPlanner.DEFAULT_LEAD)
        assertEquals(minutes("approachCheckMinutes"), ApproachAlertPlanner.CHECK_INTERVAL)
        assertEquals(minutes("approachFirstCheckBeforeMinutes"), ApproachAlertPlanner.FIRST_CHECK_BEFORE)
        assertEquals(minutes("approachMaximumDelayMinutes"), ApproachAlertPlanner.MAX_DELAY)
        assertEquals(minutes("approachEarlyToleranceMinutes"), ApproachAlertPlanner.EARLY_TOLERANCE)
        assertEquals(minutes("approachLateToleranceMinutes"), ApproachAlertPlanner.LATE_TOLERANCE)
        assertEquals(minutes("approachMinimumGapMinutes"), ApproachAlertPlanner.MIN_DELAY_BETWEEN_RUNS)
        assertEquals(minutes("approachNotificationTimeoutMinutes"), ApproachAlertPlanner.NOTIFICATION_TIMEOUT)
        assertEquals(json.getInt("approachMaximumAlerts"), ApproachAlertPlanner.MAX_ALERTS)
        assertEquals(json.getJSONArray("commuterLeadOptionsMinutes").ints(), SharedData.TripAlerts.COMMUTER_LEAD_OPTIONS_MINUTES)
        assertEquals(json.getInt("commuterMaximumAlerts"), SharedData.TripAlerts.COMMUTER_MAXIMUM_ALERTS)
        assertEquals(minutes("trainAlertsCheckMinutes"), TrainAlertWatch.INTERVAL)
        assertEquals(minutes("trainAlertsBeforeDepartureMinutes"), TrainAlertWatch.BEFORE_DEPARTURE)
        assertEquals(minutes("trainAlertsAfterDepartureMinutes"), TrainAlertWatch.AFTER_DEPARTURE)
        assertEquals(minutes("trainAlertsAfterArrivalMinutes"), TrainAlertWatch.AFTER_ARRIVAL)
        assertEquals(json.getInt("trainAlertsMaximumSubscriptions"), TrainAlertWatch.MAX_SUBSCRIPTIONS)
        assertEquals(json.getInt("trainAlertsMaximumSeen"), TrainAlertWatch.MAX_SEEN)
        assertEquals(json.getLong("trainAlertsSeenRetentionDays"), TrainAlertWatch.SEEN_RETENTION_DAYS)
        val evening = table("evening-alerts-v1.json")
        assertEquals(evening.getInt("quietFromHour"), TrainAlertWatch.QUIET_START.hour)
        assertEquals(evening.getInt("quietUntilHour"), TrainAlertWatch.QUIET_END.hour)
        assertEquals(json.getInt("positionMaximumRouteStops"), it.binario.app.gpsalarm.GpsTrainRoute.MAX_STOPS)
        assertEquals(seconds("positionMaximumFixAgeSeconds").toMillis(), it.binario.app.gpsalarm.GpsArrivalAlarmPolicy.MAX_FIX_AGE_MILLIS)
        assertEquals(minutes("positionSessionAfterArrivalMinutes").toMillis(), it.binario.app.gpsalarm.GpsArrivalAlarmPolicy.AFTER_ARRIVAL_MILLIS)
        assertEquals(minutes("positionSessionMaximumMinutes").toMillis(), it.binario.app.gpsalarm.GpsArrivalAlarmPolicy.MAX_DURATION_MILLIS)
        assertEquals(json.getDouble("positionMaximumAccuracyMeters"), it.binario.app.gpsalarm.GpsTrainTracking.MAX_ACCURACY_METERS, 0.0)
        assertEquals(json.getDouble("positionOffRouteMeters"), it.binario.app.gpsalarm.GpsTrainTracking.OFF_ROUTE_METERS, 0.0)
        assertEquals(json.getDouble("positionDwellRadiusMeters"), it.binario.app.gpsalarm.GpsTrainTracking.DWELL_RADIUS_METERS, 0.0)
        assertEquals(json.getDouble("positionBacktrackToleranceMeters"), it.binario.app.gpsalarm.GpsTrainTracking.BACKTRACK_TOLERANCE_METERS, 0.0)
        assertEquals(seconds("positionFreshSeconds").toMillis(), it.binario.app.gpsalarm.GpsTrainTracking.POSITION_FRESH_MILLIS)
        assertEquals(seconds("estimateFreshSeconds").toMillis(), it.binario.app.gpsalarm.GpsTrainTracking.ESTIMATE_FRESH_MILLIS)
        assertEquals(json.getInt("estimateMaximumDelayMinutes"), it.binario.app.gpsalarm.GpsTrainTracking.MAX_DELAY_MINUTES)
        assertEquals(minutes("estimateBeforeDepartureMinutes").toMillis(), it.binario.app.gpsalarm.GpsTrainTracking.BEFORE_DEPARTURE_MILLIS)
        assertEquals(minutes("estimateAfterArrivalMinutes").toMillis(), it.binario.app.gpsalarm.GpsTrainTracking.AFTER_ARRIVAL_MILLIS)
    }

    @Test fun `the realtime feeds and the swiss boards are the shared table`() {
        val json = table("realtime-feeds-v1.json")
        assertEquals(Duration.ofSeconds(json.getLong("observationWindowSeconds")), it.binario.app.data.core.GtfsRealtimeFreshness.OBSERVATION_WINDOW)
        assertEquals(json.getLong("observationWindowSeconds"), it.binario.app.data.urban.UrbanRealtimeSnapshot.MAX_AGE.seconds)
        val feeds = json.getJSONArray("tripUpdates").objects().associateBy { it.getString("id") }
        val sta = feeds.getValue(it.binario.app.data.live.StaRealtimeAdapter.SOURCE_ID.value)
        assertEquals(sta.getString("url"), "${it.binario.app.data.live.StaRealtimeAdapter.REALTIME_ROOT}/${it.binario.app.data.live.StaRealtimeFeed.TRIP_UPDATES.path}")
        val sncf = feeds.getValue(it.binario.app.data.live.SncfGtfsRealtimeAdapter.SOURCE_ID.value)
        assertEquals(sncf.getString("url"), it.binario.app.data.live.SncfRealtimeFeed.TRIP_UPDATES.url)
        val france = feeds.getValue(it.binario.app.data.live.TrenitaliaFranceGtfsRealtimeAdapter.SOURCE_ID.value)
        assertEquals(france.getString("url"), it.binario.app.data.live.HttpTrenitaliaFranceRealtimeFetcher.FEED_URL)
        assertEquals(france.getString("pack"), it.binario.app.data.live.TrenitaliaFranceGtfsRealtimeAdapter.STATIC_PACK.value)
        assertEquals(Duration.ofSeconds(france.getLong("timeToLiveSeconds")), it.binario.app.data.live.TrenitaliaFranceGtfsRealtimeAdapter.DESCRIPTOR.ratePolicy.defaultTimeToLive)
        assertEquals(feeds.keys, SharedData.RealtimeFeeds.TRIP_UPDATES.map { it.id }.toSet())
        val urban = json.getJSONArray("urban").objects()
        assertEquals(urban.map { it.getString("id") }, it.binario.app.data.urban.UrbanRealtimeFeeds.all.map { it.sourceId.value })
        assertEquals(urban.map { it.getString("url") }, it.binario.app.data.urban.UrbanRealtimeFeeds.all.map { it.tripUpdatesUrl })
        assertEquals(urban.map { it.getBoolean("personalOnly") }, it.binario.app.data.urban.UrbanRealtimeFeeds.all.map { it.personalOnly })
        assertEquals(json.getJSONObject("urbanFetch").getInt("maximumTrips"), it.binario.app.data.urban.UrbanGtfsRealtimeDecoder.MAX_TRIPS)
        val swiss = json.getJSONObject("swissBoard")
        assertEquals(swiss.getString("id"), it.binario.app.data.live.SwissOpenTransportStationBoardAdapter.SOURCE_ID.value)
        assertEquals(swiss.getString("baseUrl"), it.binario.app.data.live.SwissOpenTransportStationBoardAdapter.BASE_URL)
        assertEquals(swiss.getJSONArray("nonRailCategories").strings().toSet(), SharedData.RealtimeFeeds.SWISS_BOARD.nonRailCategories)
        val sleeper = feeds.getValue(it.binario.app.data.live.EuropeanSleeperGtfsRealtimeAdapter.SOURCE_ID.value)
        assertEquals(sleeper.getString("url"), it.binario.app.data.live.HttpEuropeanSleeperRealtimeFetcher.FEED_URL)
        assertEquals("remote-pack", sleeper.getString("gate"))
    }

    @Test fun `the community archives and RFI's programmed timetable are the shared tables`() {
        val remote = table("remote-gtfs-v1.json")
        val feed = remote.getJSONArray("feeds").getJSONObject(0)
        val catalogued = requireNotNull(it.binario.app.data.sources.InternationalSourceCatalog.byId(SourceId(feed.getString("id"))))
        assertEquals(feed.getString("url"), catalogued.downloadOrProbeUri.toString())
        assertEquals(feed.getString("realtimeFeed"), it.binario.app.data.live.EuropeanSleeperGtfsRealtimeAdapter.SOURCE_ID.value)
        assertEquals(java.time.LocalDate.parse(feed.getString("notBefore")), it.binario.app.data.live.EuropeanSleeperGtfsRealtimeAdapter.LAUNCH_DATE)
        assertEquals(feed.getString("pack"), it.binario.app.data.gtfs.RemoteGtfsFallbackDataSource.EUROPEAN_SLEEPER.pack)
        assertEquals(remote.getInt("maximumArchiveBytes"), SharedData.RemoteGtfs.MAXIMUM_ARCHIVE_BYTES)
        val rfi = table("rfi-programmed-v1.json")
        assertEquals(
            "https://prm.rfi.it/qo_prm/QO_Partenze_SiPMR.aspx?Id=2247&lin=it&dalle=00.00&alle=23.59&ora=00.00",
            it.binario.app.data.planned.RfiTimetablePages.url(2247)
        )
        assertEquals(rfi.getInt("maximumBytes"), it.binario.app.data.planned.RFI_PROGRAMMED_TIMETABLE_MAX_BYTES)
        for (hub in rfi.getJSONArray("hubs").objects()) {
            val id = hub.getInt("timetableId")
            val code = hub.getString("railwayCode")
            assertEquals(id, it.binario.app.data.planned.RfiProgrammedStationIds.timetableId(it.binario.app.data.Station("x", "?", "?", railwayCode = code)))
            for (name in hub.getJSONArray("names").strings()) {
                assertEquals(name, id, it.binario.app.data.planned.RfiProgrammedStationIds.timetableId(it.binario.app.data.Station("x", name, name)))
            }
        }
        for (hub in rfi.getJSONArray("supplementalHubs").objects()) {
            val id = hub.getInt("timetableId")
            for (name in hub.getJSONArray("names").strings()) {
                assertEquals(name, id, it.binario.app.data.planned.SupplementalRfiProgrammedStationIds.timetableId(it.binario.app.data.Station("x", name, name)))
            }
        }
    }
}
