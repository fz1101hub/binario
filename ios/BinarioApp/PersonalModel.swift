import Foundation
import Combine
import SwiftUI
import BinarioKit

/// Android's personal items that the iPhone keeps beside `AppModel`: «Orari salvati», the favourite
/// stations, the train types hidden at each station, the names given to «I miei treni», the
/// personal punctuality history and the app theme. Everything stays in this iPhone's
/// `UserDefaults` and leaves it only in the local backup the user exports.
@MainActor
final class PersonalModel: ObservableObject {
    /// Android's «Sistema», «Chiaro», «Scuro» (`AppThemeMode`), in the order of its settings.
    enum Theme: String, CaseIterable, Identifiable {
        case light = "LIGHT"
        case dark = "DARK"
        case system = "SYSTEM"

        var id: String { rawValue }

        var colorScheme: ColorScheme? {
            switch self {
            case .system: nil
            case .light: .light
            case .dark: .dark
            }
        }

        var titleKey: LocalizedStringKey {
            switch self {
            case .system: "settings.theme.system"
            case .light: "settings.theme.light"
            case .dark: "settings.theme.dark"
            }
        }
    }

    /// At most as many favourite stations as a backup carries.
    static let maximumFavouriteStations = 32
    /// Android's `FavouriteTrains.MAX_NAME_LENGTH`.
    static let maximumTrainNameLength = 40

    @Published private(set) var savedTimetables: [SavedTimetable]
    @Published private(set) var favouriteStations: [StationIdentity]
    @Published private(set) var trainNames: [String: String]
    @Published private(set) var punctuality: [PunctualityObservation]
    @Published private(set) var theme: Theme
    /// Bumped when the hidden train types of any station change, so the boards filter again.
    @Published private(set) var trainTypesGeneration = 0
    /// «Recenti»: the last route searches, newest first (Android keeps 8).
    @Published private(set) var recentRoutes: [RecentRoute]
    /// «Tratta fissata»: the pinned saved routes, listed first as on Android.
    @Published private(set) var pinnedRouteIDs: Set<String>
    /// «Monitora questa stazione»: the stations followed on this iPhone, with what is followed.
    @Published private(set) var stationMonitors: [StationMonitor]
    /// «Comandi vocali e Alexa»: the wish to share the voice profile, which needs an Alexa account link.
    @Published private(set) var voiceProfileRequested: Bool

    private let defaults: UserDefaults

    private enum Key {
        static let savedTimetables = "it.binario.ios.saved-timetables.v1"
        static let favouriteStations = "it.binario.ios.favourite-stations.v1"
        static let trainNames = "it.binario.ios.favourite-train-names.v1"
        static let punctuality = "it.binario.ios.punctuality-history.v1"
        static let theme = "it.binario.ios.theme.v1"
        static let trainTypesPrefix = "it.binario.ios.excluded-train-types.v1:"
        static let recentRoutes = "it.binario.ios.recent-routes.v1"
        static let pinnedRoutes = "it.binario.ios.pinned-routes.v1"
        static let stationMonitors = "it.binario.ios.station-monitors.v1"
        static let voiceProfile = "it.binario.ios.voice-profile-requested.v1"
    }

    /// A route searched recently.
    struct RecentRoute: Codable, Equatable, Hashable, Identifiable {
        let origin: StationIdentity
        let destination: StationIdentity
        var id: String { SavedRoute.routeIdentifier(origin: origin, destination: destination) }
    }

    /// Android's `StationMonitorMode`.
    enum MonitorMode: String, Codable, CaseIterable, Identifiable {
        case departures = "DEPARTURES"
        case arrivals = "ARRIVALS"
        case both = "BOTH"

        var id: String { rawValue }

        var titleKey: String {
            switch self {
            case .departures: "departures.monitor.departures"
            case .arrivals: "departures.monitor.arrivals"
            case .both: "departures.monitor.both"
            }
        }
    }

    /// Android's `StationMonitor`: a station followed on this iPhone.
    struct StationMonitor: Codable, Equatable, Identifiable {
        let station: StationIdentity
        let mode: MonitorMode
        var id: String { station.id }
    }

    static let maximumRecentRoutes = 8
    /// As many as Android's backup keeps.
    static let maximumStationMonitors = 12

    init(defaults: UserDefaults = .standard) {
        let stations = PersonalModel.decodeStations(defaults.data(forKey: Key.favouriteStations))
        let history = PunctualityHistory.bound(
            PunctualityHistory.decode(defaults.data(forKey: Key.punctuality)),
            today: PersonalModel.today()
        )
        self.defaults = defaults
        savedTimetables = SavedTimetableCodec.decode(defaults.data(forKey: Key.savedTimetables))
        favouriteStations = stations
        trainNames = defaults.dictionary(forKey: Key.trainNames) as? [String: String] ?? [:]
        theme = Theme(rawValue: defaults.string(forKey: Key.theme) ?? "") ?? .system
        punctuality = history
        recentRoutes = PersonalModel.decode([RecentRoute].self, defaults.data(forKey: Key.recentRoutes)) ?? []
        pinnedRouteIDs = Set(defaults.stringArray(forKey: Key.pinnedRoutes) ?? [])
        stationMonitors = PersonalModel.decode([StationMonitor].self, defaults.data(forKey: Key.stationMonitors)) ?? []
        voiceProfileRequested = defaults.bool(forKey: Key.voiceProfile)
    }

    private static func decode<Value: Decodable>(_ type: Value.Type, _ data: Data?) -> Value? {
        guard let data else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    // MARK: Recenti, tratte fissate

    /// Android's `recordRecent`: the route first, once, at most eight.
    func recordRecent(origin: StationIdentity, destination: StationIdentity) {
        let recent = RecentRoute(origin: origin, destination: destination)
        let updated = Array(([recent] + recentRoutes.filter { $0.id != recent.id }).prefix(Self.maximumRecentRoutes))
        guard updated != recentRoutes else { return }
        recentRoutes = updated
        defaults.set(try? JSONEncoder().encode(updated), forKey: Key.recentRoutes)
    }

    func isPinned(routeID: String) -> Bool { pinnedRouteIDs.contains(routeID) }

    /// The star of a saved route: pinned routes come first.
    func togglePin(routeID: String) {
        if pinnedRouteIDs.contains(routeID) { pinnedRouteIDs.remove(routeID) } else { pinnedRouteIDs.insert(routeID) }
        defaults.set(pinnedRouteIDs.sorted(), forKey: Key.pinnedRoutes)
    }

    /// Saved routes as Android lists them: pinned first, then in their own order.
    func ordered(_ routes: [SavedRoute]) -> [SavedRoute] {
        routes.filter { pinnedRouteIDs.contains($0.id) } + routes.filter { !pinnedRouteIDs.contains($0.id) }
    }

    // MARK: Monitoraggi di stazione

    func monitor(of station: StationIdentity) -> StationMonitor? {
        stationMonitors.first { $0.station.id == station.id }
    }

    func setMonitor(_ station: StationIdentity, mode: MonitorMode?) {
        var monitors = stationMonitors.filter { $0.station.id != station.id }
        if let mode { monitors.insert(StationMonitor(station: station, mode: mode), at: 0) }
        replaceStationMonitors(monitors)
    }

    func replaceStationMonitors(_ value: [StationMonitor]) {
        var seen = Set<String>()
        stationMonitors = Array(value.filter { seen.insert($0.id).inserted }.prefix(Self.maximumStationMonitors))
        defaults.set(try? JSONEncoder().encode(stationMonitors), forKey: Key.stationMonitors)
    }

    // MARK: Integrazioni

    func setVoiceProfileRequested(_ requested: Bool) {
        voiceProfileRequested = requested
        defaults.set(requested, forKey: Key.voiceProfile)
    }

    // MARK: Theme

    func setTheme(_ value: Theme) {
        theme = value
        defaults.set(value.rawValue, forKey: Key.theme)
    }

    // MARK: Orari salvati

    func saveTimetable(_ timetable: SavedTimetable) {
        setTimetables(SavedTimetableCodec.withSaved(savedTimetables, timetable))
    }

    func renameTimetable(id: String, name: String) {
        setTimetables(SavedTimetableCodec.renamed(savedTimetables, id: id, name: name))
    }

    func deleteTimetable(id: String) {
        setTimetables(savedTimetables.filter { $0.id != id })
    }

    func replaceTimetables(_ value: [SavedTimetable]) {
        setTimetables(Array(value.prefix(SavedTimetableCodec.maximumTimetables)))
    }

    private func setTimetables(_ value: [SavedTimetable]) {
        savedTimetables = value
        defaults.set(SavedTimetableCodec.encode(value), forKey: Key.savedTimetables)
    }

    // MARK: Stazioni preferite

    func isFavourite(_ station: StationIdentity) -> Bool {
        favouriteStations.contains { $0.id == station.id }
    }

    /// The star of a board: adds the station first, or removes it.
    func toggleFavourite(_ station: StationIdentity) {
        if isFavourite(station) {
            setFavouriteStations(favouriteStations.filter { $0.id != station.id })
        } else {
            setFavouriteStations(Array(([station] + favouriteStations).prefix(Self.maximumFavouriteStations)))
        }
    }

    func replaceFavouriteStations(_ value: [StationIdentity]) {
        var seen = Set<String>()
        setFavouriteStations(Array(value.filter { seen.insert($0.id).inserted }.prefix(Self.maximumFavouriteStations)))
    }

    private func setFavouriteStations(_ value: [StationIdentity]) {
        favouriteStations = value
        defaults.set(try? JSONEncoder().encode(value), forKey: Key.favouriteStations)
    }

    private static func decodeStations(_ data: Data?) -> [StationIdentity] {
        guard let data, let stations = try? JSONDecoder().decode([StationIdentity].self, from: data) else { return [] }
        return Array(stations.prefix(maximumFavouriteStations))
    }

    // MARK: Tipi di treno

    func excludedTrainTypes(at stationID: String) -> Set<TrainTypeGroup> {
        TrainTypeGroups.decode(defaults.string(forKey: Key.trainTypesPrefix + stationID))
    }

    func setExcludedTrainTypes(_ excluded: Set<TrainTypeGroup>, at stationID: String) {
        if excluded.isEmpty {
            defaults.removeObject(forKey: Key.trainTypesPrefix + stationID)
        } else {
            defaults.set(TrainTypeGroups.encode(excluded), forKey: Key.trainTypesPrefix + stationID)
        }
        trainTypesGeneration &+= 1
    }

    // MARK: I miei treni

    /// The name given to a favourite train, or nil.
    func name(ofTrain id: String) -> String? {
        trainNames[id]
    }

    /// A blank name removes the one given before.
    func renameTrain(id: String, name: String) {
        let clean = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maximumTrainNameLength))
        var names = trainNames
        names[id] = clean.isEmpty ? nil : clean
        setTrainNames(names)
    }

    private func setTrainNames(_ value: [String: String]) {
        trainNames = value
        defaults.set(value, forKey: Key.trainNames)
    }

    // MARK: Puntualità personale

    func punctualityStats(number: String) -> PunctualityStats? {
        PunctualityHistory.stats(punctuality, trainNumber: number)
    }

    /// One observation per train and day: a newer one of the same day replaces the older.
    func record(_ observation: PunctualityObservation) {
        let updated = PunctualityHistory.record(punctuality, [observation], today: Self.today())
        if updated != punctuality { setPunctuality(updated) }
    }

    func clearPunctuality(number: String) {
        setPunctuality(PunctualityHistory.withoutTrain(punctuality, number))
    }

    func replacePunctuality(_ value: [PunctualityObservation]) {
        setPunctuality(PunctualityHistory.bound(value, today: Self.today()))
    }

    private func setPunctuality(_ value: [PunctualityObservation]) {
        punctuality = value
        if value.isEmpty {
            defaults.removeObject(forKey: Key.punctuality)
        } else {
            defaults.set(PunctualityHistory.encode(value), forKey: Key.punctuality)
        }
    }

    /// Today in the railway's time zone.
    nonisolated static func today(_ now: Date = Date()) -> PlannedLocalDate {
        ItalianWallClock.date(of: now) ?? PlannedLocalDate(year: 2026, month: 1, day: 1)!
    }
}
