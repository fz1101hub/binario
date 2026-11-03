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
                        }
                        .onDelete(perform: model.deleteRoutes)
                    } footer: {
                        Text("saved.local_only")
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
        .alert(
            LocalizedStringKey(model.persistenceMessageKey ?? "saved.storage_error"),
            isPresented: Binding(
                get: { model.persistenceMessageKey != nil },
                set: { if !$0 { model.persistenceMessageKey = nil } }
            )
        ) {
            Button("action.ok", role: .cancel) { model.persistenceMessageKey = nil }
        }
    }
}
