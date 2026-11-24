import XCTest
@testable import BinarioKit

/// «Viaggio e monitoraggi» of Per te on the followed trip (Android's `ActiveTrip`).
final class ActiveTripProgressTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_790_000_000)

    private func at(_ minutes: Int) -> Date { start.addingTimeInterval(TimeInterval(minutes * 60)) }

    private func leg(_ board: String, _ alight: String, from: Int, to: Int) -> FollowedTripLeg {
        FollowedTripLeg(number: "2153", label: "REG 2153", boardStation: board, alightStation: alight, departure: at(from), arrival: at(to))
    }

    func testTheNextStopFollowsTheSourcesAndTheStopsReachedByHand() {
        let stops = [
            ActiveTripStop(station: "Monza", expected: at(10), platform: "2", passed: true),
            ActiveTripStop(station: "Seregno", expected: at(20), platform: nil, passed: false),
            ActiveTripStop(station: "Como", expected: at(40), platform: "1", passed: false),
        ]
        XCTAssertEqual(ActiveTripProgress.nextIndex(stops, reached: 0), 1)
        XCTAssertEqual(ActiveTripProgress.nextIndex(stops, reached: 2), 2)
        XCTAssertEqual(ActiveTripProgress.nextIndex(stops, reached: 9), 3)
        XCTAssertEqual(ActiveTripProgress.nextIndex([], reached: 0), 0)
    }

    func testWithoutARunTheTripGoesToItsArrivalOnTheCurrentTrain() throws {
        let trip = try XCTUnwrap(FollowedTrip(id: "t", title: "Milano → Como", legs: [leg("Milano", "Monza", from: 0, to: 15), leg("Monza", "Como", from: 25, to: 60)]))
        XCTAssertEqual(ActiveTripProgress.legIndex(trip, now: at(5)), 0)
        XCTAssertEqual(ActiveTripProgress.legIndex(trip, now: at(30)), 1)
        XCTAssertEqual(ActiveTripProgress.legIndex(trip, now: at(90)), 1)
        let stops = ActiveTripProgress.stops(trip.legs[1], run: nil)
        XCTAssertEqual(stops, [ActiveTripStop(station: "Como", expected: at(60), platform: nil, passed: false)])
    }
}
