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
                            VStack(alignment: .leading, spacing: 4) {
                                Text(route.name)
                                    .font(.headline)
                                Text("\(route.origin.name) → \(route.destination.name)")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel(Text("\(String(localized: "accessibility.saved_route")): \(route.name)"))
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
