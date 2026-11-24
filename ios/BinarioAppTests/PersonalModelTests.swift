import Foundation
import XCTest
@testable import Binario
import BinarioKit

/// Android's personal items on the iPhone (PersonalModel.swift): each kept in its own key of the
/// app's `UserDefaults` and read back by a new model, as after a relaunch.
@MainActor
final class PersonalModelTests: XCTestCase {
    private func defaults() -> UserDefaults {
        let name = "PersonalModelTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    func testThemeFavouriteStationsAndTrainTypesSurviveARelaunch() {
        let store = defaults()
        let model = PersonalModel(defaults: store)
        XCTAssertEqual(model.theme, .system)
        model.setTheme(.dark)
        let pavia = StationIdentity(id: "S01860", name: "Pavia", railwayCode: "S01860")
        model.toggleFavourite(pavia)
        model.setExcludedTrainTypes([.italo, .replacementBus], at: pavia.id)

        let relaunched = PersonalModel(defaults: store)
        XCTAssertEqual(relaunched.theme, .dark)
        XCTAssertEqual(relaunched.favouriteStations, [pavia])
        XCTAssertEqual(relaunched.excludedTrainTypes(at: pavia.id), [.italo, .replacementBus])
        relaunched.toggleFavourite(pavia)
        XCTAssertTrue(relaunched.favouriteStations.isEmpty)
    }

    func testSavedTimetablesRenameAndDelete() throws {
        let store = defaults()
        let model = PersonalModel(defaults: store)
        let row = TimetableRow(departure: "07:42", arrival: nil, durationMinutes: nil, trains: "REG 2153", changes: 0, source: "Orario")
        let timetable = SavedTimetable(
            id: "t1", name: "Milano Centrale → Pavia", origin: "Milano Centrale", destination: "Pavia",
            serviceDate: try XCTUnwrap(PlannedLocalDate(iso: "2026-11-24")), fromTime: "07:30", savedAt: Date(), rows: [row]
        )
        model.saveTimetable(timetable)
        model.renameTimetable(id: "t1", name: "Casa")
        XCTAssertEqual(PersonalModel(defaults: store).savedTimetables.map(\.name), ["Casa"])
        model.deleteTimetable(id: "t1")
        XCTAssertTrue(PersonalModel(defaults: store).savedTimetables.isEmpty)
    }

    func testOnePunctualityObservationPerTrainAndDayAndNamesOfMyTrains() {
        let store = defaults()
        let model = PersonalModel(defaults: store)
        let today = PersonalModel.today()
        model.record(PunctualityObservation(serviceDate: today, trainNumber: "REG 2153", operatorName: "Trenord", delayMinutes: 4, cancelled: false))
        model.record(PunctualityObservation(serviceDate: today, trainNumber: "reg 2153", operatorName: "Trenord", delayMinutes: 9, cancelled: false))
        let relaunched = PersonalModel(defaults: store)
        XCTAssertEqual(relaunched.punctuality.map(\.delayMinutes), [9])
        relaunched.clearPunctuality(number: "REG 2153")
        XCTAssertTrue(relaunched.punctuality.isEmpty)

        model.renameTrain(id: "2153@S01700", name: "  Il mio treno delle 7:42  ")
        XCTAssertEqual(PersonalModel(defaults: store).name(ofTrain: "2153@S01700"), "Il mio treno delle 7:42")
        model.renameTrain(id: "2153@S01700", name: " ")
        XCTAssertNil(PersonalModel(defaults: store).name(ofTrain: "2153@S01700"))
    }

    func testPinnedRoutesComeFirstAndTheActiveRouteSurvivesARelaunch() throws {
        let store = defaults()
        let model = PersonalModel(defaults: store)
        let milano = StationIdentity(id: "S01700", name: "Milano Centrale")
        let pavia = StationIdentity(id: "S01860", name: "Pavia")
        let roma = StationIdentity(id: "S08409", name: "Roma Termini")
        let first = try SavedRoute(id: SavedRoute.routeIdentifier(origin: milano, destination: pavia), name: "Casa", origin: milano, destination: pavia)
        let second = try SavedRoute(id: SavedRoute.routeIdentifier(origin: milano, destination: roma), name: "Roma", origin: milano, destination: roma)
        XCTAssertEqual(model.activeRoute(in: [first, second])?.id, first.id)
        model.togglePin(routeID: second.id)
        XCTAssertEqual(model.ordered([first, second]).map(\.id), [second.id, first.id])
        XCTAssertEqual(model.activeRoute(in: [first, second])?.id, second.id)
        model.setActiveRoute(first.id)
        let relaunched = PersonalModel(defaults: store)
        XCTAssertEqual(relaunched.activeRoute(in: [first, second])?.id, first.id)
        XCTAssertEqual(relaunched.activeRoute(in: [second])?.id, second.id, "a route no longer saved falls back")
    }

    func testOnlyAndroidsLinksAreAccepted() throws {
        func link(_ text: String) throws -> AppLink? { AppLinks.link(try XCTUnwrap(URL(string: text))) }
        XCTAssertEqual(try link("binario://agenda"), .agenda)
        XCTAssertEqual(try link("binario://route/active"), .activeRoute)
        XCTAssertEqual(try link("binario://departures/next"), .nextDepartures)
        XCTAssertEqual(try link("binario://train/search"), .trainSearch(nil))
        XCTAssertEqual(try link("binario://train/search?number=2153"), .trainSearch("2153"))
        for refused in [
            "binario://for-you", "binario://agenda?x=1", "binario://train/search?number=21%2053",
            "binario://train/search?number=1234567890123", "binario://user@agenda", "binario://agenda:80",
            "binario://agenda#top", "https://agenda", "binario://route/other",
        ] {
            XCTAssertNil(try link(refused), refused)
        }
    }
}
