import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView(selection: $model.selectedTab) {
            NavigationStack { PlannerView() }
                .tabItem { Label("tab.planner", systemImage: "point.topleft.down.to.point.bottomright.curvepath") }
                .tag(AppModel.Tab.planner)

            // «Partenze» second, as on Android (Pianifica, Partenze, Per te): the board of a
            // station is a task of its own, not a step of a route search.
            NavigationStack { DeparturesView() }
                .tabItem { Label("tab.departures", systemImage: "list.bullet.rectangle") }
                .tag(AppModel.Tab.departures)

            NavigationStack { SavedRoutesView() }
                .tabItem { Label("tab.saved", systemImage: "star") }
                .tag(AppModel.Tab.saved)

            NavigationStack { MyTrainsView() }
                .tabItem { Label("tab.trains", systemImage: "train.side.front.car") }
                .tag(AppModel.Tab.trains)

            NavigationStack { SettingsView() }
                .tabItem { Label("tab.settings", systemImage: "gearshape") }
                .tag(AppModel.Tab.settings)
        }
        // Live data only in the foreground: the requests on their way stop when the app leaves it,
        // and the screen on display asks again when it comes back. No background mode, no refresh
        // on a schedule.
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: model.liveDidBecomeActive()
            case .background: model.liveDidEnterBackground()
            default: break
            }
        }
        // The Agenda's reminders again: some fired, journeys departed or the permission changed
        // while the app was away. Nothing is asked to the user here.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.refreshReminders() }
            }
        }
        .task { await expireLiveValues() }
        // Saving or deleting a route can fail on any tab (the planner's «Salva tratta», «Tratte»):
        // the alert is shown where it happened, not when «Tratte» is opened later.
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

    /// Every minute while the app is open: live values older than `AppModel.liveDisplaySeconds`
    /// stop being shown. Nothing is asked here.
    private func expireLiveValues() async {
        while !Task.isCancelled {
            model.expireLiveValues(now: Date())
            try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)
        }
    }
}
