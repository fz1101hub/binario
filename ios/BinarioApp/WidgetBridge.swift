import BinarioKit
import Foundation
import SwiftUI
import WidgetKit

/// What the app shares with its home-screen widgets (the WidgetKit extension ios/BinarioWidgets),
/// through their App Group and on this iPhone only: the timetable index it installs, «Dati in tempo
/// reale» and «I miei treni». Each change redraws the widgets that show it, as Android's app feeds its
/// widgets (`TrainsWidgetProvider.onFavouritesChanged`).
enum WidgetBridge {
    /// The folder the app installs the bundled timetable index into: the App Group's, so the widgets read
    /// the same file, when this build has the group; the copy an earlier version made in the app's own
    /// folder (`fallback`) is then removed. Without the group, `fallback`.
    static func scheduleDirectory(fallback: URL) -> URL {
        guard let shared = WidgetAppGroup.scheduleDirectory(),
              (try? FileManager.default.createDirectory(at: shared, withIntermediateDirectories: true)) != nil else { return fallback }
        if shared.standardizedFileURL != fallback.standardizedFileURL, FileManager.default.fileExists(atPath: fallback.path) {
            Task.detached(priority: .background) { try? FileManager.default.removeItem(at: fallback) }
        }
        return shared
    }

    /// The index the app has installed: the widgets read it from now on.
    static func scheduleInstalled(_ database: URL) {
        guard let shared = WidgetAppGroup.scheduleDirectory(), let store = WidgetSharedStore.shared(),
              database.deletingLastPathComponent().standardizedFileURL.path == shared.standardizedFileURL.path,
              store.scheduleFileName != database.lastPathComponent else { return }
        store.setScheduleFileName(database.lastPathComponent)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// At launch: the switch and the trains as they are, for a widget added before this version.
    static func share(favourites: [FavouriteTrain], liveDataEnabled: Bool) {
        guard let store = WidgetSharedStore.shared() else { return }
        store.setLiveDataEnabled(liveDataEnabled)
        try? store.favouriteTrains.save(FavouriteTrainCollection.normalized(favourites))
    }

    static func favouritesChanged(_ collection: FavouriteTrainCollection) {
        guard let store = WidgetSharedStore.shared() else { return }
        try? store.favouriteTrains.save(collection)
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetKindName.trains.rawValue)
    }

    static func liveDataChanged(_ enabled: Bool) {
        guard let store = WidgetSharedStore.shared() else { return }
        store.setLiveDataEnabled(enabled)
        WidgetCenter.shared.reloadAllTimelines()
    }
}

/// A tap on a widget: the planner on its route, «Partenze» on its station, «I miei treni», or
/// «📍 Più vicina», which reads one position here, in the foreground, as the app's nearest station does.
struct WidgetLinkHandler: ViewModifier {
    @EnvironmentObject private var model: AppModel
    @State private var nearest: WidgetNearestRequest?

    func body(content: Content) -> some View {
        content
            .onOpenURL { url in open(url) }
            .sheet(item: $nearest) { request in
                WidgetNearestSheet(configured: request.station)
                    .presentationDetents([.medium])
            }
    }

    private func open(_ url: URL) {
        guard url.scheme == "binario", url.host == "widgets",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
        var query: [String: String] = [:]
        for item in components.queryItems ?? [] { query[item.name] = item.value }
        switch components.path {
        case "/route":
            guard let origin = identity(query["origin"], query["originName"]),
                  let destination = identity(query["destination"], query["destinationName"]) else { return }
            model.openPlanner(origin: origin, destination: destination)
        case "/station":
            guard let station = identity(query["id"], query["name"]) else { return }
            model.selectBoardStation(station)
            model.selectedTab = .departures
        case "/train", "/trains":
            model.selectedTab = .trains
        case "/nearest":
            guard let station = identity(query["id"], query["name"]) else { return }
            nearest = WidgetNearestRequest(station: station)
        default:
            return
        }
    }

    /// The catalogue's station when it is loaded (its railway code), else the one the link names.
    private func identity(_ identifier: String?, _ name: String?) -> StationIdentity? {
        guard let identifier, !identifier.isEmpty else { return nil }
        if case let .available(catalog) = model.catalogState, let station = catalog.station(id: identifier) {
            return StationIdentity(station)
        }
        guard let name, !name.isEmpty else { return nil }
        return StationIdentity(id: identifier, name: name)
    }
}

struct WidgetNearestRequest: Identifiable {
    let station: StationIdentity
    var id: String { station.id }
}

/// Android's `StationWidgetNearestActivity`: one position, the nearest station of the catalogue becomes
/// the widget's station; when the permission is denied or no position comes, the station stays and the
/// widget says why. The position is passed to that computation and kept nowhere.
struct WidgetNearestSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let configured: StationIdentity

    @State private var locator: OneShotLocation?
    @State private var message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("app.widget_nearest.title")
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            if let message {
                Text(message)
                Button("action.ok") { dismiss() }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
            } else {
                HStack(spacing: 12) {
                    ProgressView()
                    Text("app.widget_nearest.locating")
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .task { await locate() }
    }

    private func locate() async {
        guard locator == nil else { return }
        let locator = OneShotLocation()
        self.locator = locator
        let store = WidgetSharedStore.shared()
        let kept = store?.nearest(configuredStationID: configured.id)?.station
        let shownName = kept?.name ?? configured.name
        let outcome = await locator.locate()
        // A launch by the widget: the catalogue may still be loading.
        for _ in 0..<20 {
            guard case .loading = model.catalogState else { break }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        let note: WidgetNearestState.Note
        switch outcome {
        case .denied:
            note = .permissionDenied
            message = String.localizedStringWithFormat(NSLocalizedString("app.widget_nearest.denied", comment: ""), shownName)
        case .unavailable:
            note = .locationUnavailable
            message = String.localizedStringWithFormat(NSLocalizedString("app.widget_nearest.unavailable", comment: ""), shownName)
        case let .located(latitude, longitude):
            if let nearest = await model.nearestStation(latitude: latitude, longitude: longitude) {
                store?.saveNearest(
                    WidgetNearestState(station: StationIdentity(nearest.station), note: nil, at: Date()), configuredStationID: configured.id
                )
                WidgetCenter.shared.reloadTimelines(ofKind: WidgetKindName.station.rawValue)
                dismiss()
                return
            }
            note = .noneNearby
            message = String.localizedStringWithFormat(NSLocalizedString("app.widget_nearest.none", comment: ""), shownName)
        }
        store?.saveNearest(WidgetNearestState(station: kept, note: note, at: Date()), configuredStationID: configured.id)
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetKindName.station.rawValue)
    }
}
