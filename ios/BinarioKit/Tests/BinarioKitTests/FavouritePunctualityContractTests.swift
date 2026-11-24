import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/favourite-punctuality-v1.json: the runs of the favourite trains
/// recorded on the phone, the trend, the Sunday digest and the background recording, read as
/// Android's FavouritePunctualityContractTest reads them.
final class FavouritePunctualityContractTests: XCTestCase {
    private func history(_ item: [String: Any]) throws -> [PunctualityObservation] {
        try SharedRuleFile.objects(item["history"]).map { entry in
            PunctualityObservation(
                serviceDate: try XCTUnwrap(SharedRuleFile.date(entry["date"])),
                trainNumber: try XCTUnwrap(SharedRuleFile.string(entry["train"])),
                operatorName: "",
                delayMinutes: try XCTUnwrap(SharedRuleFile.int(entry["delay"])),
                cancelled: entry["cancelled"] as? Bool ?? false
            )
        }
    }

    private func local(_ value: Any?) -> LocalPunctuality? {
        guard let object = value as? [String: Any],
              let runs = SharedRuleFile.int(object["runs"]), let onTime = SharedRuleFile.int(object["onTime"]),
              let cancelled = SharedRuleFile.int(object["cancelled"]) else { return nil }
        return LocalPunctuality(runs: runs, onTime: onTime, cancelled: cancelled)
    }

    private func instant(_ value: Any?) throws -> Date? {
        guard value is String else { return nil }
        let clock = try SharedRuleFile.wall(value)
        let time = try XCTUnwrap(PlannedLocalTime(hour: clock.minuteOfDay / 60, minute: clock.minuteOfDay % 60))
        return ItalianWallClock.instant(date: clock.date, time: time)
    }

    func testRunsRecordedBetweenTwoDaysAndTheTrend() throws {
        let rules = try SharedRuleFile.load("favourite-punctuality-v1.json")
        for item in SharedRuleFile.objects(rules["local"]) {
            let name = SharedRuleFile.string(item["name"]) ?? ""
            let found = FavouriteTrainPunctuality.local(
                try history(item), trainNumber: try XCTUnwrap(SharedRuleFile.string(item["train"])),
                from: try XCTUnwrap(SharedRuleFile.date(item["from"])), to: try XCTUnwrap(SharedRuleFile.date(item["to"]))
            )
            XCTAssertEqual(found, local(item["expected"]), name)
            if let expected = item["expected"] as? [String: Any] {
                XCTAssertEqual(found?.onTimePercent, SharedRuleFile.int(expected["onTimePercent"]), name)
            }
        }
        for item in SharedRuleFile.objects(rules["trend"]) {
            let name = SharedRuleFile.string(item["name"]) ?? ""
            let found = FavouriteTrainPunctuality.trend(
                try history(item), trainNumber: try XCTUnwrap(SharedRuleFile.string(item["train"])),
                today: try XCTUnwrap(SharedRuleFile.date(item["today"]))
            )
            guard let expected = item["expected"] as? [String: Any] else {
                XCTAssertNil(found, name)
                continue
            }
            XCTAssertEqual(found?.recent, local(expected["recent"]), name)
            XCTAssertEqual(found?.earlier, local(expected["earlier"]), name)
            XCTAssertEqual(found?.better, expected["better"] as? Bool, name)
        }
    }

    func testTheWeekAndTheNextRunOfTheDigest() throws {
        let rules = try SharedRuleFile.load("favourite-punctuality-v1.json")
        for item in SharedRuleFile.objects(rules["digestWeekEnd"]) {
            let now = try SharedRuleFile.wall(item["now"])
            XCTAssertEqual(WeeklyDigestPlanner.weekEnd(at: now), SharedRuleFile.date(item["expected"]), "\(now)")
            XCTAssertEqual(WeeklyDigestPlanner.mayNotify(now), item["mayNotify"] as? Bool, "\(now)")
        }
        for item in SharedRuleFile.objects(rules["digestNextRun"]) {
            let now = try SharedRuleFile.wall(item["now"])
            let next = WeeklyDigestPlanner.nextRun(after: now, lastSent: SharedRuleFile.date(item["lastSent"]))
            XCTAssertEqual(next, try SharedRuleFile.wall(item["expected"]), "\(now)")
        }
    }

    func testTheTrainTheDigestNamesAsWorse() throws {
        let rules = try SharedRuleFile.load("favourite-punctuality-v1.json")
        for item in SharedRuleFile.objects(rules["digestWorse"]) {
            let name = SharedRuleFile.string(item["name"]) ?? ""
            let trains = try SharedRuleFile.objects(item["trains"]).map { train -> DigestTrain in
                var network: DigestNetwork?
                if let mark = train["network"] as? [String: Any] {
                    network = DigestNetwork(
                        onTime: try XCTUnwrap(SharedRuleFile.int(mark["onTime"])),
                        samples: try XCTUnwrap(SharedRuleFile.int(mark["samples"])),
                        limitMinutes: try XCTUnwrap(SharedRuleFile.int(mark["limitMinutes"])),
                        onTimeWithin5: SharedRuleFile.int(mark["onTimeWithin5"]),
                        days: try XCTUnwrap(SharedRuleFile.int(mark["days"]))
                    )
                }
                return DigestTrain(
                    number: try XCTUnwrap(SharedRuleFile.string(train["number"])), name: nil,
                    week: local(train["week"]), earlier: local(train["earlier"]), network: network
                )
            }
            let worse = WeeklyDigestBuilder.worse(trains)
            guard let expected = item["expected"] as? [String: Any] else {
                XCTAssertNil(worse, name)
                continue
            }
            XCTAssertEqual(worse?.train.number, SharedRuleFile.string(expected["number"]), name)
            XCTAssertEqual(worse?.weekPercent, SharedRuleFile.int(expected["weekPercent"]), name)
            XCTAssertEqual(worse?.usualPercent, SharedRuleFile.int(expected["usualPercent"]), name)
        }
    }

    func testWhenAFavouritesFinalDelayIsRecorded() throws {
        let rules = try SharedRuleFile.load("favourite-punctuality-v1.json")
        for item in SharedRuleFile.objects(rules["recordingDueAt"]) {
            let name = SharedRuleFile.string(item["name"]) ?? ""
            let json = try XCTUnwrap(item["candidate"] as? [String: Any])
            let candidate = RecordingCandidate(
                number: "2153",
                departure: SharedRuleFile.string(json["departure"]).flatMap { PlannedLocalTime(hhmm: $0) },
                durationMinutes: SharedRuleFile.int(json["duration"]),
                recordedOn: SharedRuleFile.date(json["recordedOn"]),
                attemptDate: SharedRuleFile.date(json["attemptDate"]),
                attempts: SharedRuleFile.int(json["attempts"]) ?? 0,
                lastAttempt: try instant(json["lastAttempt"])
            )
            let due = PunctualityRecordingPlanner.dueAt(candidate, date: try XCTUnwrap(SharedRuleFile.date(item["date"])))
            XCTAssertEqual(due, try instant(item["expected"]), name)
        }
    }
}
