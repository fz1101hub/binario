import Foundation
import XCTest
@testable import BinarioKit

final class JourneyModelsTests: XCTestCase {
    private let roma = StationIdentity(id: "S08409", name: "Roma Termini", railwayCode: "S08409")
    private let napoli = StationIdentity(id: "S09218", name: "Napoli Centrale", railwayCode: "S09218")

    func testQueryRejectsTheSameCanonicalStation() {
        XCTAssertThrowsError(try JourneyQuery(origin: roma, destination: roma, departure: Date())) { error in
            XCTAssertEqual(error as? JourneyValidationError, .sameStation)
        }
    }

    func testSavedRouteUsesCanonicalIdentitiesAndValidatesCollectionBounds() throws {
        let route = try route()
        XCTAssertEqual(route.id, "S08409--S09218")
        XCTAssertNoThrow(try SavedRouteCollection(validating: [route]))
        XCTAssertThrowsError(try SavedRouteCollection(validating: [route, route])) { error in
            XCTAssertEqual(error as? JourneyValidationError, .duplicateSavedRoute)
        }
    }

    func testSavedRouteRejectsNonNormalizedOrOversizedLabels() {
        XCTAssertThrowsError(try SavedRoute(
            id: "route",
            name: "Roma  Termini",
            origin: roma,
            destination: napoli
        ))
        XCTAssertThrowsError(try SavedRoute(
            id: String(repeating: "r", count: 129),
            name: "Roma Napoli",
            origin: roma,
            destination: napoli
        ))
    }

    func testDecodedRouteDelegatesToModelValidation() throws {
        for fixture in [
            encodedRoute(id: "", name: "Roma Napoli", origin: roma, destination: napoli),
            encodedRoute(id: "route", name: "Roma  Napoli", origin: roma, destination: napoli),
            encodedRoute(id: "route", name: "Roma Napoli", origin: roma, destination: roma),
            encodedRoute(id: String(repeating: "x", count: 129), name: "Roma Napoli", origin: roma, destination: napoli),
            encodedRoute(
                id: "route",
                name: "Roma Napoli",
                origin: StationIdentity(id: "S08409", name: "Roma  Termini"),
                destination: napoli
            ),
            encodedRoute(
                id: "route",
                name: "Roma Napoli",
                origin: StationIdentity(id: String(repeating: "s", count: 129), name: "Roma Termini"),
                destination: napoli
            ),
        ] {
            XCTAssertThrowsError(try decoder.decode(SavedRoute.self, from: fixture))
        }
    }

    func testDecodedCollectionValidatesEveryRouteAndRejectsDuplicateIDs() throws {
        let valid = try route()
        let duplicate = try SavedRoute(
            id: valid.id,
            name: "Seconda tratta",
            origin: napoli,
            destination: StationIdentity(id: "S06150", name: "Milano Centrale")
        )
        let encoded = try encoder.encode(["routes": [valid, duplicate]])

        XCTAssertThrowsError(try decoder.decode(SavedRouteCollection.self, from: encoded)) { error in
            XCTAssertEqual(error as? JourneyValidationError, .duplicateSavedRoute)
        }
    }

    func testDecodedCollectionRejectsTooManyOtherwiseValidRoutes() throws {
        let routes = try (0...SavedRouteCollection.maximumRoutes).map { index in
            try SavedRoute(
                id: "route-\(index)",
                name: "Tratta \(index)",
                origin: StationIdentity(id: "origin-\(index)", name: "Origine \(index)"),
                destination: StationIdentity(id: "destination-\(index)", name: "Destinazione \(index)")
            )
        }
        let encoded = try encoder.encode(["routes": routes])

        XCTAssertThrowsError(try decoder.decode(SavedRouteCollection.self, from: encoded)) { error in
            XCTAssertEqual(error as? JourneyValidationError, .tooManySavedRoutes)
        }
    }

    func testRepositoryAlwaysFailsClosedWithoutInventingServices() async throws {
        let query = try JourneyQuery(origin: roma, destination: napoli, departure: Date())
        let result = await FailClosedJourneyRepository().journeys(for: query)

        XCTAssertEqual(result, .unavailable(.noAuthorizedProvider))
    }

    private var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        return encoder
    }

    private var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return decoder
    }

    private func route() throws -> SavedRoute {
        try SavedRoute(
            id: SavedRoute.routeIdentifier(origin: roma, destination: napoli),
            name: "Roma Termini → Napoli Centrale",
            origin: roma,
            destination: napoli,
            createdAt: Date(timeIntervalSince1970: 1_000)
        )
    }

    private func encodedRoute(
        id: String,
        name: String,
        origin: StationIdentity,
        destination: StationIdentity
    ) -> Data {
        let object: [String: Any] = [
            "id": id,
            "name": name,
            "origin": ["id": origin.id, "name": origin.name],
            "destination": ["id": destination.id, "name": destination.name],
            "createdAt": 1_000_000,
        ]
        return try! JSONSerialization.data(withJSONObject: object)
    }
}
