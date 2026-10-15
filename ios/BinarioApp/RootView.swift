import SwiftUI

struct RootView: View {
    var body: some View {
        TabView {
            NavigationStack { PlannerView() }
                .tabItem { Label("tab.planner", systemImage: "point.topleft.down.to.point.bottomright.curvepath") }

            NavigationStack { SavedRoutesView() }
                .tabItem { Label("tab.saved", systemImage: "star") }

            NavigationStack { SettingsView() }
                .tabItem { Label("tab.settings", systemImage: "gearshape") }
        }
    }
}
