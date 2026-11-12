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

    // MARK: - The contract file

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
