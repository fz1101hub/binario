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

    // MARK: - The tables

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
