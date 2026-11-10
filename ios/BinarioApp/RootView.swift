import SwiftUI

struct RootView: View {
    @EnvironmentObject private var model: AppModel

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
    }
}
