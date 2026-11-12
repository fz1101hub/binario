import SwiftUI
import BinarioKit

struct SavedRoutesView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Group {
            if model.savedRoutes.isEmpty {
                ContentUnavailableView(
                    "saved.empty.title",
                    systemImage: "star",
                    description: Text("saved.empty.body")
                )
            } else {
                List {
                    Section {
                        ForEach(model.savedRoutes) { route in
                            let pinned = model.pinnedWatchRouteID == route.id
                            // One tap opens the planner on this route and searches it from now.
                            Button {
                                model.openPlanner(for: route)
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(route.name)
                                        .font(.headline)
                                        .foregroundStyle(.primary)
                                    Text("\(route.origin.name) → \(route.destination.name)")
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
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
                            .contextMenu {
                                WatchPinButton(pinned: pinned) { model.setWatchPin(route, pinned: !pinned) }
                            }
                        }
                        .onDelete(perform: model.deleteRoutes)
                    } footer: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("saved.local_only")
                            Text("saved.watch.footer")
                            if model.pinnedWatchRouteID != nil, !model.watchSharingEnabled {
                                Text("saved.watch.sharing_off")
                            }
                        }
                    }

                    Section {
                        Button("saved.delete_all", role: .destructive) {
                            model.deleteAllRoutes()
                        }
                    }
                }
            }
        }
        .navigationTitle("saved.title")
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
