import XCTest
@testable import BinarioKit

/// The cases of Android's `RouteMapPlannerTest`, on the iPhone's planner.
final class RouteMapPlannerTests: XCTestCase {
    private let coordinates: [String: GeoPoint] = [
        "Milano Centrale": GeoPoint(latitude: 45.4864, longitude: 9.2042),
        "Lodi": GeoPoint(latitude: 45.3096, longitude: 9.4987),
        "Piacenza": GeoPoint(latitude: 45.0517, longitude: 9.6973),
        "Parma": GeoPoint(latitude: 44.8094, longitude: 10.3289),
        "Bologna Centrale": GeoPoint(latitude: 44.5059, longitude: 11.3431),
    ]

    private func locate(_ name: String) -> GeoPoint? { coordinates[name] }

    private func stop(_ name: String, _ scheduled: String = "08:00", expected: String? = nil, passed: Bool = false,
                      skipped: Bool = false, verified: Bool? = nil) -> RouteMapEntry {
        RouteMapEntry(name: name, scheduled: scheduled, expected: expected, passed: passed, skipped: skipped, verified: verified)
    }

    func testWithoutAConfirmedStopNothingIsHighlighted() {
        XCTAssertEqual(RouteMapPlanner.progress([stop("Milano Centrale"), stop("Lodi"), stop("Piacenza")]),
                       RouteMapProgress(lastConfirmed: nil, next: nil))
    }

    func testTheLastConfirmedStopAndTheNextStopToBeServedAreHighlighted() {
        let stops = [
            stop("Milano Centrale", passed: true), stop("Lodi", passed: true), stop("Piacenza", skipped: true),
            stop("Parma"), stop("Bologna Centrale"),
        ]
        XCTAssertEqual(RouteMapPlanner.progress(stops), RouteMapProgress(lastConfirmed: 1, next: 3))
        XCTAssertEqual(RouteMapPlanner.progress([stop("Milano Centrale", passed: true), stop("Bologna Centrale", passed: true)]),
                       RouteMapProgress(lastConfirmed: 1, next: nil))
        XCTAssertEqual(RouteMapPlanner.progress([stop("Milano Centrale", passed: true), stop("Lodi", passed: true, skipped: true), stop("Piacenza")]),
                       RouteMapProgress(lastConfirmed: 0, next: 2))
    }

    func testRolesCombineOriginDestinationProgressAndSkippedStops() {
        let model = RouteMapPlanner.build(
            [stop("Milano Centrale", passed: true), stop("Lodi"), stop("Piacenza", skipped: true), stop("Bologna Centrale")],
            serviceVerified: true, locate: locate
        )
        XCTAssertEqual(model.stops[0].roles, [.origin, .lastConfirmed])
        XCTAssertEqual(model.stops[0].primaryRole, .lastConfirmed)
        XCTAssertEqual(model.stops[1].roles, [.next])
        XCTAssertEqual(model.stops[2].roles, [.skipped])
        XCTAssertEqual(model.stops[3].roles, [.destination])
        XCTAssertNil(RouteMapPlanner.build([stop("Milano Centrale"), stop("Lodi"), stop("Parma")], serviceVerified: true, locate: locate).stops[1].primaryRole)

        let single = RouteMapPlanner.build([stop("Lodi")], serviceVerified: true, locate: locate)
        XCTAssertEqual(single.stops.map(\.roles), [[.origin]])
        XCTAssertTrue(single.line.isEmpty)
    }

    func testTheExpectedTimeIsShownOnlyWhenVerifiedDifferentAndNotAPlaceholder() {
        let model = RouteMapPlanner.build(
            [
                stop("Milano Centrale", "08:00", expected: "08:05", verified: true),
                stop("Lodi", "08:20", expected: "08:25", verified: false),
                stop("Piacenza", "08:40", expected: "08:45"),
                stop("Parma", "09:10", expected: "09:10", verified: true),
                stop("Bologna Centrale", "—", expected: "—", verified: true),
            ],
            serviceVerified: false, locate: locate
        )
        XCTAssertEqual(model.stops.map(\.expected), ["08:05", nil, nil, nil, nil])
        XCTAssertEqual(RouteMapPlanner.build([stop("Piacenza", "08:40", expected: "08:45")], serviceVerified: true, locate: locate).stops.first?.expected, "08:45")
    }

    func testUnmappedStopsStayInTheListAndTheLineJoinsOnlyStopsTheTrainCallsAt() {
        let model = RouteMapPlanner.build(
            [stop("Milano Centrale"), stop("Melegnano"), stop("Lodi", skipped: true), stop("Piacenza"), stop("Bologna Centrale")],
            serviceVerified: true, locate: locate
        )
        XCTAssertEqual(model.stops.count, 5)
        XCTAssertEqual(model.unmappedStops.map(\.name), ["Melegnano"])
        XCTAssertEqual(model.line, [coordinates["Milano Centrale"], coordinates["Piacenza"], coordinates["Bologna Centrale"]].compactMap { $0 })
    }

    func testRepeatedCoordinatesMissingAndInvalidOnes() {
        let same = GeoPoint(latitude: 45.0, longitude: 9.0)
        let other = GeoPoint(latitude: 44.0, longitude: 10.0)
        let repeated = RouteMapPlanner.build([stop("A"), stop("A bis"), stop("B")], serviceVerified: true) { $0 == "B" ? other : same }
        XCTAssertEqual(repeated.line, [same, other])

        let nowhere = RouteMapPlanner.build([stop("Nowhere"), stop("Elsewhere")], serviceVerified: true) { _ in nil }
        XCTAssertTrue(nowhere.mappedStops.isEmpty)
        XCTAssertTrue(nowhere.line.isEmpty)

        let broken = RouteMapPlanner.build([stop("Broken"), stop("Lodi")], serviceVerified: true) { name in
            name == "Broken" ? GeoPoint(latitude: .nan, longitude: 9.0) : self.coordinates[name]
        }
        XCTAssertEqual(broken.unmappedStops.map(\.name), ["Broken"])
        XCTAssertEqual(RouteMapPlanner.build([stop("Milano Centrale"), stop("Melegnano"), stop("Lodi", skipped: true)], serviceVerified: true, locate: locate)
            .mappedStops.map(\.name), ["Milano Centrale", "Lodi"])
    }
}
