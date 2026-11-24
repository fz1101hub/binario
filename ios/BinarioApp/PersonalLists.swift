import SwiftUI
import BinarioKit

// Android's personal lists on the iPhone: «Recenti e preferite» in the planner, «Recenti» and
// «Nuova tratta» in Per te, pinned and reordered routes, monitored stations and the voice profile.

/// «Recenti e preferite» under the planner's form, before a search: the saved routes (pinned
/// first) and the recent searches; a tap fills the form and searches from now.
struct RecentAndFavouriteRoutesSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel

    var body: some View {
        let saved = personal.ordered(model.savedRoutes)
        let recent = personal.recentRoutes.filter { route in !saved.contains { $0.id == route.id } }
        if !saved.isEmpty || !recent.isEmpty {
            Section("planner.recent.title") {
                ForEach(saved) { route in
                    Button {
                        model.openPlanner(for: route)
                    } label: {
                        Label(route.name, systemImage: personal.isPinned(routeID: route.id) ? "star.fill" : "star")
                    }
                }
                ForEach(recent) { route in
                    Button {
                        model.openPlanner(origin: route.origin, destination: route.destination)
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: "\(route.origin.name) → \(route.destination.name)")
                                Text("planner.recent.search")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "clock.arrow.circlepath")
                        }
                    }
                }
            }
        }
    }
}

/// «Recenti» of Per te.
struct RecentRoutesSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel

    var body: some View {
        Section("saved.routes.recent") {
            if personal.recentRoutes.isEmpty {
                Text("saved.routes.no_recent")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(personal.recentRoutes) { route in
                Button {
                    model.openPlanner(origin: route.origin, destination: route.destination)
                } label: {
                    Label(String(localized: "planner.recent.search") + ": \(route.origin.name) → \(route.destination.name)", systemImage: "clock.arrow.circlepath")
                }
            }
        }
    }
}

/// The actions of a saved route (Android's route menu): pin or unpin, move up or down; in the
/// row's context menu, beside «Mostra su Watch».
struct SavedRouteMenuItems: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel
    let route: SavedRoute

    var body: some View {
        let pinned = personal.isPinned(routeID: route.id)
        let index = model.savedRoutes.firstIndex { $0.id == route.id } ?? 0
        Button {
            personal.togglePin(routeID: route.id)
        } label: {
            Label(LocalizedStringKey(pinned ? "saved.routes.unpin" : "saved.routes.pin"), systemImage: pinned ? "star.slash" : "star")
        }
        Button {
            model.moveRoute(id: route.id, by: -1)
        } label: {
            Label(PersonalTexts.format("saved.routes.move_up", route.name), systemImage: "arrow.up")
        }
        .disabled(index == 0)
        Button {
            model.moveRoute(id: route.id, by: 1)
        } label: {
            Label(PersonalTexts.format("saved.routes.move_down", route.name), systemImage: "arrow.down")
        }
        .disabled(index >= model.savedRoutes.count - 1)
    }
}

/// The same actions for VoiceOver.
struct SavedRouteAccessibilityActions: ViewModifier {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel
    let route: SavedRoute

    func body(content: Content) -> some View {
        let pinned = personal.isPinned(routeID: route.id)
        content
            .accessibilityAction(named: Text(LocalizedStringKey(pinned ? "saved.routes.unpin" : "saved.routes.pin"))) {
                personal.togglePin(routeID: route.id)
            }
            .accessibilityAction(named: Text(PersonalTexts.format("saved.routes.move_up", route.name))) {
                model.moveRoute(id: route.id, by: -1)
            }
            .accessibilityAction(named: Text(PersonalTexts.format("saved.routes.move_down", route.name))) {
                model.moveRoute(id: route.id, by: 1)
            }
    }
}

/// «Nuova tratta»: origin, destination and an optional name; «Salva e attiva tratta».
struct RouteComposerSheet: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel
    @Environment(\.dismiss) private var dismiss
    @State private var origin: Station?
    @State private var destination: Station?
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("saved.composer.description")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    StationPickerField(titleKey: "planner.origin", selection: $origin)
                    Button {
                        (origin, destination) = (destination, origin)
                    } label: {
                        Label("saved.composer.swap", systemImage: "arrow.up.arrow.down")
                    }
                    StationPickerField(titleKey: "planner.destination", selection: $destination)
                    TextField("saved.composer.name", text: $name, prompt: Text("saved.composer.name_example"))
                }
                Section {
                    Button("saved.composer.save") {
                        guard let origin, let destination else { return }
                        if model.saveRoute(origin: StationIdentity(origin), destination: StationIdentity(destination), name: name) {
                            // «Salva e attiva tratta».
                            personal.setActiveRoute(SavedRoute.routeIdentifier(origin: StationIdentity(origin), destination: StationIdentity(destination)))
                            dismiss()
                        }
                    }
                    .disabled(origin == nil || destination == nil || origin?.id == destination?.id)
                }
            }
            .navigationTitle("saved.composer.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("action.cancel") { dismiss() }
                }
            }
        }
    }
}

/// «Monitora questa stazione» on a board: what is followed, or «Rimuovi monitoraggio stazione».
struct StationMonitorMenu: View {
    @EnvironmentObject private var personal: PersonalModel
    let station: StationIdentity

    var body: some View {
        let current = personal.monitor(of: station)
        Menu {
            ForEach(PersonalModel.MonitorMode.allCases) { mode in
                Button {
                    personal.setMonitor(station, mode: mode)
                } label: {
                    if current?.mode == mode {
                        Label(LocalizedStringKey(mode.titleKey), systemImage: "checkmark")
                    } else {
                        Text(LocalizedStringKey(mode.titleKey))
                    }
                }
            }
            if current != nil {
                Button("departures.monitor.stop", role: .destructive) {
                    personal.setMonitor(station, mode: nil)
                }
            }
        } label: {
            Label(LocalizedStringKey(current == nil ? "departures.monitor.start" : "departures.monitor.title"),
                  systemImage: current == nil ? "eye" : "eye.fill")
        }
    }
}

/// «Stazioni monitorate» of Per te: a tap opens the station's board.
struct StationMonitorsSection: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var personal: PersonalModel

    var body: some View {
        if !personal.stationMonitors.isEmpty {
            Section("saved.monitors.title") {
                ForEach(personal.stationMonitors) { monitor in
                    Button {
                        model.selectBoardStation(monitor.station)
                        model.selectedTab = .departures
                    } label: {
                        Text(PersonalTexts.format("saved.monitors.summary", monitor.station.name,
                                                  NSLocalizedString(monitor.mode.titleKey, comment: "").lowercased()))
                            .foregroundStyle(.primary)
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            personal.setMonitor(monitor.station, mode: nil)
                        } label: {
                            Label(PersonalTexts.format("saved.monitors.remove", monitor.station.name), systemImage: "trash")
                        }
                    }
                }
            }
        }
    }
}

/// «Integrazioni Binario»: the voice profile for Alexa, as Android's switch. Nothing is sent: the
/// profile needs an Alexa account link, which this app does not make.
struct IntegrationsSection: View {
    @EnvironmentObject private var personal: PersonalModel

    var body: some View {
        Section {
            Text("settings.integrations.description")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Toggle(isOn: Binding(get: { personal.voiceProfileRequested }, set: { personal.setVoiceProfileRequested($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("settings.voice.title")
                    Text(LocalizedStringKey(personal.voiceProfileRequested ? "settings.voice.link_required" : "settings.voice.disabled"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Text("settings.voice.alexa_note")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } header: {
            Text("settings.integrations.title")
        }
    }
}
