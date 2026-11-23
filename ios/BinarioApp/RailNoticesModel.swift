import Foundation
import Combine
import BinarioKit

/// The state of the notice sections, Android's `RailNoticesViewModel` and `RouteWorksViewModel`:
/// nothing is asked until a section is shown, every request goes through `RailNoticeService`, and a
/// section shown again after the table's reload interval asks again (the feeds' own caches decide
/// whether that reaches the network). `personal` is «Dati in tempo reale»: a list read with another
/// value is never shown. Nothing is stored on the device. Without the bundled table
/// (`notices-sources-v1.json`) there is no service and no section at all.
@MainActor
final class RailNoticesModel: ObservableObject {
    enum Load<Value: Equatable>: Equatable {
        case loading
        case ready(Value)

        var value: Value? {
            if case let .ready(value) = self { return value }
            return nil
        }
    }

    /// «Avvisi e infomobilità», for the value of `personal` it was read with.
    @Published private(set) var general: Load<GeneralNotices>?
    /// «Scioperi in programma» of the MIT calendar; empty when the feed fails.
    @Published private(set) var upcomingStrikes: [StrikeNotice] = []
    /// The works sections, by `worksKey`; missing while their notices are read.
    @Published private(set) var works: [String: RouteWorks] = [:]
    /// The Trenord notices of station boards, by `stationKey`.
    @Published private(set) var stations: [String: StationNotices] = [:]

    let service: RailNoticeService?
    var rules: NoticeRules? { service?.rules }

    private var generalPersonal: Bool?
    private var generalLoadedAt: Date?
    private var generalTask: Task<Void, Never>?
    private var strikesTask: Task<Void, Never>?
    private var worksLoadedAt: [String: Date] = [:]
    private var stationLoadedAt: [String: Date] = [:]
    /// The matcher of the catalogue it was made for, kept for the process.
    private var matcher: (catalogue: Int, matcher: RouteWorksMatcher)?

    init(service: RailNoticeService?) {
        self.service = service
    }

    /// The bundled table's sources; none (and no section) when the table is missing or invalid.
    static func bootstrap(bundle: Bundle = .main) -> RailNoticesModel {
        let url = bundle.url(forResource: NoticeSourcesTable.resourceName, withExtension: NoticeSourcesTable.resourceExtension)
        return RailNoticesModel(service: RailNoticeService.standard(rules: NoticeRules.load(url: url)))
    }

    private func recent(_ loadedAt: Date?, now: Date) -> Bool {
        guard let loadedAt, let rules else { return false }
        let elapsed = now.timeIntervalSince(loadedAt)
        return elapsed >= 0 && elapsed < rules.reloadAfter
    }

    /// Reads the notices area and the strike calendar; `force` («Aggiorna avvisi») asks again at
    /// once (the feeds still apply their own time to live).
    func loadGeneral(personal: Bool, force: Bool = false, now: Date = Date()) {
        guard let service else { return }
        if !force, generalPersonal == personal, general != nil, recent(generalLoadedAt, now: now) { return }
        // A refresh keeps the list on screen; the list read with the other switch value goes at once.
        if generalPersonal != personal || general?.value == nil { general = .loading }
        generalPersonal = personal
        generalLoadedAt = now
        generalTask?.cancel()
        generalTask = Task { [weak self] in
            let result = await service.general(personal: personal)
            guard let self, !Task.isCancelled, self.generalPersonal == personal else { return }
            self.general = .ready(result)
        }
        strikesTask?.cancel()
        strikesTask = Task { [weak self] in
            let strikes = await service.strikeCalendar()
            guard let self, !Task.isCancelled else { return }
            let current = Date()
            self.upcomingStrikes = ItalianWallClock.date(of: current).map { service.rules.upcoming(strikes, today: $0, now: current) } ?? []
        }
    }

    // MARK: Works on the saved routes

    /// The key of a works section: its routes and the switch.
    static func worksKey(_ routes: [SavedRoute], personal: Bool) -> String {
        routes.map { "\($0.id):\($0.origin.id)>\($0.destination.id):\($0.name)" }.joined(separator: "|") + "|\(personal)"
    }

    /// The first routes a works section reads (Android's `RouteWorksViewModel.MAX_ROUTES`).
    func worksRoutes(_ routes: [SavedRoute]) -> [SavedRoute] {
        guard let rules else { return [] }
        return Array(routes.prefix(rules.maximumWorksRoutes))
    }

    /// Reads the works of `routes`: their two ends, the iPhone's index having no stop list of its
    /// trains (Android's fallback when no train runs direct), matched against the catalogue's names.
    func loadWorks(_ routes: [SavedRoute], stations: [WorksRoute], catalogue: [Station], personal: Bool, now: Date = Date()) {
        guard let service, !routes.isEmpty, !catalogue.isEmpty else { return }
        let key = Self.worksKey(routes, personal: personal)
        if works[key] != nil, recent(worksLoadedAt[key], now: now) { return }
        worksLoadedAt[key] = now
        let rules = service.rules
        let kept = matcher.flatMap { $0.catalogue == catalogue.count ? $0.matcher : nil }
        Task { [weak self] in
            let matcher: RouteWorksMatcher
            if let kept {
                matcher = kept
            } else {
                // Folding every catalogue name takes a moment: off the main actor, once.
                matcher = await Task.detached(priority: .utility) { RouteWorksMatcher(catalogue: catalogue, rules: rules) }.value
                self?.matcher = (catalogue.count, matcher)
            }
            guard let today = ItalianWallClock.date(of: Date()) else { return }
            let result = await service.routeWorks(stations, matcher: matcher, today: today, personal: personal)
            guard let self, !Task.isCancelled else { return }
            self.works[key] = result
        }
    }

    // MARK: Trenord notices of a station board

    static func stationKey(_ station: Station, personal: Bool) -> String {
        "\(station.id)|\(personal)"
    }

    /// Whether a board of `station` shows Trenord's notices: the personal sources on and the station
    /// in the Trenord area, so other boards cause no request.
    func showsTrenordNotices(at station: Station, personal: Bool) -> Bool {
        guard let service else { return false }
        return service.hasTrenordSource(personal: personal) && service.rules.inTrenordArea(station)
    }

    func loadStation(_ station: Station, personal: Bool, now: Date = Date()) {
        guard let service, showsTrenordNotices(at: station, personal: personal) else { return }
        let key = Self.stationKey(station, personal: personal)
        if stations[key] != nil, recent(stationLoadedAt[key], now: now) { return }
        stationLoadedAt[key] = now
        let name = station.name
        Task { [weak self] in
            let result = await service.stationNotices(name, personal: personal)
            guard let self, !Task.isCancelled else { return }
            self.stations[key] = result
        }
    }
}
