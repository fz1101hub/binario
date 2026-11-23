import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/live-sources-v1.json, read as it is, Android's LiveSourcesContractTest
/// too: the operator of a ViaggiaTreno row, a row of the arrivals board and the per-train notices of
/// a day, through BinarioKit's readers. The notices are Android's fixtures, read from the checkout.
final class LiveSourcesContractTests: XCTestCase {
    func testTheOperatorOfAViaggiaTrenoRowIsTheSharedRule() throws {
        let cases = try XCTUnwrap(contract()["viaggiaTrenoOperator"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for item in cases {
            let row = try XCTUnwrap(LiveJSONObject(item["row"]))
            XCTAssertEqual(ViaggiaTrenoParser.boardOperatorID(row), item["operator"] as? String, "\(item)")
        }
    }

    func testARowOfTheArrivalsBoardIsTheSharedRule() throws {
        let cases = try XCTUnwrap(contract()["viaggiaTrenoArrivals"] as? [String: Any])
        let nowMillis = try XCTUnwrap((cases["now"] as? NSNumber)?.int64Value)
        let toSeconds = try XCTUnwrap((cases["toSeconds"] as? NSNumber)?.doubleValue)
        let rows = try XCTUnwrap(cases["rows"] as? [Any])
        let expected = try XCTUnwrap(cases["expected"] as? [[String: Any]])
        let now = LiveRailTime.date(millis: nowMillis)
        let text = try XCTUnwrap(String(data: JSONSerialization.data(withJSONObject: rows), encoding: .utf8))
        let window = LiveBoardWindow(
            station: Station(id: "S08217", name: "Roma Tiburtina", railwayCode: "S08217"), from: now,
            to: now.addingTimeInterval(toSeconds), lateSince: nil, kind: .arrivals
        )
        let parsed = try XCTUnwrap(ViaggiaTrenoParser.departures(text, window: window, fetchedAt: now))
        XCTAssertEqual(parsed.map(\.number), expected.compactMap { $0["number"] as? String })
        for (item, row) in zip(expected, parsed) {
            XCTAssertEqual(row.destination, item["endpoint"] as? String)
            XCTAssertEqual(LiveRailTime.hhmm(row.scheduled), item["scheduled"] as? String)
            XCTAssertEqual(row.expected.map { LiveRailTime.hhmm($0.value) }, item["expected"] as? String)
            XCTAssertEqual(row.platform?.value, item["platform"] as? String)
            XCTAssertEqual(row.scheduledPlatform, item["scheduledPlatform"] as? String)
            XCTAssertEqual(row.status?.value.rawValue, item["status"] as? String)
            XCTAssertEqual(row.viaggiaTrenoRun?.nativeID, item["run"] as? String)
        }
    }

    func testTheNoticesOfATrainOnADayAreTheSharedRule() throws {
        let rules = try XCTUnwrap(NoticeRules.standard)
        let cases = try XCTUnwrap(contract()["trainNotices"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for item in cases {
            let fixture = try XCTUnwrap(item["fixture"] as? String)
            let body = try String(contentsOf: repositoryRoot.appendingPathComponent(fixture), encoding: .utf8)
            let notes = try XCTUnwrap(ViaggiaTrenoTrainNotices.parse(body, rules: rules), fixture)
            let day = try XCTUnwrap(PlannedLocalDate(iso: try XCTUnwrap(item["serviceDate"] as? String)))
            let serviceDate = LiveTestDates.rome(day.year, day.month, day.day, 12, 0)
            let notices = ViaggiaTrenoTrainNotices.select(
                notes, number: try XCTUnwrap(item["number"] as? String), origin: item["origin"] as? String, serviceDate: serviceDate
            )
            XCTAssertEqual(notices.map(\.id), item["ids"] as? [String], "\(item)")
            if let opening = item["textStartsWith"] as? String {
                XCTAssertTrue(notices.first?.text(for: "it").hasPrefix(opening) == true)
            }
            if let opening = item["englishStartsWith"] as? String {
                XCTAssertTrue(notices.first?.text(for: "en").hasPrefix(opening) == true)
            }
        }
    }

    // MARK: - The contract file

    private func contract() throws -> [String: Any] {
        let url = repositoryRoot.appendingPathComponent("contracts/shared-rules/v1/live-sources-v1.json")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
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
