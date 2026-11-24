import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-data/v1: each table read from its JSON file, as the Android and Alexa tests
/// read it, against the generated `SharedData` and against the code that uses it.
final class SharedDataTests: XCTestCase {
    func testItaloFallbackStationsAndSlugsAreTheSharedTable() throws {
        let json = try table("italo-stations-v1.json")
        let stations = try XCTUnwrap(json["stations"] as? [[String: Any]])
        XCTAssertEqual(ItaloStationFallbacks.all.map(\.code), stations.compactMap { $0["code"] as? String })
        XCTAssertEqual(ItaloStationFallbacks.all.map(\.name), stations.compactMap { $0["name"] as? String })
        XCTAssertEqual(ItaloStationFallbacks.all.map(\.aliases), stations.map { $0["aliases"] as? [String] ?? [] })
        XCTAssertEqual(ItaloStationFallbacks.all.first { $0.code == "RRO" }?.slug, "milano-rho-fiera")
        let slugs = try XCTUnwrap(json["slugsByName"] as? [String: String])
        XCTAssertEqual(SharedData.ItaloStations.slugsByName, slugs)
        for (name, slug) in slugs {
            XCTAssertEqual(ItaloStationFallbacks.knownSlugs[LiveText.legacyStationKey(name)], slug, name)
        }
    }

    func testRfiPlaceIDsByNameAreTheSharedTable() throws {
        let json = try table("rfi-place-ids-v1.json")
        let places = try XCTUnwrap(json["placeIdsByName"] as? [String: Int])
        XCTAssertEqual(SharedData.RfiPlaceIds.placeIdsByName, places)
        for (name, id) in places {
            XCTAssertEqual(RfiPlaceCatalogue.knownPlaces[LiveText.legacyStationKey(name)], id, name)
        }
    }

    func testSourceScoresAndTheOperatorBonusAreTheSharedTable() throws {
        let json = try table("source-scores-v1.json")
        let preferences = try XCTUnwrap(json["preferences"] as? [[String: Any]])
        let rules = try XCTUnwrap(json["operatorAffinity"] as? [[String: Any]])
        let defaultScore = try XCTUnwrap(json["defaultScore"] as? Int)
        XCTAssertEqual(SharedData.SourceScores.preferences.count, preferences.count)
        XCTAssertEqual(SharedData.SourceScores.operatorAffinity.count, rules.count)
        XCTAssertEqual(SharedData.SourceScores.defaultScore, defaultScore)
        let categories: [(LiveSourcePolicy.Category, String)] = [(.trainRealtime, "TRAIN_REALTIME"), (.stationBoard, "STATION_BOARD")]
        for source in LiveSource.allCases {
            for (category, name) in categories {
                XCTAssertEqual(category.sharedName, name)
                let preference = preferences.first { item in
                    (item["source"] as? String) == source.rawValue && (item["category"] as? String) == name
                }
                let base: Int = (preference?["score"] as? Int) ?? defaultScore
                XCTAssertEqual(LiveSourcePolicy.score(category, source, operatorHint: nil), base, "\(source) \(name)")
                XCTAssertEqual(LiveSourcePolicy.score(category, source, operatorHint: "Zzz"), base, "\(source) \(name)")
                let rule = rules.first { item in ((item["sources"] as? [String]) ?? []).contains(source.rawValue) }
                let points: Int = ((rule?["pointsByCategory"] as? [String: Int]) ?? [:])[name] ?? 0
                for keyword in (rule?["operatorKeywords"] as? [String]) ?? [] {
                    XCTAssertEqual(LiveSourcePolicy.score(category, source, operatorHint: keyword), base + points, "\(source) \(name) \(keyword)")
                }
            }
        }
    }

    func testPunctualityThresholdsAreTheSharedTable() throws {
        let json = try table("punctuality-v1.json")
        let categories = try XCTUnwrap(json["highSpeedCategories"] as? [String])
        XCTAssertEqual(SharedData.Punctuality.highSpeedCategories, Set(categories))
        for category in categories {
            XCTAssertEqual(NetworkReliability.onTimeLimitMinutes(category: category), json["highSpeedOnTimeMinutes"] as? Int, category)
        }
        XCTAssertEqual(NetworkReliability.onTimeLimitMinutes(category: "REG"), json["otherOnTimeMinutes"] as? Int)
        XCTAssertEqual(NetworkReliability.highSpeedOnTimeMinutes, json["highSpeedOnTimeMinutes"] as? Int)
        XCTAssertEqual(NetworkReliability.otherOnTimeMinutes, json["otherOnTimeMinutes"] as? Int)
        XCTAssertEqual(NetworkReliability.minimumSamples, json["minimumSamples"] as? Int)
        XCTAssertEqual(NetworkReliability.minimumPlatformCount, json["minimumPlatformDays"] as? Int)
        XCTAssertEqual(NetworkReliability.minimumPlatformSharePercent, json["minimumPlatformSharePercent"] as? Int)
        XCTAssertEqual(NetworkReliability.minimumWeekdaySamples, json["minimumWeekdaySamples"] as? Int)
        XCTAssertEqual(NetworkReliability.minimumWeekdayGapPoints, json["minimumWeekdayGapPoints"] as? Int)
        XCTAssertEqual(NetworkReliability.maximumWeekdayRemarks, json["maximumWeekdayRemarks"] as? Int)
        XCTAssertEqual(NetworkReliabilityAggregates.minimumAggregateSamples, json["minimumAggregateSamples"] as? Int)
        XCTAssertEqual(NetworkReliabilityAggregates.minimumBandSamples, json["minimumBandSamples"] as? Int)
        XCTAssertEqual(NetworkReliabilityAggregates.minimumBandGapPoints, json["minimumBandGapPoints"] as? Int)
    }

    func testTrainNumberPrefixesAreTheSharedTable() throws {
        let json = try table("train-number-prefixes-v1.json")
        let categories = try XCTUnwrap(json["categories"] as? [String])
        XCTAssertEqual(SharedData.TrainNumberPrefixes.categories, categories)
        for category in categories {
            XCTAssertEqual(LiveTrainNumberQuery("\(category) 0123").numeric, "123", category)
            XCTAssertNil(LiveTrainNumberQuery("\(category)Q123").numeric, category)
        }
    }

    func testAirportRoutesAndTheirCityLinesAreTheSharedTable() throws {
        let json = try table("airport-routes-v1.json")
        let routes = try XCTUnwrap(json["routes"] as? [[String: Any]])
        XCTAssertEqual(AirportRoutes.all.map(\.cityStationID), routes.compactMap { $0["city"] as? String })
        XCTAssertEqual(AirportRoutes.all.map(\.airportStationID), routes.compactMap { $0["airport"] as? String })
        XCTAssertEqual(AirportRoutes.all.map(\.packs), routes.map { Set(($0["packs"] as? [String]) ?? []) })
        let packs = try XCTUnwrap(json["packs"] as? [[String: Any]])
        let cityLines = Set(packs.filter { ($0["cityLine"] as? Bool) == true }.compactMap { $0["id"] as? String })
        XCTAssertEqual(cityLines, ["marconi-express-gtfs"])
        for route in AirportRoutes.all {
            XCTAssertEqual(AirportRoutes.byCityLine(route), route.packs.isSubset(of: cityLines), route.cityStationID)
        }
    }

    func testAgendaAndTemplateLimitsAreTheSharedTable() throws {
        let json = try table("planned-journeys-v1.json")
        XCTAssertEqual(PlannedJourneySchema.version, json["schemaVersion"] as? Int)
        XCTAssertEqual(PlannedJourneySchema.transferKind, json["transferKind"] as? String)
        XCTAssertEqual(PlannedJourneySchema.maximumJourneys, json["maximumJourneys"] as? Int)
        XCTAssertEqual(PlannedJourneySchema.maximumSegments, json["maximumSegments"] as? Int)
        XCTAssertEqual(PlannedJourneySchema.maximumChecklistItems, json["maximumChecklistItems"] as? Int)
        XCTAssertEqual(PlannedJourneySchema.maximumOccurrences, json["maximumOccurrences"] as? Int)
        XCTAssertEqual(PlannedJourneySchema.maximumLabelLength, json["maximumLabelLength"] as? Int)
        XCTAssertEqual(PlannedJourneySchema.maximumChecklistLabelLength, json["maximumChecklistLabelLength"] as? Int)
        XCTAssertEqual(PlannedJourneySchema.maximumIdentifierLength, json["maximumIdentifierLength"] as? Int)
        XCTAssertEqual(PlannedJourneySchema.maximumEncodedBytes, json["maximumTransferBytes"] as? Int)
        XCTAssertEqual(PlannedJourneySchema.maximumNestingDepth, json["maximumNestingDepth"] as? Int)
        XCTAssertEqual(PlannedJourneySchema.reminderMinutes, Set((json["reminderMinutes"] as? [Int]) ?? []))
        XCTAssertEqual(PlannedJourneyTemplates.maximumTemplates, json["maximumTemplates"] as? Int)
        XCTAssertEqual(PlannedJourneyTemplates.maximumNameLength, json["maximumTemplateNameLength"] as? Int)
        XCTAssertEqual(PlannedJourneyTemplates.kind, json["templatesKind"] as? String)
        XCTAssertEqual(PlannedJourneyTemplates.maximumBytes, json["maximumTemplatesBytes"] as? Int)
    }

    /// No iPhone code reads the calendar yet: the table is generated for it all the same.
    func testTheHolidayCalendarIsGeneratedForTheIPhoneToo() throws {
        let json = try table("italian-holidays-v1.json")
        let holidays = try XCTUnwrap(json["holidays"] as? [[String: Any]])
        XCTAssertEqual(SharedData.ItalianHolidays.holidays.map(\.month), holidays.compactMap { $0["month"] as? Int })
        XCTAssertEqual(SharedData.ItalianHolidays.holidays.map(\.day), holidays.compactMap { $0["day"] as? Int })
        XCTAssertEqual(SharedData.ItalianHolidays.holidays.map(\.fromYear), holidays.map { $0["fromYear"] as? Int })
        XCTAssertEqual(SharedData.ItalianHolidays.easterOffsetsDays, json["easterOffsetsDays"] as? [Int])
    }

    func testItaloGTFSStationCodesAreGeneratedForTheIPhoneToo() throws {
        // The iPhone index builder reads the JSON itself (tools/android_station_codes.py).
        let json = try table("italo-gtfs-station-codes.json")
        let codes = try XCTUnwrap(json["codes"] as? [[String: Any]])
        XCTAssertEqual(SharedData.ItaloGtfsStationCodes.codes.map(\.code), codes.compactMap { $0["code"] as? String })
        XCTAssertEqual(SharedData.ItaloGtfsStationCodes.codes.map(\.catalogueId), codes.compactMap { $0["catalogueId"] as? String })
        let unmapped = try XCTUnwrap(json["unmapped"] as? [[String: Any]])
        XCTAssertEqual(SharedData.ItaloGtfsStationCodes.unmapped.map(\.code), unmapped.compactMap { $0["code"] as? String })
    }

    func testPackRolesAreTheSharedTable() throws {
        let json = try table("pack-roles-v1.json")
        func set(_ key: String) -> Set<String> { Set((json[key] as? [String]) ?? []) }
        XCTAssertEqual(TimetableCopies.nationalPacks, set("nationalPacks"))
        XCTAssertEqual(TimetableCopies.foreignPacks, set("foreignPacks"))
        XCTAssertEqual(TimetableCopies.crossPublishingPacks, set("crossPublishingPacks"))
        XCTAssertEqual(TimetableLegMode.scheduledCoachPacks, set("scheduledCoachPacks"))
        for pack in set("nationalPacks") { XCTAssertEqual(TimetableCopies.rank(pack, border: false), 0, pack) }
        for pack in set("foreignPacks") { XCTAssertEqual(TimetableCopies.rank(pack, border: true), 0, pack) }
        for pack in set("scheduledCoachPacks") { XCTAssertEqual(TimetableLegMode.of(routeType: 3, sourceID: pack), .bus, pack) }
        XCTAssertEqual(TimetableLegMode.of(routeType: 3, sourceID: "lombardia-trenord-gtfs"), .replacementBus)
        // The city networks: their stops are `gtfs:<pack>:<stop>` ids, never catalogue stations.
        XCTAssertEqual(UrbanStops.packs, set("urbanPacks"))
        for pack in set("urbanPacks") { XCTAssertTrue(UrbanStops.isStopID("gtfs:\(pack):1"), pack) }
        XCTAssertFalse(UrbanStops.isStopID("gtfs:trenitalia-gtfs:1"))
    }

    func testTheWalksToTheCityStopsAreTheSharedTable() throws {
        let json = try table("urban-stops-v1.json")
        let platformMeters = try XCTUnwrap(json["platformMeters"] as? Int)
        XCTAssertEqual(UrbanStops.platformMeters, Double(platformMeters))
        XCTAssertEqual(SharedData.UrbanStops.maximumWalkMeters, json["maximumWalkMeters"] as? Int)
        XCTAssertEqual(SharedData.UrbanStops.walkMarginMinutes, json["walkMarginMinutes"] as? Int)
        XCTAssertEqual(SharedData.UrbanStops.walkingSpeedKmh, json["walkingSpeedKmh"] as? Double)
    }

    func testEverySourceIsAskedWithTheSharedManners() throws {
        let json = try table("source-manners-v1.json")
        XCTAssertEqual(LiveSourceUserAgent.make(version: "1.2"), "Binario/1.2 (iOS; rail timetable app)")
        let template = try XCTUnwrap(json["userAgent"] as? String)
        XCTAssertEqual(LiveSourceUserAgent.make(version: "é"), template.replacingOccurrences(of: "<version>", with: "0")
            .replacingOccurrences(of: "<platform>", with: "iOS"))
        let statuses = Set((json["refusalStatuses"] as? [Int]) ?? [])
        XCTAssertEqual(SharedData.SourceManners.refusalStatuses, statuses)
        for status in statuses { XCTAssertTrue(LiveFetchFailure.http(status: status, retryAfter: nil).isRefusal, "\(status)") }
        XCTAssertFalse(LiveFetchFailure.http(status: 503, retryAfter: nil).isRefusal)
        let configuration = LiveSourceGate.Configuration(minimumInterval: 30)
        XCTAssertEqual(configuration.failureThreshold, json["failureThreshold"] as? Int)
        XCTAssertEqual(configuration.baseOpenDuration, (json["baseOpenSeconds"] as? NSNumber)?.doubleValue)
        XCTAssertEqual(configuration.maximumOpenDuration, (json["maximumOpenSeconds"] as? NSNumber)?.doubleValue)
        XCTAssertEqual(configuration.refusalPause, (json["refusalPauseSeconds"] as? NSNumber)?.doubleValue)
        XCTAssertEqual(configuration.maximumRetryAfter, (json["maximumRetryAfterSeconds"] as? NSNumber)?.doubleValue)
    }

    func testTheNoticeFeedsAreTheSharedTable() throws {
        let json = try table("notices-sources-v1.json")
        let sources = try XCTUnwrap(json["sources"] as? [[String: Any]])
        XCTAssertEqual(NoticeTable.sources.map(\.id), sources.compactMap { $0["id"] as? String })
        XCTAssertTrue(Set(NoticeTable.sources.map(\.id)).isSuperset(of: NoticeSourceID.all))
        for (source, item) in zip(NoticeTable.sources, sources) {
            XCTAssertEqual(source.url, item["url"] as? String, source.id)
            XCTAssertEqual(source.gate, item["gate"] as? String, source.id)
            XCTAssertEqual(source.accept, item["accept"] as? String, source.id)
            XCTAssertEqual(source.maximumBytes, item["maximumBytes"] as? Int, source.id)
            XCTAssertEqual(source.readTimeoutSeconds, (item["readTimeoutSeconds"] as? NSNumber)?.doubleValue, source.id)
            XCTAssertEqual(source.timeToLiveSeconds, (item["timeToLiveSeconds"] as? NSNumber)?.doubleValue, source.id)
            XCTAssertEqual(source.publisher, item["publisher"] as? String, source.id)
        }
        let rules = try XCTUnwrap(NoticeRules.standard)
        let service = try XCTUnwrap(json["service"] as? [String: Any])
        XCTAssertEqual(rules.reloadAfter, (service["reloadAfterSeconds"] as? NSNumber)?.doubleValue)
        let display = try XCTUnwrap(json["display"] as? [String: Any])
        XCTAssertEqual(rules.display.networkCollapsed, display["networkCollapsed"] as? Int)
        XCTAssertEqual(rules.display.worksCollapsed, display["worksCollapsed"] as? Int)
        let links = try XCTUnwrap(json["links"] as? [String: Any])
        XCTAssertEqual(rules.mitStrikeCalendar?.absoluteString, links["mitStrikeCalendar"] as? String)
        XCTAssertEqual(rules.guaranteedTrains(of: .trenord)?.absoluteString, (links["guaranteedTrains"] as? [String: String])?["TRENORD"])
        XCTAssertNil(rules.guaranteedTrains(of: .rfi))
        let text = try XCTUnwrap(json["text"] as? [String: Any])
        XCTAssertEqual(NoticeTable.text.urlPattern, text["urlPattern"] as? String)
        let works = try XCTUnwrap(json["routeWorks"] as? [String: Any])
        XCTAssertEqual(NoticeTable.routeWorks.linePattern, works["linePattern"] as? String)
        XCTAssertEqual(NoticeTable.months, json["months"] as? [String])
    }

    func testTheViaggiaTrenoApiIsTheSharedTable() throws {
        let json = try table("viaggiatreno-v1.json")
        func seconds(_ key: String) -> Double? { (json[key] as? NSNumber)?.doubleValue }
        XCTAssertEqual(ViaggiaTrenoLiveSource.baseURL, json["baseUrl"] as? String)
        XCTAssertEqual(
            ViaggiaTrenoLiveSource.resources,
            ["departuresResource", "arrivalsResource", "runResource", "numberSearchResource"].compactMap { json[$0] as? String }
        )
        let policy = ViaggiaTrenoLiveSource.policy(for: "partenze/S01700/now")
        XCTAssertEqual(policy.requestTimeout, seconds("readTimeoutSeconds"))
        XCTAssertEqual(policy.totalTimeout, seconds("totalTimeoutSeconds"))
        XCTAssertEqual(policy.maximumBytes, json["maximumBytes"] as? Int)
        XCTAssertEqual(policy.maximumRequests, json["maximumRequests"] as? Int)
        XCTAssertEqual(ViaggiaTrenoLiveSource.minimumInterval, seconds("minimumIntervalSeconds"))
        XCTAssertEqual(ViaggiaTrenoParser.timeToLive, seconds("timeToLiveSeconds"))
        XCTAssertEqual(ViaggiaTrenoLiveSource.detailConcurrency, json["detailPermits"] as? Int)
        XCTAssertEqual(ViaggiaTrenoLiveSource.defaultDetailsTimeout, seconds("runDetailsTimeoutSeconds"))
        XCTAssertEqual(ViaggiaTrenoLiveSource.defaultLookbackTimeout, seconds("lookbackTimeoutSeconds"))
        XCTAssertEqual(ViaggiaTrenoParser.maximumAutocompleteLines, json["maximumAutocompleteLines"] as? Int)
        XCTAssertEqual(ViaggiaTrenoParser.maximumReferences, json["maximumRunReferences"] as? Int)
        XCTAssertEqual(ViaggiaTrenoParser.maximumBoardRows, json["maximumBoardRows"] as? Int)
        XCTAssertEqual(SharedData.ViaggiaTreno.numberSearchPastDays, json["numberSearchPastDays"] as? Int)
        XCTAssertEqual(ViaggiaTrenoParser.suppressedStopType, json["suppressedStopType"] as? Int)
        XCTAssertEqual(ViaggiaTrenoParser.extraordinaryStopType, json["extraordinaryStopType"] as? Int)
        XCTAssertEqual(SharedData.ViaggiaTreno.trenordClients, Set(json["trenordClients"] as? [Int] ?? []))
        XCTAssertEqual(SharedData.ViaggiaTreno.trenitaliaRegionalClients, Set(json["trenitaliaRegionalClients"] as? [Int] ?? []))
        XCTAssertEqual(SharedData.ViaggiaTreno.trenitaliaLongDistanceClients, Set(json["trenitaliaLongDistanceClients"] as? [Int] ?? []))
    }

    func testTheRfiMonitorIsTheSharedTable() throws {
        let json = try table("rfi-monitor-v1.json")
        func seconds(_ key: String) -> Double? { (json[key] as? NSNumber)?.doubleValue }
        XCTAssertEqual(RfiLiveSource.catalogueURL, json["catalogueUrl"] as? String)
        XCTAssertEqual(RfiLiveSource.monitorURL, json["monitorUrl"] as? String)
        XCTAssertEqual(RfiLiveSource.policy.accept, json["accept"] as? String)
        XCTAssertEqual(RfiLiveSource.policy.totalTimeout, seconds("totalTimeoutSeconds"))
        XCTAssertEqual(RfiLiveSource.policy.maximumBytes, json["maximumBytes"] as? Int)
        XCTAssertEqual(RfiLiveSource.minimumInterval, seconds("minimumIntervalSeconds"))
        XCTAssertEqual(RfiMonitorParser.timeToLive, seconds("timeToLiveSeconds"))
        XCTAssertEqual(RfiPlaceCatalogue.refreshInterval, seconds("catalogueRefreshSeconds"))
        XCTAssertEqual(RfiPlaceCatalogue.retryInterval, seconds("catalogueRetrySeconds"))
        XCTAssertEqual(
            RfiLiveSource.monitorAddress(placeID: 2416, kind: .arrivals),
            (json["monitorUrl"] as? String ?? "") + "?placeId=2416&arrivals=True"
        )
    }

    func testThePerTrainNoticesAreTheSharedFeedAndReader() throws {
        let json = try table("notices-sources-v1.json")
        let sources = try XCTUnwrap(json["sources"] as? [[String: Any]])
        let source = try XCTUnwrap(sources.first { $0["id"] as? String == NoticeSourceID.trainNotices })
        let notices = try XCTUnwrap(ViaggiaTrenoTrainNotices(rules: try XCTUnwrap(NoticeRules.standard)))
        let url = try XCTUnwrap(source["url"] as? String)
        XCTAssertEqual(notices.url(number: "8829")?.absoluteString, url + "?commercialTrainNumber=8829")
        XCTAssertEqual(notices.policy.accept, source["accept"] as? String)
        XCTAssertEqual(notices.policy.maximumBytes, source["maximumBytes"] as? Int)
        XCTAssertEqual(notices.policy.totalTimeout, (source["totalTimeoutSeconds"] as? NSNumber)?.doubleValue)
        XCTAssertTrue(notices.policy.allowsViaggiaTrenoCleartextHop)
        let rules = try XCTUnwrap(json["trainNotices"] as? [String: Any])
        XCTAssertEqual(NoticeTable.trainNotices.maximumNotes, rules["maximumNotes"] as? Int)
        XCTAssertEqual(NoticeTable.trainNotices.textLength, rules["textLength"] as? Int)
        XCTAssertEqual(NoticeTable.trainNotices.maximumTrains, rules["maximumTrains"] as? Int)
        XCTAssertEqual(NoticeTable.trainNotices.maximumCachedTrains, rules["maximumCachedTrains"] as? Int)
    }

    func testTheRealtimeFeedsAndTheSwissBoardsAreTheSharedTable() throws {
        let json = try table("realtime-feeds-v1.json")
        XCTAssertEqual(SharedData.RealtimeFeeds.observationWindowSeconds, json["observationWindowSeconds"] as? Double)
        let feeds = try XCTUnwrap(json["tripUpdates"] as? [[String: Any]])
        XCTAssertEqual(SharedData.RealtimeFeeds.tripUpdates.map(\.id), feeds.compactMap { $0["id"] as? String })
        // Each feed is a live source of its own, asked for its pack only, with the table's limits.
        let realtime = TimetableRealtime.standard(transport: FakeLiveTransport { _ in .failure(.network) }, pauses: LiveSourcePauses())
        for feed in feeds {
            let id = try XCTUnwrap(feed["id"] as? String)
            let pack = try XCTUnwrap(feed["pack"] as? String)
            let source = try XCTUnwrap(realtime.feeds[pack], id)
            XCTAssertEqual(source.source.rawValue, id)
            XCTAssertEqual(source.feed.url, feed["url"] as? String)
            XCTAssertEqual(source.policy.maximumBytes, feed["maximumBytes"] as? Int)
            XCTAssertEqual(source.policy.maximumRequests, feed["maximumRequests"] as? Int)
            XCTAssertEqual(source.policy.totalTimeout, feed["totalTimeoutSeconds"] as? Double)
            XCTAssertEqual(source.policy.allowsSameHostRedirects, (feed["redirects"] as? String) == "same-https-host")
            XCTAssertEqual(LiveSourceLabels.label(source.source), feed["label"] as? String)
        }
        let urban = try XCTUnwrap(json["urban"] as? [[String: Any]])
        XCTAssertEqual(UrbanRealtimeFeeds.all.map(\.id), urban.compactMap { $0["id"] as? String })
        let swiss = try XCTUnwrap(json["swissBoard"] as? [String: Any])
        XCTAssertEqual(LiveSource.swissOpenTransport.rawValue, swiss["id"] as? String)
        XCTAssertEqual(SwissStationBoardSource.policy.maximumBytes, swiss["maximumBytes"] as? Int)
        XCTAssertEqual(SwissStationBoardSource.table.nonRailCategories, Set(swiss["nonRailCategories"] as? [String] ?? []))
        XCTAssertTrue(SwissStationBoardSource.boardURL(uic: "8505300", from: Date(), now: Date()).hasPrefix(try XCTUnwrap(swiss["baseUrl"] as? String)))
    }

    // MARK: - The tables

    func testTheDelayCompensationThresholdsAreTheSharedTable() throws {
        let json = try table("delay-compensation-v1.json")
        XCTAssertEqual(DelayCompensationRule.quarterFromMinutes, json["quarterFromMinutes"] as? Int)
        XCTAssertEqual(DelayCompensationRule.halfFromMinutes, json["halfFromMinutes"] as? Int)
        XCTAssertEqual(CompensationShare.quarter.percent, json["quarterPercent"] as? Int)
        XCTAssertEqual(CompensationShare.half.percent, json["halfPercent"] as? Int)
    }

    func testTheDestinationWeatherSourceIsTheSharedTable() throws {
        let json = try table("destination-weather-v1.json")
        XCTAssertEqual(DestinationWeatherService.attributionURL?.absoluteString, json["attributionUrl"] as? String)
        XCTAssertEqual(DestinationWeatherService.attributionURL.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false)?.host }, DestinationWeatherService.attributionHost)
        XCTAssertEqual(SharedData.DestinationWeather.forecastUrl, json["forecastUrl"] as? String)
        XCTAssertEqual(SharedData.DestinationWeather.dailyVariables, json["dailyVariables"] as? [String])
        XCTAssertEqual(SharedData.DestinationWeather.forecastDays, json["forecastDays"] as? Int)
        XCTAssertEqual(SharedData.DestinationWeather.timeToLiveSeconds, (json["timeToLiveSeconds"] as? Double))
        XCTAssertEqual(SharedData.DestinationWeather.maximumBytes, json["maximumBytes"] as? Int)
    }

    func testTheBikeSharingSystemsAreTheSharedTable() throws {
        let json = try table("bike-sharing-v1.json")
        let systems = try XCTUnwrap(json["systems"] as? [[String: Any]])
        XCTAssertEqual(BikeSharingSystem.all.map(\.id), systems.compactMap { $0["id"] as? String })
        XCTAssertEqual(BikeSharingSystem.all.map(\.discoveryURL), systems.compactMap { $0["discoveryUrl"] as? String })
        XCTAssertEqual(BikeSharingSystem.all.map(\.attribution), systems.compactMap { $0["attribution"] as? String })
        XCTAssertEqual(BikeSharingSystem.all.map(\.personalOnly), systems.compactMap { $0["personalOnly"] as? Bool })
        XCTAssertEqual(BikeSharingSystem.forBuild(personalSources: false).map(\.id),
                       ["gbfs-nextbike-bergamo", "gbfs-famose-senigallia", "gbfs-bikemi", "gbfs-verona-bike"])
        XCTAssertEqual(SharedData.BikeSharing.radiusMeters, json["radiusMeters"] as? Int)
        XCTAssertEqual(SharedData.BikeSharing.feedLanguages, json["feedLanguages"] as? [String])
    }

    func testTheLiveTrainMapIsTheSharedTable() throws {
        let json = try table("live-train-map-v1.json")
        XCTAssertEqual(LiveTrainSelector.maximumTrains, json["maximumTrains"] as? Int)
        XCTAssertEqual(LiveTrainSelector.maximumAppliedDelay, json["lookBackMinutes"] as? Int)
        XCTAssertEqual(LiveTrainSelector.minimumAppliedDelay, json["minimumAppliedDelayMinutes"] as? Int)
        XCTAssertEqual(LiveTrainSelector.sameTrainKilometres, json["sameTrainKilometres"] as? Double)
        XCTAssertEqual(LiveTrainRefresh.positionsSeconds, json["positionsRefreshSeconds"] as? Double)
        XCTAssertEqual(LiveTrainAreas.stationRadiusKilometres, json["stationRadiusKilometres"] as? Double)
        let request = ScheduledRunRequest(at: RailMoment(date: try XCTUnwrap(PlannedLocalDate(iso: "2026-11-04")), secondOfDay: 0),
                                          scope: .area(GeoBounds(north: 1, south: 0, east: 1, west: 0)))
        XCTAssertEqual(request.lookBackMinutes, json["lookBackMinutes"] as? Int)
        XCTAssertEqual(request.lookAheadMinutes, json["lookAheadMinutes"] as? Int)
    }

    func testTheEveningAlertsAreTheSharedTable() throws {
        let json = try table("evening-alerts-v1.json")
        XCTAssertEqual(StrikeReminderPlanner.runHour, json["strikeReminderHour"] as? Int)
        XCTAssertEqual(RouteWorksAlertPlanner.runHour, json["routeWorksAlertHour"] as? Int)
        XCTAssertEqual(RouteWorksAlertPlanner.quietFromHour, json["quietFromHour"] as? Int)
        XCTAssertEqual(RouteWorksAlertPlanner.quietUntilHour, json["quietUntilHour"] as? Int)
        XCTAssertEqual(StrikeReminderPlanner.notifiedRetentionDays, json["strikeNotifiedRetentionDays"] as? Int)
        XCTAssertEqual(RouteWorksAlertPlanner.maximumItems, json["routeWorksMaximumItems"] as? Int)
        XCTAssertEqual(RouteWorksAlertPlanner.maximumKeepDays, json["routeWorksMaximumKeepDays"] as? Int)
        XCTAssertEqual(RouteWorksAlertPlanner.maximumNotified, json["routeWorksMaximumNotified"] as? Int)
    }

    func testTheCommunityArchivesAndRfisProgrammedTimetableAreTheSharedTables() throws {
        let remote = try table("remote-gtfs-v1.json")
        let feeds = try XCTUnwrap(remote["feeds"] as? [[String: Any]])
        XCTAssertEqual(SharedData.RemoteGtfs.feeds.map(\.url), feeds.compactMap { $0["url"] as? String })
        XCTAssertEqual(RemoteGTFSSchedule.policy.maximumBytes, remote["maximumArchiveBytes"] as? Int)
        XCTAssertEqual(RemoteGTFSSchedule.policy.maximumRequests, (remote["maximumRedirects"] as? Int).map { $0 + 1 })
        XCTAssertTrue(RemoteGTFSSchedule.policy.allowsSameHostRedirects)
        XCTAssertEqual(RemoteGTFSSchedule.policy.accept, remote["accept"] as? String)
        let archive = try XCTUnwrap(remote["archive"] as? [String: Any])
        XCTAssertEqual(GTFSArchiveLimits.remote.maximumEntries, archive["maximumEntries"] as? Int)
        XCTAssertEqual(GTFSArchiveLimits.remote.maximumEntryBytes, archive["maximumEntryBytes"] as? Int)
        XCTAssertEqual(GTFSArchiveLimits.remote.maximumCompressionRatio, archive["maximumCompressionRatio"] as? Int)
        XCTAssertEqual(GTFSArchiveLimits.remote.maximumArchiveBytes, remote["maximumArchiveBytes"] as? Int)
        for feed in SharedData.RemoteGtfs.feeds {
            let realtime = try XCTUnwrap(SharedData.RealtimeFeeds.tripUpdates.first { $0.id == feed.realtimeFeed }, feed.id)
            XCTAssertEqual(realtime.pack, feed.pack)
            XCTAssertEqual(realtime.gate, "remote-pack")
        }
        let rfi = try table("rfi-programmed-v1.json")
        XCTAssertEqual(RfiProgrammedTimetable.policy.maximumBytes, rfi["maximumBytes"] as? Int)
        XCTAssertEqual(RfiProgrammedTimetable.policy.totalTimeout, rfi["totalTimeoutSeconds"] as? Double)
        XCTAssertEqual(RfiProgrammedTimetable.policy.maximumRequests, 1)
        XCTAssertEqual(SharedData.RfiProgrammed.supplementalHubs.count, (rfi["supplementalHubs"] as? [[String: Any]])?.count)
    }

    private func table(_ name: String) throws -> [String: Any] {
        let url = repositoryRoot.appendingPathComponent("contracts/shared-data/v1").appendingPathComponent(name)
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        return try XCTUnwrap(object as? [String: Any], name)
    }

    private var repositoryRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}
