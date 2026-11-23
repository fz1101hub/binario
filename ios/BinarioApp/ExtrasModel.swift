import Foundation
import Combine
import BinarioKit

/// Android's features beyond trains on the iPhone: the weather at the destination (Open-Meteo),
/// bike sharing near a station (GBFS), and the switches of the two evening alerts. Nothing is
/// asked until a section asks for it; every request goes through BinarioKit's live core (HTTPS,
/// the honest User-Agent, a pause at the first refusal: `SharedData.SourceManners`). Nothing
/// received is stored on the device.
@MainActor
final class ExtrasModel: ObservableObject {
    enum Load<Value: Equatable>: Equatable {
        case loading
        case ready(Value)
    }

    /// «Meteo a destinazione», on by default as on Android.
    @Published private(set) var destinationWeatherEnabled: Bool
    /// «Scioperi del giorno dopo», on by default as on Android.
    @Published private(set) var strikeReminderEnabled: Bool
    /// «Lavori di domani sulle tue tratte», off by default as on Android.
    @Published private(set) var routeWorksAlertEnabled: Bool
    /// The weather sections, by `weatherKey`.
    @Published private(set) var weather: [String: Load<DestinationWeatherResult>] = [:]
    /// The bike sharing sections, by station id; nothing until the user taps «Mostra».
    @Published private(set) var bikes: [String: Load<BikeSharingResult>] = [:]

    let weatherService: DestinationWeatherService
    let bikeService: BikeSharingService
    private let preferences: ExtrasPreferenceStore
    private var weatherLoadedAt: [String: Date] = [:]
    private var coordinates: (count: Int, index: StationCoordinateIndex)?
    private var stationsByID: (count: Int, stations: [String: Station])?

    /// A forecast shown again within this time is not asked again (the service keeps it for hours).
    private static let weatherReload: TimeInterval = 5 * 60

    /// Bird Roma and any other `personalOnly` system of the shared table: only in a build compiled
    /// with the `BINARIO_PERSONAL_SOURCES` Swift condition, as Android's personal builds
    /// (`-Pbinario.personalSources=true`); never in the App Store build.
    nonisolated static let personalSources: Bool = {
        #if BINARIO_PERSONAL_SOURCES
        return true
        #else
        return false
        #endif
    }()

    init(
        weatherService: DestinationWeatherService = .standard(),
        bikeService: BikeSharingService = .standard(personalSources: ExtrasModel.personalSources),
        preferences: ExtrasPreferenceStore = ExtrasPreferenceStore()
    ) {
        self.weatherService = weatherService
        self.bikeService = bikeService
        self.preferences = preferences
        destinationWeatherEnabled = preferences.destinationWeatherEnabled
        strikeReminderEnabled = preferences.strikeReminderEnabled
        routeWorksAlertEnabled = preferences.routeWorksAlertEnabled
    }

    /// Whether an evening alert may read the MIT or RFI feed in the background.
    var anyEveningAlertEnabled: Bool { strikeReminderEnabled || routeWorksAlertEnabled }

    func setDestinationWeatherEnabled(_ enabled: Bool) {
        preferences.destinationWeatherEnabled = enabled
        destinationWeatherEnabled = enabled
        if !enabled { weather = [:] }
    }

    /// Turning an alert on asks for the notification permission (once; iOS remembers the answer)
    /// and plans the next evening run; turning both off cancels it.
    func setStrikeReminderEnabled(_ enabled: Bool) {
        preferences.strikeReminderEnabled = enabled
        strikeReminderEnabled = enabled
        alertsChanged(turnedOn: enabled, immediate: false)
    }

    func setRouteWorksAlertEnabled(_ enabled: Bool) {
        preferences.routeWorksAlertEnabled = enabled
        routeWorksAlertEnabled = enabled
        alertsChanged(turnedOn: enabled, immediate: enabled)
    }

    private func alertsChanged(turnedOn: Bool, immediate: Bool) {
        guard turnedOn else {
            EveningAlerts.schedule()
            return
        }
        Task {
            #if canImport(UserNotifications)
            _ = await SystemAgendaNotificationCenter().requestPermission()
            #endif
            EveningAlerts.schedule(immediateWorksAlert: immediate)
        }
    }

    // MARK: Catalogue lookups

    /// The catalogue station a published name places, when it has coordinates (Android's
    /// `StationCoordinateIndex`).
    func coordinates(of name: String, catalogue: [Station]) -> Station? {
        if coordinates?.count != catalogue.count {
            coordinates = (catalogue.count, StationCoordinateIndex(catalogue))
        }
        return coordinates?.index.find(name)
    }

    /// The catalogue by id, for the map's stops.
    func stations(_ catalogue: [Station]) -> [String: Station] {
        if let kept = stationsByID, kept.count == catalogue.count { return kept.stations }
        var byID: [String: Station] = [:]
        for station in catalogue where byID[station.id] == nil { byID[station.id] = station }
        stationsByID = (catalogue.count, byID)
        return byID
    }

    // MARK: Weather at the destination

    static func weatherKey(_ station: Station, date: PlannedLocalDate) -> String {
        "\(station.id)@\(date.iso)"
    }

    /// Asks the forecast of `date` at `station` when the switch is on: a section shown again soon
    /// after keeps the forecast it has.
    func loadWeather(_ station: Station, date: PlannedLocalDate, now: Date = Date()) {
        guard destinationWeatherEnabled, let latitude = station.latitude, let longitude = station.longitude else { return }
        let key = Self.weatherKey(station, date: date)
        if weather[key] != nil, let loadedAt = weatherLoadedAt[key], now.timeIntervalSince(loadedAt) < Self.weatherReload { return }
        weatherLoadedAt[key] = now
        if weather[key] == nil { weather[key] = .loading }
        let service = weatherService
        Task { [weak self] in
            let result = await service.forecast(latitude: latitude, longitude: longitude, date: date)
            guard let self, !Task.isCancelled, self.destinationWeatherEnabled else { return }
            self.weather[key] = .ready(result)
        }
    }

    // MARK: Bike sharing

    func servesBikes(_ station: Station) -> Bool {
        guard let latitude = station.latitude, let longitude = station.longitude else { return false }
        return bikeService.serves(latitude: latitude, longitude: longitude)
    }

    /// Reads what is near `station`, after the user's tap (or «Riprova»).
    func loadBikes(_ station: Station) {
        guard let latitude = station.latitude, let longitude = station.longitude, bikes[station.id] != .loading else { return }
        bikes[station.id] = .loading
        let service = bikeService
        let id = station.id
        Task { [weak self] in
            let result = await service.nearby(latitude: latitude, longitude: longitude)
            guard let self, !Task.isCancelled else { return }
            self.bikes[id] = .ready(result)
        }
    }

    func resetBikes(_ station: Station) {
        bikes[station.id] = nil
    }
}

/// The switches of the extras, in the app's `UserDefaults` on this iPhone only. Only a boolean the
/// app wrote counts; anything else reads as the default.
final class ExtrasPreferenceStore: @unchecked Sendable {
    static let destinationWeatherKey = "it.binario.ios.destination-weather.v1"
    static let strikeReminderKey = "it.binario.ios.strike-reminder.v1"
    static let routeWorksAlertKey = "it.binario.ios.route-works-alert.v1"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var destinationWeatherEnabled: Bool {
        get { flag(Self.destinationWeatherKey, fallback: true) }
        set { defaults.set(newValue, forKey: Self.destinationWeatherKey) }
    }

    var strikeReminderEnabled: Bool {
        get { flag(Self.strikeReminderKey, fallback: true) }
        set { defaults.set(newValue, forKey: Self.strikeReminderKey) }
    }

    var routeWorksAlertEnabled: Bool {
        get { flag(Self.routeWorksAlertKey, fallback: false) }
        set { defaults.set(newValue, forKey: Self.routeWorksAlertKey) }
    }

    private func flag(_ key: String, fallback: Bool) -> Bool {
        guard let stored = defaults.object(forKey: key) as? NSNumber, CFGetTypeID(stored) == CFBooleanGetTypeID() else { return fallback }
        return stored.boolValue
    }
}
