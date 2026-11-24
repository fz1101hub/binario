import SwiftUI
import BinarioKit

/// «Per te», as Android's: the Agenda and «Impostazioni», the saved routes («Le tue tratte») with the
/// works on them, «Orari salvati», the favourite stations and «I miei treni». Everything is stored on
/// this iPhone.
struct SavedRoutesView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var notices: RailNoticesModel
    @EnvironmentObject private var personal: PersonalModel
    /// «Nuova tratta».
    @State private var composing = false
    /// «Elimina tutte le tratte» asks first: one tap would delete up to 32 routes and the Watch pin.
    @State private var confirmingDeleteAll = false

    var body: some View {
        List {
            // «Agenda»: the planned journeys, a page of this tab rather than a sixth tab.
            Section {
                NavigationLink {
                    AgendaView()
                } label: {
                    Label {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("saved.agenda.title")
                                .foregroundStyle(.primary)
                            Text("saved.agenda.subtitle")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "calendar")
                    }
                }
                // «Impostazioni», reached from Per te as on Android.
                NavigationLink {
                    SettingsView()
                } label: {
                    Label("settings.title", systemImage: "gearshape")
                }
            }

            if model.savedRoutes.isEmpty {
                Section {
                    ContentUnavailableView(
                        "saved.empty.title",
                        systemImage: "star",
                        description: Text("saved.empty.body")
                    )
                }
            } else {
                Section {
                    // Pinned routes first, as on Android.
                    ForEach(personal.ordered(model.savedRoutes)) { route in
                        let pinned = model.pinnedWatchRouteID == route.id
                        // One tap opens the planner on this route and searches it from now.
                        Button {
                            // As on Android, the route opened becomes the active one.
                            personal.setActiveRoute(route.id)
                            model.openPlanner(for: route)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(route.name)
                                    .font(.headline)
                                    .foregroundStyle(.primary)
                                Text("\(route.origin.name) → \(route.destination.name)")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                if personal.isPinned(routeID: route.id) {
                                    Label("saved.routes.pinned", systemImage: "star.fill")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                if pinned {
                                    Label("saved.watch.pinned", systemImage: "applewatch")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        // A button is one element already; the label replaces its two lines.
                        .accessibilityLabel(Text("\(String(localized: "accessibility.saved_route")): \(route.name)"))
                        .accessibilityHint(Text("accessibility.open_saved_route"))
                        .accessibilityAction(named: Text("accessibility.delete_route")) {
                            if let index = model.savedRoutes.firstIndex(where: { $0.id == route.id }) {
                                model.deleteRoutes(at: IndexSet(integer: index))
                            }
                        }
                        // «Mostra su Watch»: one pinned route, sent to the Watch when the iPhone has
                        // no search for now to show (sharing on).
                        .accessibilityAction(named: Text(LocalizedStringKey(pinned ? "saved.watch.stop" : "saved.watch.show"))) {
                            model.setWatchPin(route, pinned: !pinned)
                        }
                        .swipeActions(edge: .leading) {
                            WatchPinButton(pinned: pinned) { model.setWatchPin(route, pinned: !pinned) }
                        }
                        .modifier(SavedRouteAccessibilityActions(route: route))
                        .contextMenu {
                            WatchPinButton(pinned: pinned) { model.setWatchPin(route, pinned: !pinned) }
                            SavedRouteMenuItems(route: route)
                        }
                    }
                    .onDelete { offsets in
                        // The rows are listed pinned first: delete by identity, in the model's order.
                        let shown = personal.ordered(model.savedRoutes)
                        let ids = Set(offsets.compactMap { shown.indices.contains($0) ? shown[$0].id : nil })
                        model.deleteRoutes(at: IndexSet(model.savedRoutes.indices.filter { ids.contains(model.savedRoutes[$0].id) }))
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("saved.local_only")
                        Text("saved.watch.footer")
                        if model.pinnedWatchRouteID != nil, !model.watchSharingEnabled {
                            Text("saved.watch.sharing_off")
                        }
                    }
                }

                // «Lavori e interruzioni sulle tue tratte», as Android's «Per te».
                RouteWorksSection(routes: model.savedRoutes, scope: .savedRoutes)

                Section {
                    Button("saved.delete_all", role: .destructive) {
                        confirmingDeleteAll = true
                    }
                    .confirmationDialog("saved.delete_all", isPresented: $confirmingDeleteAll, titleVisibility: .visible) {
                        Button("saved.delete_all", role: .destructive) {
                            model.deleteAllRoutes()
                        }
                    }
                }
            }

            // «Nuova tratta» and «Recenti», as Android's «Le tue tratte» (PersonalLists.swift).
            Section {
                Button {
                    composing = true
                } label: {
                    Label("saved.composer.title", systemImage: "plus")
                }
            }
            RecentRoutesSection()

            // The rest of Android's «Per te» (PersonalViews.swift).
            SavedTimetablesSection()
            StationMonitorsSection()
            FavouriteStationsSection()
            Section {
                NavigationLink {
                    MyTrainsView()
                } label: {
                    Label("saved.trains.title", systemImage: "star")
                }
            }
        }
        .navigationTitle("tab.for_you")
        .sheet(isPresented: $composing) { RouteComposerSheet() }
        .loadsRouteWorks(model.savedRoutes, model: model, notices: notices)
        // The Home-screen quick action «Agenda» opens the page as the row does.
        .navigationDestination(isPresented: $model.showingAgenda) {
            AgendaView()
        }
        // The limit and storage errors are RootView's alert, shown on whichever tab caused them.
    }
}

/// «Mostra su Watch» or «Non mostrare su Watch» for one saved route.
private struct WatchPinButton: View {
    let pinned: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            Label(LocalizedStringKey(pinned ? "saved.watch.stop" : "saved.watch.show"), systemImage: "applewatch")
        }
        .tint(.accentColor)
    }
}
