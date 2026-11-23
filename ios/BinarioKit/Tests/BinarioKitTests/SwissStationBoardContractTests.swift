import Foundation
import XCTest
@testable import BinarioKit

/// contracts/shared-rules/v1/swiss-station-board-v1.json: the Swiss boards of transport.opendata.ch as Android and the
/// iPhone read them (station numbers, name lookups, rows, addresses asked), from the same file in both test suites,
/// with Android's fixtures as inputs.
final class SwissStationBoardContractTests: XCTestCase {
    private func cases() throws -> ContractObject {
        let data = try RealtimeContract.data("contracts/shared-rules/v1/swiss-station-board-v1.json")
        return ContractObject(values: try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any]))
    }

    private func body(_ item: ContractObject) throws -> String {
        if let file = item.optionalString("file") { return String(decoding: try RealtimeContract.data(file), as: UTF8.self) }
        return item.string("json")
    }

    private func station(_ item: ContractObject) -> Station {
        Station(
            id: item.string("id"), name: item.string("name"), trenitaliaLocationId: item.optionalString("trenitaliaLocationId"),
            latitude: (item.values["latitude"] as? NSNumber)?.doubleValue, longitude: (item.values["longitude"] as? NSNumber)?.doubleValue
        )
    }

    private func window(_ item: ContractObject, station: Station) -> LiveBoardWindow {
        LiveBoardWindow(
            station: station, from: Date(timeIntervalSince1970: TimeInterval(item.int64("from") ?? 0)),
            to: Date(timeIntervalSince1970: TimeInterval(item.int64("to") ?? 0)),
            lateSince: item.int64("lateSince").map { Date(timeIntervalSince1970: TimeInterval($0)) }
        )
    }

    private func source(now: Int64, answer: @escaping @Sendable (String) -> String?) -> (SwissStationBoardSource, FakeLiveTransport) {
        let transport = FakeLiveTransport { request in
            guard let text = answer(request.url.absoluteString) else { return .failure(.network) }
            return FakeLiveTransport.ok(text)
        }
        let clock: @Sendable () -> Date = { Date(timeIntervalSince1970: TimeInterval(now)) }
        return (SwissStationBoardSource(transport: transport, pauses: LiveSourcePauses(), now: clock), transport)
    }

    func testTheRowsOfABoardAndTheAddressAsked() async throws {
        for item in try cases().objects("boards") {
            let name = item.string("name")
            let text = try body(item)
            let place = station(try XCTUnwrap(item.object("station")))
            let (board, transport) = source(now: item.int64("now") ?? 0) { _ in text }
            let answer = await board.departures(for: window(try XCTUnwrap(item.object("window")), station: place))
            XCTAssertEqual(transport.paths, [item.string("url")], name)
            if item.bool("unreadable") {
                XCTAssertEqual(answer, .unavailable(.sourceUnavailable), name)
                continue
            }
            guard case let .answered(rows, complete) = answer else { return XCTFail("\(name): \(answer)") }
            XCTAssertTrue(complete, name)
            let expected = item.objects("rows")
            XCTAssertEqual(rows.count, expected.count, name)
            for (wanted, row) in zip(expected, rows) {
                XCTAssertEqual(row.number, wanted.string("number"), name)
                XCTAssertEqual(row.category, wanted.optionalString("lineLabel") ?? "", name)
                XCTAssertEqual(row.destination, wanted.string("destination"), name)
                XCTAssertEqual(row.operatorID, wanted.optionalString("operator"), name)
                XCTAssertEqual(Int64(row.scheduled.timeIntervalSince1970), wanted.int64("scheduled"), name)
                XCTAssertEqual(row.expected.map { Int64($0.value.timeIntervalSince1970) }, wanted.int64("expected"), name)
                XCTAssertEqual(row.platform?.value, wanted.optionalString("platform"), name)
                XCTAssertEqual(row.scheduledPlatform, wanted.optionalString("scheduledPlatform"), name)
                let status: String? = row.status.map { $0.value == .delayed ? "DELAYED" : "ON_TIME" }
                XCTAssertEqual(status, wanted.optionalString("status"), name)
                XCTAssertEqual(row.sources, [.swissOpenTransport], name)
            }
        }
    }

    func testTheStationNumbers() throws {
        for item in try cases().objects("uic") {
            let id = item.string("stationId")
            let location = item.optionalString("trenitaliaLocationId")
            XCTAssertEqual(SwissStationIds.uic(stationID: id, trenitaliaLocationID: location), item.optionalString("uic"), id)
            XCTAssertEqual(SwissStationIds.isSwissWithoutUIC(stationID: id, trenitaliaLocationID: location), item.bool("swissWithoutUic"), id)
        }
    }

    func testTheTrainStationANameLookupMayUse() throws {
        for item in try cases().objects("locations") {
            let all = try XCTUnwrap(SwissBoardParser.locations(String(decoding: try RealtimeContract.data(item.string("file")), as: UTF8.self)))
            let candidates: [SwissLocation]
            switch item.string("keep") {
            case "not-train": candidates = all.filter { $0.icon != "train" }
            case "italian-ids": candidates = all.map { SwissLocation(id: "8301301", name: $0.name, icon: $0.icon, latitude: $0.latitude, longitude: $0.longitude) }
            default: candidates = all
            }
            let latitude = (item.values["latitude"] as? NSNumber)?.doubleValue
            let longitude = (item.values["longitude"] as? NSNumber)?.doubleValue
            XCTAssertEqual(
                SwissBoardParser.selectLocation(candidates, name: item.string("name"), latitude: latitude, longitude: longitude),
                item.optionalString("selected"), item.string("name")
            )
        }
    }

    func testTheNameLookupsOfBorderAndSwissStations() async throws {
        for item in try cases().objects("lookups") {
            let name = item.string("name")
            let answers = try XCTUnwrap(item.object("answers"))
            var texts: [String: String] = [:]
            for url in answers.keys { texts[url] = try body(try XCTUnwrap(answers.object(url))) }
            let known = texts
            let place = station(try XCTUnwrap(item.object("station")))
            let (board, transport) = source(now: item.int64("now") ?? 0) { known[$0] }
            let answer = await board.departures(for: window(try XCTUnwrap(item.object("window")), station: place))
            XCTAssertEqual(transport.paths, item.strings("asked"), name)
            if let rows = item.int64("rows") {
                guard case let .answered(found, _) = answer else { return XCTFail("\(name): \(answer)") }
                XCTAssertEqual(Int64(found.count), rows, name)
            } else {
                XCTAssertEqual(answer, .unavailable(.notCovered), name)
            }
        }
    }

    func testTheOperatorsOfTheRowsAndTheStationNames() async throws {
        let operators = try XCTUnwrap(try cases().object("operators"))
        let text = operators.string("json")
        let place = station(try XCTUnwrap(operators.object("station")))
        let (board, _) = source(now: operators.int64("now") ?? 0) { _ in text }
        guard case let .answered(rows, _) = await board.departures(for: window(try XCTUnwrap(operators.object("window")), station: place)) else {
            return XCTFail("no board")
        }
        let expected = (operators.values["expected"] as? [Any] ?? []).map { $0 as? String }
        XCTAssertEqual(rows.map(\.operatorID), expected)
        let names = try cases().objects("names")
        XCTAssertFalse(names.isEmpty)
        for item in names {
            XCTAssertEqual(SwissBoardParser.normalizeName(item.string("name")), item.string("normalized"))
        }
    }
}
