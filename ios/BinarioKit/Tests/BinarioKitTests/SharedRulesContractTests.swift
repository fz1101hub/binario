import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1: the train-number keys and the statistics rules Android, iPhone and
/// Alexa must share, read from the same file by the three test suites.
final class SharedRulesContractTests: XCTestCase {
    func testTrainNumbersGiveTheSharedStatisticsKeyAndLiveQuery() throws {
        for item in try rules().trainNumbers {
            XCTAssertEqual(NetworkReliability.numberKey(item.published), item.key, item.published)
            let query = LiveTrainNumberQuery(item.published)
            XCTAssertEqual(query.normalized, item.query, item.published)
            XCTAssertEqual(query.numeric, item.queryNumeric, item.published)
            XCTAssertEqual(query.isValid, item.queryValid, item.published)
        }
    }

    func testOnTimeLimitsAndMinimumSamplesAreTheSharedOnes() throws {
        let statistics = try rules().statistics
        XCTAssertEqual(NetworkReliability.minimumSamples, statistics.minimumSamples)
        for item in statistics.onTimeLimitMinutes {
            XCTAssertEqual(NetworkReliability.onTimeLimitMinutes(category: item.category), item.minutes, "«\(item.category)»")
        }
    }

    func testATrainWithoutOriginIsMeasuredAtItsLastStopAsTheOtherReadersMeasureIt() throws {
        let statistics = try rules().statistics
        let document = try Data(contentsOf: contract.appendingPathComponent(statistics.document))
        let stats = try XCTUnwrap(NetworkReliabilityParser.parse(json: [UInt8](document)))
        for item in statistics.lastStopWithoutOrigin {
            let stop = FavouriteTrainsReliability.lastStop(stats, number: item.number, originCode: nil)
            guard let figures = item.expected else {
                XCTAssertNil(stop, item.number)
                continue
            }
            let actual = try XCTUnwrap(stop, item.number)
            XCTAssertEqual(actual.samples, figures.samples, item.number)
            XCTAssertEqual(actual.onTime, figures.onTime, item.number)
            XCTAssertEqual(actual.onTimeLimitMinutes, figures.limitMinutes, item.number)
            XCTAssertEqual(actual.daysObserved, figures.daysObserved, item.number)
            XCTAssertEqual(actual.daysCancelled, figures.daysCancelled, item.number)
            // A cancelled day counts as a day not on time.
            let share = Double(figures.daysOnTime.part) / Double(figures.daysOnTime.whole)
            XCTAssertEqual(actual.score, share, accuracy: 1e-9, item.number)
        }
    }

    /// journey-leg-vehicles-v1.json: what a leg of the timetable travels on and the label it shows,
    /// as Android's `journeyLegMode` and `JourneyLegVehicle.of` say (JourneyLegVehiclesContractTest.kt).
    func testEverySharedLegHasTheSharedModeVehicleAndLabel() throws {
        let rules = try JSONDecoder().decode(
            LegVehicleRules.self, from: Data(contentsOf: contract.appendingPathComponent("journey-leg-vehicles-v1.json"))
        )
        XCTAssertEqual(Set(rules.scheduledCoachPacks), TimetableLegMode.scheduledCoachPacks)
        XCTAssertFalse(rules.legVehicles.isEmpty)
        for item in rules.legVehicles {
            let label = "\(item.routeType) \(item.pack) \(item.operatorName ?? "-") \(item.line ?? "-"): \(item.why)"
            let routeType = try XCTUnwrap(Int64(item.routeType), label)
            let mode = TimetableLegMode.of(routeType: routeType, sourceID: item.pack)
            XCTAssertEqual(mode?.rawValue, item.mode, label)
            guard let mode else {
                XCTAssertNil(item.vehicle, label)
                continue
            }
            let vehicle = TimetableVehicle.of(mode: mode, operatorName: item.operatorName, lineLabel: item.line)
            XCTAssertEqual(vehicle.rawValue, item.vehicle, label)
            XCTAssertEqual(vehicle.labelKey, item.iosLabel, label)
            XCTAssertEqual(vehicle.isBus, item.iosLabel != nil, label)
        }
    }

    /// weekday-remarks-v1.json: the weekday remarks of a train's detail, as Android shows them
    /// (WeekdayRemarksContractTest.kt): at most two, the largest difference first.
    func testEverySharedWeekdayCaseGivesTheSharedRemarks() throws {
        let rules = try JSONDecoder().decode(WeekdayRules.self, from: Data(contentsOf: contract.appendingPathComponent("weekday-remarks-v1.json")))
        let document = try Data(contentsOf: contract.appendingPathComponent(rules.document))
        let stats = try XCTUnwrap(NetworkReliabilityParser.parse(json: [UInt8](document)))
        XCTAssertFalse(rules.cases.isEmpty)
        for item in rules.cases {
            let trains = stats.trains.filter { $0.number == item.number }
            XCTAssertEqual(trains.count, 1, item.number)
            let train = try XCTUnwrap(trains.first, item.number)
            let actual = NetworkReliability.weekdayRemarks(train, index: item.stop).map {
                WeekdayCase(weekday: $0.weekday, samples: $0.samples, onTime: $0.onTime)
            }
            XCTAssertEqual(actual, item.expected, item.why)
        }
    }

    // MARK: - The contract file

    private struct WeekdayRules: Decodable {
        let document: String
        let cases: [WeekdayRule]
    }

    private struct WeekdayRule: Decodable {
        let number: String
        let stop: Int
        let why: String
        let expected: [WeekdayCase]
    }

    private struct WeekdayCase: Decodable, Equatable {
        let weekday: Int
        let samples: Int
        let onTime: Int
    }

    private struct LegVehicleRules: Decodable {
        let scheduledCoachPacks: [String]
        let legVehicles: [LegVehicleCase]
    }

    private struct LegVehicleCase: Decodable {
        let routeType: String
        let pack: String
        let operatorName: String?
        let line: String?
        let mode: String?
        let vehicle: String?
        let iosLabel: String?
        let why: String

        enum CodingKeys: String, CodingKey {
            case routeType, pack, operatorName = "operator", line, mode, vehicle, iosLabel, why
        }
    }

    private struct Rules: Decodable {
        let trainNumbers: [TrainNumberCase]
        let statistics: Statistics
    }

    private struct TrainNumberCase: Decodable {
        let published: String
        let key: String
        let query: String
        let queryNumeric: String?
        let queryValid: Bool
    }

    private struct Statistics: Decodable {
        let document: String
        let minimumSamples: Int
        let onTimeLimitMinutes: [LimitCase]
        let lastStopWithoutOrigin: [LastStopCase]
    }

    private struct LimitCase: Decodable {
        let category: String
        let minutes: Int
    }

    private struct LastStopCase: Decodable {
        let number: String
        let expected: Figures?
    }

    private struct Figures: Decodable {
        let samples: Int
        let onTime: Int
        let limitMinutes: Int
        let daysObserved: Int
        let daysCancelled: Int
        let daysOnTime: Fraction
    }

    private struct Fraction: Decodable {
        let part: Int
        let whole: Int
    }

    private func rules() throws -> Rules {
        try JSONDecoder().decode(Rules.self, from: Data(contentsOf: contract.appendingPathComponent("rules-v1.json")))
    }

    private var contract: URL {
        repositoryRoot.appendingPathComponent("contracts/shared-rules/v1")
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
