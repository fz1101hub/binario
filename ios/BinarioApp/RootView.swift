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

            // «Per te», as on Android: the saved routes, the Agenda, «Orari salvati», the favourite
            // stations, «I miei treni» and «Impostazioni» (SavedRoutesView.swift).
            NavigationStack { SavedRoutesView() }
                .tabItem { Label("tab.for_you", systemImage: "person") }
                .tag(AppModel.Tab.saved)
        }
        // «I miei treni» and «Impostazioni» are pages of «Per te»: a request for their former tabs opens it.
        .onChange(of: model.selectedTab) { _, tab in
            if tab == .trains || tab == .settings { model.selectedTab = .saved }
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
        // A tap on a home-screen widget (ios/BinarioWidgets).
        .modifier(WidgetLinkHandler())
        // The Home-screen quick action «Agenda», at launch or later.
        .onAppear { openAgendaIfAsked() }
        .onReceive(NotificationCenter.default.publisher(for: AgendaQuickAction.notification)) { _ in
            openAgendaIfAsked()
        }
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

    private func openAgendaIfAsked() {
        if AgendaQuickAction.consume() {
            model.openAgenda()
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
